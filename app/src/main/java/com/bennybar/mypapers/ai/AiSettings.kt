package com.bennybar.mypapers.ai

import android.content.Context
import com.bennybar.mypapers.data.KeystoreCipher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class AiConfig(val apiKey: String, val model: String) {
    val isSet get() = apiKey.isNotBlank()
}

/**
 * The user's own OpenAI key (kept on this phone only, encrypted with the
 * Android Keystore — never synced) and the model to read documents with.
 */
class AiSettings(context: Context) {
    private val prefs = context.getSharedPreferences("ai", Context.MODE_PRIVATE)
    private val cipher = KeystoreCipher("mypapers_ai")

    private val _config = MutableStateFlow(read())
    val config: StateFlow<AiConfig> = _config

    fun save(config: AiConfig) {
        prefs.edit()
            .putString("apiKey", cipher.encrypt(config.apiKey.trim()))
            .putString("model", config.model.trim().ifBlank { DEFAULT_MODEL })
            .apply()
        _config.value = read()
    }

    private fun read() = AiConfig(
        apiKey = prefs.getString("apiKey", null)?.let(cipher::decrypt).orEmpty(),
        model = prefs.getString("model", DEFAULT_MODEL)!!,
    )

    companion object {
        const val DEFAULT_MODEL = "gpt-5.4-mini"
    }
}
