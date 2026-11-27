package com.mundiah.mimunclip.model

import android.net.Uri

/** Satu segmen hasil transkripsi Whisper (verbose_json + segment timestamps). */
data class Segment(
    val id: Int,
    val start: Double,
    val end: Double,
    val text: String,
)

/** Satu rentang waktu kontinu di dalam sebuah klip (start-end dalam detik). */
data class ClipRange(
    val start: Double,
    val end: Double,
)

/**
 * Satu kandidat klip yang diusulkan Qwen (atau planner lokal bila Qwen gagal).
 * [startSegmentId]/[endSegmentId] adalah id segmen Whisper pembentuk klip.
 */
data class ClipCandidate(
    val title: String,
    val hookQuote: String,
    val caption: String,
    val startSegmentId: Int,
    val endSegmentId: Int,
    val ranges: List<ClipRange>,
    val durationSec: Double,
) {
    val rangeLabel: String
        get() = ranges.joinToString(" + ") { r ->
            "${r.start.toInt()}–${r.end.toInt()} dtk"
        }
}

/** Hasil render satu klip yang tersimpan di riwayat lokal. */
data class ClipResult(
    val id: String,
    val title: String,
    val hookQuote: String,
    val caption: String,
    val fileName: String,
    /** Uri MediaStore (content://) dari file hasil di Downloads/MimunClip. */
    val uri: String,
    val durationSec: Double,
    val slsrApplied: Boolean,
    val slsrNote: String,
    val createdAtMillis: Long,
) {
    val contentUri: Uri get() = Uri.parse(uri)
}

/** Pengaturan render yang dipilih user di layar utama / pengaturan. */
data class RenderSettings(
    val clipCount: Int = 2,
    val slsrEnabled: Boolean = true,
    val casEnabled: Boolean = true,
    val unsharpEnabled: Boolean = true,
    val qwenModel: String = QwenDefaults.MODEL,
    val qwenFallbackModel: String = QwenDefaults.FALLBACK_MODEL,
    val darkTheme: Boolean? = null, // null = ikuti sistem
)

object QwenDefaults {
    /**
     * Model default. Catatan: workflow lama menulis "qwen/qwen3.8-27b"
     * yang tampaknya typo (tidak ada model Groq bernama itu) — v1 memakai
     * "qwen/qwen3-32b" dan bisa diubah di Settings.
     */
    const val MODEL = "qwen/qwen3-32b"
    const val FALLBACK_MODEL = "llama-3.3-70b-versatile"
    const val WHISPER_MODEL = "whisper-large-v3-turbo"
}

object ClipDefaults {
    const val MIN_CLIP_SEC = 35.0
    const val MAX_CLIP_SEC = 50.0
    /** Fallback bila materi bersih tidak cukup untuk 35 detik. */
    const val MIN_TOTAL_CLIP_SEC = 28.0
    const val MIN_SUB_PART_SEC = 12.0
    const val MAX_CANDIDATES = 4
    const val MAX_CLIPS = 4
    const val MAX_HASHTAGS = 5
}
