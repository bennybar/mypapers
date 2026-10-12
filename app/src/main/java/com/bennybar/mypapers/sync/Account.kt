package com.bennybar.mypapers.sync

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import com.bennybar.mypapers.BuildConfig
import com.bennybar.mypapers.data.ServerSettings
import com.bennybar.mypapers.data.Session
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

class SignInException(message: String) : IOException(message)

/**
 * Sign in with Google, traded at the server's auth service (mypapers-auth) for
 * a token to the account's own CouchDB database.
 */
class Account {
    private val http = OkHttpClient()

    /** Shows Google's account picker. Needs an Activity context. */
    suspend fun signIn(activity: Context): Session {
        val option = GetSignInWithGoogleOption.Builder(BuildConfig.GOOGLE_WEB_CLIENT_ID).build()
        return exchange(googleIdToken(activity, GetCredentialRequest.Builder().addCredentialOption(option).build()))
    }

    /**
     * A fresh token for the account already signed in, without UI (Google may
     * still briefly show "Signing in as…"). Null if Google won't do it silently.
     */
    suspend fun renew(activity: Context): Session? {
        val option = GetGoogleIdOption.Builder()
            .setServerClientId(BuildConfig.GOOGLE_WEB_CLIENT_ID)
            .setFilterByAuthorizedAccounts(true)
            .setAutoSelectEnabled(true)
            .build()
        return try {
            exchange(googleIdToken(activity, GetCredentialRequest.Builder().addCredentialOption(option).build()))
        } catch (_: GetCredentialException) {
            null
        }
    }

    private suspend fun googleIdToken(activity: Context, request: GetCredentialRequest): String {
        val credential = CredentialManager.create(activity).getCredential(activity, request).credential
        if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            return GoogleIdTokenCredential.createFrom(credential.data).idToken
        }
        throw SignInException("Unexpected credential type ${credential.type}")
    }

    private suspend fun exchange(idToken: String): Session = withContext(Dispatchers.IO) {
        val body = buildJsonObject { put("idToken", idToken) }.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url("${ServerSettings.SERVER_URL}/mypapers-api/session").post(body).build()
        http.newCall(request).execute().use { r ->
            val text = r.body.string()
            when {
                r.code == 403 -> throw SignInException("This Google account isn't allowed on this server.")
                !r.isSuccessful -> throw SignInException("Sign-in failed (${r.code}): ${text.trim().take(200)}")
            }
            val json = Json.parseToJsonElement(text).jsonObject
            Session(
                email = json["email"]!!.jsonPrimitive.content,
                db = json["db"]!!.jsonPrimitive.content,
                token = json["token"]!!.jsonPrimitive.content,
                expiresAt = json["expiresAt"]!!.jsonPrimitive.long,
            )
        }
    }
}
