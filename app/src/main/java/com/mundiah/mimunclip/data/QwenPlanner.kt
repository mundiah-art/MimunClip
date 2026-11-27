package com.mundiah.mimunclip.data

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.mundiah.mimunclip.model.ClipCandidate
import com.mundiah.mimunclip.model.ClipDefaults
import com.mundiah.mimunclip.model.ClipRange
import com.mundiah.mimunclip.model.Segment

/**
 * Perencanaan klip via Groq Qwen + planner lokal sebagai fallback.
 *
 * Alur v1:
 *  1. Qwen merapikan transkrip Whisper yang berantakan.
 *  2. Qwen mengusulkan 4 kandidat hook/klip (35–50 detik, min fallback 28 detik)
 *     lengkap dengan judul hook, hook_quote, dan caption + maks 5 hashtag.
 *  3. Semua kandidat divalidasi dengan [ContentFilter] (HANYA stok + promo).
 *     Segmen terlarang tidak pernah jadi awal klip dan tidak pernah masuk
 *     rentang klip; caption yang lolos filter diganti caption fallback aman.
 *  4. Bila Qwen gagal / JSON tidak valid / tidak ada kandidat bersih,
 *     [LocalClipPlanner] membangun kandidat dari segmen bersih secara lokal.
 */
class QwenPlanner(private val groq: GroqClient) {

    private val gson = Gson()

    // ---------- 1. Rapikan transkrip ----------

    private data class CleanedResponse(
        val cleaned: List<CleanedSegment>? = null,
    )
    private data class CleanedSegment(val id: Int = 0, val text: String = "")

    /** Kembalikan segmen dengan teks yang sudah dirapikan; fallback = teks asli. */
    fun cleanTranscript(segments: List<Segment>, model: String, fallbackModel: String): List<Segment> {
        val lines = segments.joinToString("\n") { "[${it.id}] ${it.text}" }
        val prompt = "Rapikan transkrip Bahasa Indonesia berikut dari video live " +
            "TikTok Shop fashion wanita. Perbaiki kata yang salah dengar, " +
            "tanda baca, dan kapitalisasi, tapi JANGAN ubah arti, JANGAN " +
            "tambah kata promosi, JANGAN hapus segmen, dan JANGAN ubah id.\n\n" +
            "Kembalikan HANYA JSON valid: " +
            "{\"cleaned\":[{\"id\":1,\"text\":\"kalimat rapi\"}]}\n\nTRANSKRIP:\n$lines"
        return try {
            val raw = groq.chatJson(
                model, fallbackModel,
                "Kembalikan hanya JSON valid tanpa markdown atau penjelasan tambahan.",
                prompt, temperature = 0.1, maxTokens = 4096
            )
            val parsed = gson.fromJson(raw, CleanedResponse::class.java)
            val byId = parsed.cleaned.orEmpty().associateBy { it.id }
            segments.map { seg ->
                val clean = byId[seg.id]?.text?.trim()
                if (clean.isNullOrBlank()) seg else seg.copy(text = clean)
            }
        } catch (e: Exception) {
            segments // Qwen gagal: lanjut dengan transkrip mentah.
        }
    }

    // ---------- 2. Usulan kandidat dari Qwen ----------

    private data class PlanResponse(val clips: List<RawClip>? = null)
    private data class RawClip(
        val title: String? = null,
        val hook_quote: String? = null,
        val caption: String? = null,
        val parts: List<RawPart>? = null,
    )
    private data class RawPart(val start_segment: Int = 0, val end_segment: Int = 0)

