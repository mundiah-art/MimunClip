package com.mundiah.mimunclip.video

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Simpan video hasil ke Downloads/MimunClip via MediaStore (Android 10+,
 * tanpa izin storage tambahan untuk file milik aplikasi sendiri).
 * Nama file: VID_yyyyMMdd_HHmmss_SlugHook.mp4
 */
object MediaStoreSaver {

    fun slugFromTitle(title: String, maxWords: Int = 4): String {
        val cleaned = title.replace(Regex("[^A-Za-z0-9 ]"), " ")
        val words = cleaned.split(" ")
            .filter { it.length > 1 }
            .take(maxWords)
            .map { it.replaceFirstChar { c -> c.uppercaseChar() } }
        val slug = words.joinToString("").take(32)
        return slug.ifEmpty { "Clip" }
    }

    fun fileNameFor(title: String, at: Date = Date()): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(at)
        return "VID_${stamp}_${slugFromTitle(title)}.mp4"
    }

    /**
     * Salin [source] ke Downloads/MimunClip/[fileName].
     * Mengembalikan Uri MediaStore-nya. File sumber TIDAK dihapus.
     */
    fun saveToDownloads(context: Context, source: File, fileName: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "video/mp4")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/MimunClip")
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Gagal membuat entri MediaStore untuk $fileName")
        resolver.openOutputStream(uri)?.use { out ->
            FileInputStream(source).use { input -> input.copyTo(out) }
        } ?: throw IllegalStateException("Gagal membuka output stream untuk $fileName")
        return uri
    }
}
