package com.mundiah.mimunclip.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.mundiah.mimunclip.model.ClipResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Riwayat hasil render, disimpan lokal sebagai JSON di internal storage.
 * TIDAK pernah menghapus file video — entri riwayat yang dihapus hanya
 * menghilangkan catatan; file MP4 di Downloads/MimunClip tetap ada.
 */
class HistoryStore(private val context: Context) {

    private val gson = Gson()
    private val file: File get() = File(context.filesDir, "history.json")
    private val type = object : TypeToken<List<ClipResult>>() {}.type

    suspend fun loadAll(): List<ClipResult> = withContext(Dispatchers.IO) {
        try {
            if (!file.exists()) return@withContext emptyList()
            val list: List<ClipResult>? = gson.fromJson(file.readText(), type)
            (list ?: emptyList()).sortedByDescending { it.createdAtMillis }
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun add(result: ClipResult) = withContext(Dispatchers.IO) {
        val current = loadAll().toMutableList()
        current.removeAll { it.id == result.id }
        current.add(0, result)
        file.writeText(gson.toJson(current.take(200)))
    }

    suspend fun addAll(results: List<ClipResult>) {
        for (r in results) add(r)
    }

    /** Hapus catatan saja; file video TIDAK dihapus. */
    suspend fun remove(id: String) = withContext(Dispatchers.IO) {
        val current = loadAll().filter { it.id != id }
        file.writeText(gson.toJson(current))
    }
}
