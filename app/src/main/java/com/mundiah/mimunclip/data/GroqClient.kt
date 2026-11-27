package com.mundiah.mimunclip.data

import com.google.gson.Gson
import com.mundiah.mimunclip.model.QwenDefaults
import com.mundiah.mimunclip.model.Segment
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Klien Groq langsung via REST (https://api.groq.com/openai/v1), tanpa SDK Groq.
 * - Whisper: POST /audio/transcriptions (multipart), model whisper-large-v3-turbo,
 *   language=id, response_format=verbose_json, timestamp_granularities=[segment].
 * - Chat: POST /chat/completions (JSON), response_format json_object.
 *
 * Multi-key: beberapa key dipisah koma/baris baru; saat respons 429/401/5xx
 * atau timeout/IO error, otomatis rolling ke key berikutnya. Key TIDAK pernah
 * ditulis ke log — hanya 4 karakter terakhir yang ditampilkan.
 */
class GroqClient(
    private val keys: List<String>,
    private val http: OkHttpClient = defaultHttpClient(),
) {
    init {
        require(keys.isNotEmpty()) { "Groq API key belum diisi. Buka Pengaturan." }
    }

    companion object {
        const val BASE_URL = "https://api.groq.com/openai/v1"
        private val JSON = "application/json; charset=utf-8".toMediaType()

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS) // transkripsi video panjang
            .writeTimeout(120, TimeUnit.SECONDS)
            .build()

        fun maskKey(key: String): String =
            if (key.length >= 4) "…${key.takeLast(4)}" else "…"

        fun parseKeys(raw: String): List<String> =
            raw.split(',', '\n', ';')
                .map { it.trim().trim('"', '\'') }
                .filter { it.isNotEmpty() && it != "masukkan_api_key" }
                .distinct()
    }

    private val gson = Gson()
    private var keyIndex = 0

    private class RollableException(message: String) : IOException(message)
    private class FatalGroqException(message: String) : IOException(message)

    /**
     * Jalankan [block] dengan satu key; rolling otomatis bila gagal dengan
     * alasan yang bisa dipulihkan (429/401/5xx/timeout/koneksi).
     */
    private fun <T> withRollingKey(label: String, block: (key: String) -> T): T {
        var lastError: Exception? = null
        for (attempt in keys.indices) {
            val idx = (keyIndex + attempt) % keys.size
            val key = keys[idx]
            try {
                val result = block(key)
                keyIndex = idx
                return result
            } catch (e: RollableException) {
                lastError = e
                // Lanjut ke key berikutnya.
            } catch (e: FatalGroqException) {
                // Fatal (mis. 400: request tidak valid) — ganti key tidak membantu.
                throw e
            } catch (e: IOException) {
                lastError = e
                if (attempt == keys.lastIndex) break
            }
        }
        throw IOException(
            "$label gagal di semua ${keys.size} key Groq " +
                "(terakhir: ${lastError?.message}). " +
                "Periksa key di Pengaturan / kuota Groq.",
            lastError
        )
    }

    private fun postJson(key: String, path: String, json: String): String {
        val req = Request.Builder()
            .url(BASE_URL + path)
            .addHeader("Authorization", "Bearer $key")
            .post(json.toRequestBody(JSON))
            .build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            when {
                resp.isSuccessful -> return body
                resp.code in listOf(429, 401) || resp.code >= 500 ->
                    throw RollableException("HTTP ${resp.code}: ${body.take(200)}")
                else -> throw FatalGroqException("HTTP ${resp.code}: ${body.take(300)}")
            }
        }
    }

    // ---------- Whisper ----------

    private data class WhisperResponse(
        val segments: List<WhisperSegment>? = null,
    )
    private data class WhisperSegment(
        val start: Double = 0.0,
        val end: Double = 0.0,
        val text: String = "",
    )

    /**
     * Transkripsi [audioFile] (mp3 mono 16kHz dari FfmpegRenderer).
     * Mengembalikan segmen ber-timestamp, diurutkan berdasarkan start.
     */
    fun transcribe(audioFile: File, onKeyNote: ((String) -> Unit)? = null): List<Segment> {
        return withRollingKey("Transkripsi Whisper") { key ->
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file", audioFile.name,
                    audioFile.asRequestBody("audio/mpeg".toMediaType())
                )
                .addFormDataPart("model", QwenDefaults.WHISPER_MODEL)
                .addFormDataPart("language", "id")
                .addFormDataPart("response_format", "verbose_json")
                .addFormDataPart("timestamp_granularities[]", "segment")
                .build()
            val req = Request.Builder()
                .url("$BASE_URL/audio/transcriptions")
                .addHeader("Authorization", "Bearer $key")
                .post(body)
                .build()
            http.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                when {
                    resp.isSuccessful -> {
                        val parsed = try {
                            gson.fromJson(raw, WhisperResponse::class.java)
                        } catch (e: Exception) {
                            throw FatalGroqException(
                                "Respons Whisper tidak bisa dibaca: ${e.message}"
                            )
                        }
                        val segments = (parsed.segments ?: emptyList())
                            .filter { it.text.isNotBlank() && it.end > it.start }
                            .sortedBy { it.start }
                            .mapIndexed { i, s ->
                                Segment(i + 1, s.start, s.end, s.text.trim())
                            }
                        if (segments.isEmpty()) {
                            throw FatalGroqException(
                                "Whisper tidak menemukan ucapan dalam audio."
                            )
                        }
                        onKeyNote?.invoke("Whisper OK (${maskKey(key)})")
                        segments
                    }
                    resp.code in listOf(429, 401) || resp.code >= 500 ->
                        throw RollableException("HTTP ${resp.code}: ${raw.take(200)}")
                    else -> throw FatalGroqException("HTTP ${resp.code}: ${raw.take(300)}")
                }
            }
        }
    }

    // ---------- Chat (Qwen) ----------

    private data class ChatResponse(
        val choices: List<ChatChoice>? = null,
    )
    private data class ChatChoice(
        val message: ChatMessage? = null,
    )
    private data class ChatMessage(
        val content: String? = null,
    )

    /**
     * Chat completion dengan response_format json_object. Bila model utama
     * ditolak server (400 model tidak ditemukan / tidak didukung), otomatis
     * coba sekali dengan [fallbackModel].
     */
    fun chatJson(
        model: String,
        fallbackModel: String,
        systemPrompt: String,
        userPrompt: String,
        temperature: Double = 0.25,
        maxTokens: Int = 4096,
    ): String {
        val payload = mapOf(
            "model" to model,
            "messages" to listOf(
                mapOf("role" to "system", "content" to systemPrompt),
                mapOf("role" to "user", "content" to userPrompt),
            ),
            "temperature" to temperature,
            "max_completion_tokens" to maxTokens,
            "response_format" to mapOf("type" to "json_object"),
        )
        val json = gson.toJson(payload)
        return try {
            withRollingKey("Qwen ($model)") { key -> postJson(key, "/chat/completions", json) }
                .let { extractContent(it) }
        } catch (e: IOException) {
            val msg = e.message.orEmpty()
            val modelRejected = "400" in msg &&
                ("model" in msg.lowercase() || "not found" in msg.lowercase())
            if (modelRejected && fallbackModel != model) {
                val fallbackPayload = gson.toJson(payload + ("model" to fallbackModel))
                withRollingKey("Qwen fallback ($fallbackModel)") { key ->
                    postJson(key, "/chat/completions", fallbackPayload)
                }.let { extractContent(it) }
            } else {
                throw e
            }
        }
    }

    private fun extractContent(rawJson: String): String {
        val parsed = try {
            gson.fromJson(rawJson, ChatResponse::class.java)
        } catch (e: Exception) {
            throw IOException("Respons Qwen tidak bisa dibaca: ${e.message}")
        }
        val content = parsed.choices?.firstOrNull()?.message?.content.orEmpty().trim()
        if (content.isBlank()) throw IOException("Respons Qwen kosong.")
        return content
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```").trim()
    }
}