    /**
     * Minta 4 kandidat ke Qwen, lalu validasi dan lengkapi rentangnya secara
     * lokal (mengikuti logika workflow lama: rentang berurutan dari segmen
     * hook, lewati segmen terlarang, target 35–50 detik).
     */
    fun planCandidates(
        segments: List<Segment>,
        videoDurationSec: Double,
        model: String,
        fallbackModel: String,
    ): List<ClipCandidate> {
        val transcript = segments.joinToString("\n") { s ->
            val bad = ContentFilter.firstForbiddenLabel(s.text)
            val tag = if (bad != null) " [TERLARANG:$bad]" else ""
            "[${s.id}] [${"%.2f".format(s.start)}-${"%.2f".format(s.end)}]$tag ${s.text}"
        }
        val prompt = """
            Kamu adalah Asisten Copywriter TikTok Affiliate khusus Fashion Wanita (gaya Racun TikTok) & editor video ahli retensi.
            Dari transkrip video berdurasi ~${videoDurationSec.toInt()} detik di bawah ini, pilih 4 KANDIDAT TITIK HOOK TERBAIK untuk dipotong menjadi klip berdurasi 35 hingga 50 detik (urutkan dari yang hook-nya paling kuat).

            ATURAN WAJIB:
            1. Setiap kandidat klip harus berupa 1 RENTANG SEGMEN BERURUTAN dimulai tepat dari kalimat HOOK TERKUAT agar orang berhenti scroll.
            2. JUDUL HOOK (title): 2-4 kata singkat yang menggambarkan inti hook klip tersebut (tanpa tanda baca, untuk nama file).
            3. CAPTION & HASHTAG (caption): Gunakan FORMAT ini:
               [Nama/Judul Produk Spesifik]! [Highlight Utama / Spesifikasi Bahan seperti Rayon Premium/Viscose Rajut/Twill/Cupro/Katun & Detail Desain Unik], [Karakter Look seperti Casual Chic/Elegant/Retro/Classy/Flowy], Nyaman Dipakai [Konteks Pemakaian / OOTD] ✨ Langsung cek keranjang kuning ya!
               [Baris baru berisi maksimal 5 hashtag]
               - DILARANG menggunakan kata 'Rekomendasi' atau kata pembuka basa-basi di awal caption. Langsung mulai dengan Nama/Judul Produk!
               - Total hashtag MAKSIMAL 5 buah, WAJIB ada 1 hashtag spesifik nama/tipe pakaian (misal #onesetrayon, #blousewanita) serta wajib ada #racuntiktok dan #ootdwanita.
               - DILARANG di caption: kata 'link', 'tautan', 'bio', kata stok (stok tinggal, stok terbatas, sisa stok), 'PO/pre-order', harga/nominal, diskon, promo, cashback, voucher, atau lokasi pengiriman.
            4. DURASI: Total durasi tiap klip di kisaran 35 hingga 50 detik.
            5. LARANGAN MUTLAK VIDEO: JANGAN PERNAH memilih segmen yang ditandai [TERLARANG] atau yang menyebut stok (tinggal/sisa/stok) atau promo (diskon/promo/cashback/voucher/kupon/flash sale/sale/deal).
            6. Pastikan 2 kandidat teratas tidak menggunakan nomor segmen yang sama.

            Kembalikan HANYA JSON valid dengan format berikut:
            {"clips":[{"title":"Blouse Rayon Twill Adem","hook_quote":"kalimat hook","caption":"Blouse Rayon Twill Premium! Bahan super adem, jatuh, dan anti kusut dengan detail kerah lipat manis, tampilan Casual Chic dan Flowy, Nyaman Dipakai ngantor maupun hangout OOTD ✨ Langsung cek keranjang kuning ya!\n#blouserayon #atasanwanita #racuntiktok #ootdwanita #fyp","parts":[{"start_segment":2,"end_segment":12}]}]}

            TRANSKRIP:
            $transcript
        """.trimIndent()

        val rawClips: List<RawClip> = try {
            val raw = groq.chatJson(
                model, fallbackModel,
                "Kembalikan hanya JSON valid tanpa markdown atau penjelasan tambahan.",
                prompt, temperature = 0.25, maxTokens = 4096
            )
            gson.fromJson(raw, PlanResponse::class.java).clips.orEmpty()
        } catch (e: Exception) {
            emptyList()
        }

        val byId = segments.associateBy { it.id }
        val candidates = mutableListOf<ClipCandidate>()
        for (raw in rawClips) {
            val part = raw.parts?.firstOrNull() ?: continue
            val built = LocalClipPlanner.buildFromStart(
                segments, part.start_segment, ClipDefaults.MAX_CLIP_SEC
            ) ?: continue
            val title = raw.title?.trim().takeUnless { it.isNullOrBlank() }
                ?: byId[built.startSegmentId]?.text
                    ?.split(" ")?.take(4)?.joinToString(" ")
                ?: "Outfit Pilihan"
            val hook = raw.hook_quote?.trim().takeUnless { it.isNullOrBlank() }
                ?: byId[built.startSegmentId]?.text.orEmpty()
            var caption = raw.caption?.trim().orEmpty()
            if (caption.isBlank() || ContentFilter.hasForbiddenCaptionWords(caption)) {
                caption = ContentFilter.safeFallbackCaption(title)
            }
            candidates += ClipCandidate(
                title = title, hookQuote = hook, caption = caption,
                startSegmentId = built.startSegmentId,
                endSegmentId = built.endSegmentId,
                ranges = built.ranges, durationSec = built.durationSec,
            )
            if (candidates.size >= ClipDefaults.MAX_CANDIDATES) break
        }

        // Lengkapi dengan kandidat lokal bila Qwen memberi kurang dari 4.
        if (candidates.size < ClipDefaults.MAX_CANDIDATES) {
            val usedStarts = candidates.map { it.startSegmentId }.toSet()
            val extra = LocalClipPlanner.plan(segments)
                .filter { it.startSegmentId !in usedStarts }
            candidates += extra.take(ClipDefaults.MAX_CANDIDATES - candidates.size)
        }
        return candidates.distinctBy { it.startSegmentId }
            .take(ClipDefaults.MAX_CANDIDATES)
    }
}

