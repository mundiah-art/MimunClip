package com.mundiah.mimunclip.data

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.mundiah.mimunclip.model.QwenDefaults
import com.mundiah.mimunclip.model.RenderSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "mimunclip_settings")

/**
 * Penyimpanan pengaturan.
 * - Pengaturan biasa (jumlah klip, toggle, model, tema): DataStore Preferences.
 * - Groq API key: EncryptedSharedPreferences (AES-256). Bila enkripsi gagal
 *   di perangkat (mis. keystore bermasalah), fallback ke SharedPreferences
 *   biasa supaya app tetap bisa dipakai — key tetap TIDAK pernah di-log
 *   dan hanya ditampilkan dalam bentuk mask (…abcd).
 */
class SettingsStore(private val context: Context) {

    companion object {
        private val KEY_CLIP_COUNT = intPreferencesKey("clip_count")
        private val KEY_SLSR = booleanPreferencesKey("slsr_enabled")
        private val KEY_CAS = booleanPreferencesKey("cas_enabled")
        private val KEY_UNSHARP = booleanPreferencesKey("unsharp_enabled")
        private val KEY_QWEN_MODEL = stringPreferencesKey("qwen_model")
        private val KEY_QWEN_FALLBACK = stringPreferencesKey("qwen_fallback_model")
        private val KEY_THEME = stringPreferencesKey("theme") // system | light | dark
        private const val ENC_PREFS = "mimunclip_keys"
        private const val ENC_KEY_GROQ = "groq_keys_raw"
        private const val PLAIN_PREFS = "mimunclip_keys_plain"
    }

    suspend fun load(): RenderSettings {
        val prefs = context.dataStore.data.first()
        val theme = prefs[KEY_THEME] ?: "system"
        return RenderSettings(
            clipCount = (prefs[KEY_CLIP_COUNT] ?: 2).coerceIn(1, 4),
            slsrEnabled = prefs[KEY_SLSR] ?: true,
            casEnabled = prefs[KEY_CAS] ?: true,
            unsharpEnabled = prefs[KEY_UNSHARP] ?: true,
            qwenModel = (prefs[KEY_QWEN_MODEL] ?: QwenDefaults.MODEL).ifBlank { QwenDefaults.MODEL },
            qwenFallbackModel = (prefs[KEY_QWEN_FALLBACK] ?: QwenDefaults.FALLBACK_MODEL)
                .ifBlank { QwenDefaults.FALLBACK_MODEL },
            darkTheme = when (theme) {
                "light" -> false
                "dark" -> true
                else -> null
            },
        )
    }

    suspend fun save(settings: RenderSettings) {
        context.dataStore.edit { prefs ->
            prefs[KEY_CLIP_COUNT] = settings.clipCount.coerceIn(1, 4)
            prefs[KEY_SLSR] = settings.slsrEnabled
            prefs[KEY_CAS] = settings.casEnabled
            prefs[KEY_UNSHARP] = settings.unsharpEnabled
            prefs[KEY_QWEN_MODEL] = settings.qwenModel.ifBlank { QwenDefaults.MODEL }
            prefs[KEY_QWEN_FALLBACK] = settings.qwenFallbackModel.ifBlank { QwenDefaults.FALLBACK_MODEL }
            prefs[KEY_THEME] = when (settings.darkTheme) {
                true -> "dark"
                false -> "light"
                null -> "system"
            }
        }
    }

    fun themeFlow() = context.dataStore.data.map { prefs ->
        when (prefs[KEY_THEME] ?: "system") {
            "dark" -> true
            "light" -> false
            else -> null
        }
    }

    // ---------- Groq keys ----------

    private fun encryptedPrefs(): SharedPreferences? = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context, ENC_PREFS, masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        null
    }

    private fun plainPrefs(): SharedPreferences =
        context.getSharedPreferences(PLAIN_PREFS, Context.MODE_PRIVATE)

    /** Teks mentah key (dipisah koma/baris baru), apa adanya dari penyimpanan. */
    fun loadGroqKeysRaw(): String {
        val enc = encryptedPrefs()?.getString(ENC_KEY_GROQ, null)
        if (!enc.isNullOrBlank()) return enc
        return plainPrefs().getString(ENC_KEY_GROQ, "").orEmpty()
    }

    fun saveGroqKeysRaw(raw: String) {
        val enc = encryptedPrefs()
        if (enc != null) {
            enc.edit().putString(ENC_KEY_GROQ, raw).apply()
            plainPrefs().edit().remove(ENC_KEY_GROQ).apply()
        } else {
            plainPrefs().edit().putString(ENC_KEY_GROQ, raw).apply()
        }
    }

    fun loadGroqKeys(): List<String> = GroqClient.parseKeys(loadGroqKeysRaw())

    /** Satu baris per key, masing-masing dimask (…abcd) untuk ditampilkan. */
    fun maskedKeysSummary(): String =
        loadGroqKeys().mapIndexed { i, k -> "Key ${i + 1} (${GroqClient.maskKey(k)})" }
            .joinToString("\n")
}
