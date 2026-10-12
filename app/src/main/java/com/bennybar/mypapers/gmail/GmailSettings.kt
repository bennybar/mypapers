package com.bennybar.mypapers.gmail

import android.content.Context
import com.bennybar.mypapers.data.KeystoreCipher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class GmailConfig(
    val address: String,
    /** A Gmail app password (Google account → Security → App passwords; needs 2-Step Verification). */
    val appPassword: String,
    /** How far back the first scan looks. */
    val monthsBack: Int,
    /** Look for new papers once a day after the first scan. */
    val dailySweep: Boolean,
) {
    val isSet get() = address.isNotBlank() && appPassword.isNotBlank()
}

/** The Gmail login (app password encrypted with the Android Keystore, this phone only) and scan progress. */
class GmailSettings(context: Context) {
    private val prefs = context.getSharedPreferences("gmail", Context.MODE_PRIVATE)
    private val cipher = KeystoreCipher("mypapers_gmail")

    private val _config = MutableStateFlow(read())
    val config: StateFlow<GmailConfig> = _config

    /** When the last scan finished (epoch ms); 0 = never — the next scan is the first, full one. */
    var lastScanAt: Long
        get() = prefs.getLong("lastScanAt", 0)
        set(value) = prefs.edit().putLong("lastScanAt", value).apply()

    fun save(config: GmailConfig) {
        val accountChanged = config.address.trim() != _config.value.address
        prefs.edit()
            .putString("address", config.address.trim())
            .putString("appPassword", cipher.encrypt(config.appPassword.replace(" ", "")))
            .putInt("monthsBack", config.monthsBack)
            .putBoolean("dailySweep", config.dailySweep)
            .apply {
                // Another mailbox: its first scan is a full one again.
                if (accountChanged) putLong("lastScanAt", 0)
            }
            .apply()
        _config.value = read()
    }

    private fun read() = GmailConfig(
        address = prefs.getString("address", "")!!,
        appPassword = prefs.getString("appPassword", null)?.let(cipher::decrypt).orEmpty(),
        monthsBack = prefs.getInt("monthsBack", 6),
        dailySweep = prefs.getBoolean("dailySweep", true),
    )
}
