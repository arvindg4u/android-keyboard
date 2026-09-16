package org.futo.voiceinput.shared.gemini

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class GeminiSettings(context: Context) {

    companion object {
        private const val PREFS_NAME = "gemini_voice_settings"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_SMART_MODE = "smart_mode"

        fun normalizeKey(raw: String): String = raw.trim()

        fun hasKey(raw: String): Boolean = normalizeKey(raw).isNotEmpty()
    }

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context.applicationContext,
        PREFS_NAME,
        MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    var apiKey: String
        get() = normalizeKey(prefs.getString(KEY_API_KEY, "").orEmpty())
        set(value) {
            prefs.edit().putString(KEY_API_KEY, normalizeKey(value)).apply()
        }

    var smartMode: Boolean
        get() = prefs.getBoolean(KEY_SMART_MODE, false)
        set(value) {
            prefs.edit().putBoolean(KEY_SMART_MODE, value).apply()
        }

    val baseUrl: String = LiveProtocol.DEFAULT_BASE_URL

    fun hasKey(): Boolean = hasKey(apiKey)
}
