package com.bennybar.mypapers.data

import android.content.Context
import com.bennybar.mypapers.sync.SeqStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A signed-in account: its own CouchDB database and the token to reach it. */
data class Session(val email: String, val db: String, val token: String, val expiresAt: Long) {
    fun isExpired(now: Long = System.currentTimeMillis()) = now >= expiresAt
}

/**
 * The signed-in session and the sync position. The token is encrypted with a
 * key that never leaves the Android Keystore.
 */
class ServerSettings(context: Context) : SeqStore {
    private val prefs = context.getSharedPreferences("server", Context.MODE_PRIVATE)
    private val cipher = KeystoreCipher("mypapers_server")

    private val _session = MutableStateFlow(readSession())
    val session: StateFlow<Session?> = _session

    override var lastSeq: String
        get() = prefs.getString("lastSeq", "0")!!
        set(value) = prefs.edit().putString("lastSeq", value).apply()

    /** The database this phone's documents were last synced with. */
    var syncedDb: String?
        get() = prefs.getString("syncedDb", null)
        set(value) = prefs.edit().putString("syncedDb", value).apply()

    fun saveSession(session: Session?) {
        prefs.edit().apply {
            if (session == null) {
                remove("email"); remove("db"); remove("token"); remove("expiresAt")
            } else {
                putString("email", session.email)
                putString("db", session.db)
                putString("token", cipher.encrypt(session.token))
                putLong("expiresAt", session.expiresAt)
            }
        }.apply()
        _session.value = session
    }

    private fun readSession(): Session? {
        val email = prefs.getString("email", null) ?: return null
        val db = prefs.getString("db", null) ?: return null
        val token = prefs.getString("token", null)?.let(cipher::decrypt) ?: return null
        return Session(email, db, token, prefs.getLong("expiresAt", 0))
    }

    companion object {
        /** The MyPapers server (CouchDB, with the sign-in service at /mypapers-api/). */
        const val SERVER_URL = "https://couchdb.ibarak.me"
    }
}
