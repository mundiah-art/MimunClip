package com.mundiah.mimunclip.video

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.ReturnCode
import com.mundiah.mimunclip.model.ClipCandidate
import com.mundiah.mimunclip.model.RenderSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/**
 * Render video dengan FFmpegKit (dev.ffmpegkit-maintained, API sama dengan
 * com.arthenica.ffmpegkit).
 *
 * - Ekstrak audio mono 16 kHz mp3 untuk Whisper.
 * - Render klip: -ss SEBELUM -i, lalu re-encode (libx264 veryfast crf 23,
 *   AAC stereo 44100). Filter video SELALU crop 9:16 + scale 720x1280 +
 *   fps 30; CAS 90% dan Unsharp 90% hanya bila toggle-nya ON. Bila tidak
 *   ada filter aktif pun tetap re-encode ringan agar hasil konsisten
 *   (bukan stream-copy buta).
 * - Klip multi-rentang: tiap rentang di-render terpisah, lalu digabung
 *   dengan concat demuxer + re-encode ringan.
 * - SLSR: bila toggle ON, hasil render di-patch [Slsr.patchBytesSlsr].
 *   Gagal -> perbaiki audio dulu ke AAC stereo 44100 (copy video) lalu
 *   coba lagi -> masih gagal -> pakai hasil render langsung tanpa SLSR
 *   dan laporkan lewat [RenderOutcome.slsrNote] (bukan error fatal).
 */
class FfmpegRenderer(private val context: Context) {

    data class RenderOutcome(
        val file: File,
        val slsrApplied: Boolean,
        val slsrNote: String,
    )

    data class VideoInfo(val durationSec: Double, val width: Int, val height: Int)