/**
 * Planner lokal (tanpa LLM): pindai segmen bersih sebagai titik awal hook,
 * kumpulkan segmen berurutan sampai 35–50 detik, lewati segmen terlarang
 * (potongan boleh terbelah jadi beberapa rentang, tiap potongan >= 12 detik).
 * Dipakai sebagai fallback bila Qwen gagal, dan untuk melengkapi kandidat.
 */
object LocalClipPlanner {

    data class BuiltClip(
        val startSegmentId: Int,
        val endSegmentId: Int,
        val ranges: List<ClipRange>,
        val durationSec: Double,
    )

    fun plan(segments: List<Segment>): List<ClipCandidate> {
        val byId = segments.associateBy { it.id }
        val out = mutableListOf<ClipCandidate>()
        val usedStarts = mutableSetOf<Int>()
        for (seg in segments) {
            if (out.size >= ClipDefaults.MAX_CANDIDATES) break
            if (seg.id in usedStarts) continue
            val built = buildFromStart(segments, seg.id, ClipDefaults.MAX_CLIP_SEC)
                ?: continue
            usedStarts += built.startSegmentId
            val title = byId[built.startSegmentId]?.text
                ?.split(" ")?.take(4)?.joinToString(" ") ?: "Outfit Pilihan"
            val hook = byId[built.startSegmentId]?.text.orEmpty()
            out += ClipCandidate(
                title = title, hookQuote = hook,
                caption = ContentFilter.safeFallbackCaption(title),
                startSegmentId = built.startSegmentId,
                endSegmentId = built.endSegmentId,
                ranges = built.ranges, durationSec = built.durationSec,
            )
        }
        return out
    }

    /**
     * Bangun satu klip dari [startSegmentId]: kumpulkan segmen berurutan,
     * berhenti bila melewati [targetMaxSec]. Segmen terlarang memutus
     * potongan; potongan < 12 detik dibuang. Mengembalikan null bila total
     * durasi bersih < 28 detik.
     */
    fun buildFromStart(
        segments: List<Segment>,
        startSegmentId: Int,
        targetMaxSec: Double,
    ): BuiltClip? {
        val byId = segments.associateBy { it.id }
        val startSeg = byId[startSegmentId] ?: return null
        if (ContentFilter.hasForbiddenWords(startSeg.text)) return null

        val ranges = mutableListOf<ClipRange>()
        val usedIds = mutableListOf<Int>()
        var total = 0.0
        var curStart: Double? = null
        var curEnd: Double? = null
        var curIds = mutableListOf<Int>()

        fun flush() {
            val s = curStart
            val e = curEnd
            if (s != null && e != null && (e - s) >= ClipDefaults.MIN_SUB_PART_SEC) {
                ranges += ClipRange(s, e)
                usedIds += curIds
                total += (e - s)
            }
            curStart = null; curEnd = null; curIds = mutableListOf()
        }

        val maxId = segments.maxOf { it.id }
        for (sid in startSegmentId..maxId) {
            val seg = byId[sid] ?: continue
            if (ContentFilter.hasForbiddenWords(seg.text)) {
                flush()
                if (ranges.size >= 3 || total >= 34.0) break
                continue
            }
            if (curStart == null) {
                curStart = seg.start; curEnd = seg.end; curIds = mutableListOf(sid)
            } else {
                if (total + (seg.end - curStart!!) > targetMaxSec) break
                curEnd = seg.end; curIds += sid
            }
        }
        flush()
        if (total < ClipDefaults.MIN_TOTAL_CLIP_SEC || usedIds.isEmpty()) return null
        return BuiltClip(usedIds.first(), usedIds.last(), ranges, total)
    }
}
