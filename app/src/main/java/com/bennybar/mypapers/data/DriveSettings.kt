package com.bennybar.mypapers.data

import android.content.Context
import com.bennybar.mypapers.sync.PageTokenStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The Google account the papers sync to (its own Drive) and where its change feed was last read. */
class DriveSettings(context: Context) : PageTokenStore {
    private val prefs = context.getSharedPreferences("drive", Context.MODE_PRIVATE)

    private val _email = MutableStateFlow(prefs.getString("email", null))
    /** Signed in as; null = signed out. */
    val email: StateFlow<String?> = _email

    fun saveEmail(email: String?) {
        prefs.edit().putString("email", email).apply()
        _email.value = email
    }

    /** Drive's change-feed position; null = read everything next time. */
    override var pageToken: String?
        get() = prefs.getString("pageToken", null)
        set(value) = prefs.edit().putString("pageToken", value).apply()

    private val _lastSync = MutableStateFlow(prefs.getLong("lastSyncAt", 0))
    val lastSync: StateFlow<Long> = _lastSync

    override var lastSyncAt: Long
        get() = _lastSync.value
        set(value) {
            prefs.edit().putLong("lastSyncAt", value).apply()
            _lastSync.value = value
        }

    /** The account this phone's papers were last synced with. */
    var syncedEmail: String?
        get() = prefs.getString("syncedEmail", null)
        set(value) = prefs.edit().putString("syncedEmail", value).apply()
}
