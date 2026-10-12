package com.bennybar.mypapers.gmail

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class GmailConfig(
    /** The Google account Gmail was connected with (the signed-in one); "" = not connected. */
    val email: String,
    /** How far back the first scan looks. */
    val monthsBack: Int,
    /** Look for new papers once a day after the first scan. */
    val dailySweep: Boolean,
) {
    val isConnected get() = email.isNotBlank()
}

/** Which Gmail is connected, the scan options, and scan progress (this phone only). */
class GmailSettings(context: Context) {
    private val prefs = context.getSharedPreferences("gmail", Context.MODE_PRIVATE)

    private val _config = MutableStateFlow(read())
    val config: StateFlow<GmailConfig> = _config


    /** When the last completed scan started (epoch ms); 0 = never — the next scan is the first, full one. */
    var lastScanAt: Long
        get() = prefs.getLong("lastScanAt", 0)
        set(value) = prefs.edit().putLong("lastScanAt", value).apply()

    /** How many months back the completed scans have covered; a longer window scans the rest once. */
    var coveredMonths: Int
        get() = prefs.getInt("coveredMonths", 0)
        set(value) = prefs.edit().putInt("coveredMonths", value).apply()

    init {
        // Scans completed before coverage was recorded covered the window that was set.
        if (lastScanAt > 0 && !prefs.contains("coveredMonths")) coveredMonths = _config.value.monthsBack
    }

    fun save(config: GmailConfig) {
        val accountChanged = config.email != _config.value.email
        prefs.edit()
            .putString("email", config.email)
            .putInt("monthsBack", config.monthsBack)
            .putBoolean("dailySweep", config.dailySweep)
            .also { if (accountChanged) it.putLong("lastScanAt", 0).putInt("coveredMonths", 0) } // another mailbox: full first scan again
            .apply()
        _config.value = read()
    }

    private fun read() = GmailConfig(
        email = prefs.getString("email", "")!!,
        monthsBack = prefs.getInt("monthsBack", 6),
        dailySweep = prefs.getBoolean("dailySweep", true),
    )
}
