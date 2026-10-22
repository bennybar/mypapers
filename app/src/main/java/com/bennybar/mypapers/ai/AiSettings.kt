package com.bennybar.mypapers.ai

import android.content.Context
import com.bennybar.mypapers.data.KeystoreCipher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class AiConfig(
    val apiKey: String,
    val model: String,
    /** Read each new paper in the background as it comes in (off: only when asked). */
    val autoRead: Boolean = false,
    /** An OpenAI-compatible proxy (LiteLLM…), e.g. https://litellm.example.com/v1; blank: OpenAI itself. */
    val baseUrl: String = "",
) {
    val isSet get() = apiKey.isNotBlank()

    /** Where requests go: the proxy, or OpenAI. */
    val endpoint get() = baseUrl.trim().trimEnd('/').ifBlank { OPENAI }

    /** Who answers, for messages ("OpenAI", or the proxy's host). */
    val provider get() = if (baseUrl.isBlank()) "OpenAI" else runCatching { java.net.URI(endpoint).host }.getOrNull() ?: endpoint

    companion object {
        const val OPENAI = "https://api.openai.com/v1"
    }
}

/**
 * The user's own OpenAI key (or a proxy's) (kept on this phone only, encrypted with the
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
            .putBoolean("autoRead", config.autoRead)
            .putString("baseUrl", config.baseUrl.trim())
            .apply()
        _config.value = read()
    }

    private fun read() = AiConfig(
        apiKey = prefs.getString("apiKey", null)?.let(cipher::decrypt).orEmpty(),
        model = prefs.getString("model", DEFAULT_MODEL)!!,
        autoRead = prefs.getBoolean("autoRead", false),
        baseUrl = prefs.getString("baseUrl", "").orEmpty(),
    )

    companion object {
        const val DEFAULT_MODEL = "gpt-5.4-mini"
    }
}
