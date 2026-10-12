package com.bennybar.mypapers.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.bennybar.mypapers.sync.SeqStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** A signed-in account: its own CouchDB database and the token to reach it. */
data class Session(val email: String, val db: String, val token: String, val expiresAt: Long) {
    fun isExpired(now: Long = System.currentTimeMillis()) = now >= expiresAt
}

/**
 * The server address, the signed-in session and the sync position. The token
 * is encrypted with a key that never leaves the Android Keystore.
 */
class ServerSettings(context: Context) : SeqStore {
    private val prefs = context.getSharedPreferences("server", Context.MODE_PRIVATE)

    private val _url = MutableStateFlow(prefs.getString("url", DEFAULT_URL)!!)
    val url: StateFlow<String> = _url

    private val _session = MutableStateFlow(readSession())
    val session: StateFlow<Session?> = _session

    override var lastSeq: String
        get() = prefs.getString("lastSeq", "0")!!
        set(value) = prefs.edit().putString("lastSeq", value).apply()

    /** The database this phone's documents were last synced with. */
    var syncedDb: String?
        get() = prefs.getString("syncedDb", null)
        set(value) = prefs.edit().putString("syncedDb", value).apply()

    fun saveUrl(url: String) {
        prefs.edit().putString("url", url.trim().trimEnd('/')).apply()
        _url.value = prefs.getString("url", DEFAULT_URL)!!
    }

    fun saveSession(session: Session?) {
        prefs.edit().apply {
            if (session == null) {
                remove("email"); remove("db"); remove("token"); remove("expiresAt")
            } else {
                putString("email", session.email)
                putString("db", session.db)
                putString("token", encrypt(session.token))
                putLong("expiresAt", session.expiresAt)
            }
        }.apply()
        _session.value = session
    }

    private fun readSession(): Session? {
        val email = prefs.getString("email", null) ?: return null
        val db = prefs.getString("db", null) ?: return null
        val token = prefs.getString("token", null)?.let(::decrypt) ?: return null
        return Session(email, db, token, prefs.getLong("expiresAt", 0))
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
        }.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.encodeToString(cipher.iv + cipher.doFinal(plain.toByteArray()), Base64.NO_WRAP)
    }

    /** Null when it can't be read (e.g. restored onto another phone, whose Keystore lacks the key). */
    private fun decrypt(stored: String): String? = runCatching {
        val bytes = Base64.decode(stored, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12))
        String(cipher.doFinal(bytes, 12, bytes.size - 12))
    }.getOrNull()

    companion object {
        const val DEFAULT_URL = "https://couchdb.ibarak.me"
        private const val KEY_ALIAS = "mypapers_server"
    }
}
