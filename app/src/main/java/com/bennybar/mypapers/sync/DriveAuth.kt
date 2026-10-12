package com.bennybar.mypapers.sync

import android.accounts.Account
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.tasks.await

/**
 * Access to the app's own hidden Drive folder for the signed-in account (drive.appdata: it sees
 * nothing else in Drive). Allowed once on Google's consent screen; after that tokens come back
 * silently, also in background syncs.
 */
object DriveAuth {
    const val SCOPE = "https://www.googleapis.com/auth/drive.appdata"

    sealed interface Result {
        data class Token(val accessToken: String) : Result
        /** The user has to allow it (or allow it again): start this from an activity. */
        data class NeedsConsent(val intent: PendingIntent) : Result
    }

    suspend fun authorize(context: Context, email: String): Result {
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(SCOPE)))
            .setAccount(Account(email, "com.google"))
            .build()
        val result = Identity.getAuthorizationClient(context).authorize(request).await()
        return if (result.hasResolution()) Result.NeedsConsent(result.pendingIntent!!)
        else Result.Token(result.accessToken ?: throw DriveAuthException())
    }

    /** A token without UI, for syncing; [DriveAuthException] if the user has to allow access again. */
    suspend fun token(context: Context, email: String): String =
        (authorize(context, email) as? Result.Token)?.accessToken ?: throw DriveAuthException()

    /** After the consent screen. */
    fun tokenFrom(context: Context, data: Intent?): String? =
        runCatching { Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data).accessToken }.getOrNull()
}