    fun probe(uri: Uri): VideoInfo {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val durationMs = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull() ?: 0L
            val width = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
            )?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
            )?.toIntOrNull() ?: 0
            return VideoInfo(durationMs / 1000.0, width, height)
        } finally {
            try { retriever.release() } catch (e: Exception) { /* abaikan */ }
        }
    }

    /** Salin video pilihan user (content Uri) ke cache agar FFmpeg bisa membacanya. */
    suspend fun copyInputToCache(uri: Uri, fileName: String = "input.mp4"): File =
        withContext(Dispatchers.IO) {
            val out = File(context.cacheDir, fileName)
            context.contentResolver.openInputStream(uri)?.use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            } ?: throw IllegalStateException("Tidak bisa membaca video yang dipilih.")
            out
        }

    private suspend fun runFfmpeg(
        command: String,
        onProgress: ((percent: Float, timeMs: Long) -> Unit)?,
        totalDurationMs: Long,
    ): FFmpegSession = suspendCancellableCoroutine { cont ->
        // FFmpegKit memuat library native (.so) saat pertama dipakai; bila
        // gagal di perangkat (UnsatisfiedLinkError dsb.) itu adalah Error,
        // bukan Exception — ubah jadi IllegalStateException supaya pemanggil
        // bisa menampilkannya sebagai pesan error, bukan force close.
        val session = try {
            FFmpegKit.executeAsync(
                command,
                { s ->
                    if (cont.isActive) cont.resume(s)
                },
                { log -> /* log FFmpeg tersedia di logcat via FFmpegKitConfig jika perlu */ },
                { statistics ->
                    if (totalDurationMs > 0) {
                        val pct = (statistics.time.toFloat() / totalDurationMs * 100f)
                            .coerceIn(0f, 100f)
                        onProgress?.invoke(pct, statistics.time.toLong())
                    }
                }
            )
        } catch (e: Throwable) {
            if (cont.isActive) {
                cont.resumeWith(Result.failure(
                    IllegalStateException(
                        "FFmpeg gagal dimuat di perangkat ini " +
                            "(${e.javaClass.simpleName}: ${e.message}). " +
                            "Coba restart HP, lalu install ulang APK."
                    )
                ))
            }
            return@suspendCancellableCoroutine
        }
        cont.invokeOnCancellation {
            try { FFmpegKit.cancel(session.sessionId) } catch (e: Exception) { /* abaikan */ }
        }
    }

    private fun checkSession(session: FFmpegSession, step: String) {
        val rc = session.returnCode
        if (!ReturnCode.isSuccess(rc)) {
            val err = session.failStackTrace?.take(500).orEmpty()
            throw IllegalStateException(
                "$step gagal (returnCode=$rc). ${err.ifBlank { "Lihat logcat FFmpegKit." }}"
            )
        }
    }

    suspend fun extractAudioForWhisper(
        source: File,
        workDir: File,
        onProgress: ((Float) -> Unit)? = null,
    ): File = withContext(Dispatchers.IO) {
        val out = File(workDir, "audio.mp3")
        val info = probe(Uri.fromFile(source))
        val cmd = "-y -hide_banner -loglevel error -i \"${source.absolutePath}\" " +
            "-map 0:a:0 -vn -af aresample=async=1:first_pts=0 " +
            "-ac 1 -ar 16000 -c:a libmp3lame -b:a 32k \"${out.absolutePath}\""
        val session = runFfmpeg(
            cmd,
            { pct, _ -> onProgress?.invoke(pct) },
            (info.durationSec * 1000).toLong()
        )
        checkSession(session, "Ekstrak audio")
        if (!out.exists() || out.length() == 0L) {
            throw IllegalStateException("Audio hasil ekstrak kosong.")
        }
        out
    }

    private fun videoFilterChain(settings: RenderSettings): String {
        val filters = mutableListOf<String>()
        // Potong ke rasio vertikal 9:16 lalu skala ke 720x1280, 30 fps.
        filters += "crop=min(iw\\,ih*9/16):ih"
        filters += "scale=720:1280"
        filters += "fps=30"
        if (settings.casEnabled) filters += "cas=0.9"
        if (settings.unsharpEnabled) filters += "unsharp=5:5:0.9:5:5:0.0"
        return filters.joinToString(",")
    }

    private fun encodeArgs(): String =
        "-c:v libx264 -preset veryfast -crf 23 -c:a aac -ac 2 -ar 44100 -movflags +faststart"

    private suspend fun renderRange(
        source: File,
        startSec: Double,
        durationSec: Double,
        out: File,
        settings: RenderSettings,
        onProgress: ((Float) -> Unit)?,
    ) {
        val vf = videoFilterChain(settings)
        val cmd = "-y -hide_banner -loglevel error " +
            "-ss ${"%.3f".format(startSec)} -i \"${source.absolutePath}\" " +
            "-t ${"%.3f".format(durationSec)} " +
            "-vf \"$vf\" ${encodeArgs()} \"${out.absolutePath}\""
        val session = runFfmpeg(
            cmd,
            { pct, _ -> onProgress?.invoke(pct) },
            (durationSec * 1000).toLong()
        )
        checkSession(session, "Render klip")
        if (!out.exists() || out.length() == 0L) {
            throw IllegalStateException("Hasil render kosong.")
        }
    }

    private suspend fun fixAudioContainer(source: File, out: File) {
        val cmd = "-y -hide_banner -loglevel error -i \"${source.absolutePath}\" " +
            "-c:v copy -c:a aac -ac 2 -ar 44100 \"${out.absolutePath}\""
        val session = runFfmpeg(cmd, null, 0)
        checkSession(session, "Perbaiki audio (AAC stereo 44100)")
        if (!out.exists() || out.length() == 0L) {
            throw IllegalStateException("File hasil perbaikan audio kosong.")
        }
    }

    private suspend fun applySlsrWithFallback(
        rendered: File,
        workDir: File,
        clipIndex: Int,
    ): Pair<File, String> {
        // Percobaan 1: langsung pada hasil render.
        try {
            val patched = Slsr.patchBytesSlsr(rendered.readBytes())
            val out = File(workDir, "clip_${clipIndex}_slsr.mp4")
            out.writeBytes(patched)
            return out to "SLSR aktif"
        } catch (e: Exception) {
            // Percobaan 2: perbaiki audio dulu, lalu coba lagi.
            try {
                val fixed = File(workDir, "clip_${clipIndex}_fixed.mp4")
                fixAudioContainer(rendered, fixed)
                val patched = Slsr.patchBytesSlsr(fixed.readBytes())
                val out = File(workDir, "clip_${clipIndex}_slsr.mp4")
                out.writeBytes(patched)
                return out to "SLSR aktif (setelah perbaikan audio)"
            } catch (e2: Exception) {
                // Fallback terakhir: pakai hasil render langsung tanpa SLSR.
                return rendered to "SLSR gagal (${e2.message?.take(80) ?: "tidak memenuhi syarat"}), " +
                    "dipakai hasil render langsung tanpa SLSR"
            }
        }
    }

    /**
     * Render satu kandidat menjadi file MP4 final di [workDir].
     * [onProgress] menerima 0..100 untuk klip ini.
     */
    suspend fun renderClip(
        source: File,
        candidate: ClipCandidate,
        clipIndex: Int,
        settings: RenderSettings,
        workDir: File,
        onProgress: ((Float) -> Unit)? = null,
    ): RenderOutcome = withContext(Dispatchers.IO) {
        val rendered = File(workDir, "clip_${clipIndex}_render.mp4")
        if (candidate.ranges.size == 1) {
            val r = candidate.ranges[0]
            renderRange(source, r.start, r.end - r.start, rendered, settings) { pct ->
                onProgress?.invoke(pct * 0.85f)
            }
        } else {
            val parts = mutableListOf<File>()
            candidate.ranges.forEachIndexed { i, r ->
                val part = File(workDir, "clip_${clipIndex}_part_$i.mp4")
                renderRange(source, r.start, r.end - r.start, part, settings) { pct ->
                    val base = i.toFloat() / candidate.ranges.size
                    onProgress?.invoke((base + pct / 100f / candidate.ranges.size) * 60f)
                }
                parts += part
            }
            val listFile = File(workDir, "clip_${clipIndex}_concat.txt")
            listFile.writeText(parts.joinToString("\n") { "file '${it.absolutePath}'" })
            val cmd = "-y -hide_banner -loglevel error -f concat -safe 0 " +
                "-i \"${listFile.absolutePath}\" ${encodeArgs()} \"${rendered.absolutePath}\""
            val session = runFfmpeg(cmd, { pct, _ -> onProgress?.invoke(60f + pct * 0.25f) }, 0)
            checkSession(session, "Gabung potongan klip")
            parts.forEach { it.delete() }
        }

        if (!settings.slsrEnabled) {
            return@withContext RenderOutcome(rendered, false, "SLSR nonaktif (toggle off)")
        }
        val (finalFile, note) = applySlsrWithFallback(rendered, workDir, clipIndex)
        onProgress?.invoke(100f)
        val applied = finalFile != rendered
        RenderOutcome(finalFile, applied, note)
    }
}
