package com.bennybar.mypapers.gmail

import android.accounts.Account
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.tasks.await

/**
 * Read-only Gmail access for the signed-in Google account. Asked once (Google's
 * consent screen); after that a fresh access token comes back silently, also
 * from background jobs.
 */
object GmailAuth {
    const val SCOPE = "https://www.googleapis.com/auth/gmail.readonly"

    sealed interface Result {
        data class Token(val accessToken: String) : Result
        /** The user has to approve (or approve again): start this from an activity. */
        data class NeedsConsent(val intent: PendingIntent) : Result
    }

    suspend fun authorize(context: Context, email: String): Result {
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(SCOPE)))
            .setAccount(Account(email, "com.google"))
            .build()
        val result = Identity.getAuthorizationClient(context).authorize(request).await()
        return if (result.hasResolution()) Result.NeedsConsent(result.pendingIntent!!)
        else Result.Token(result.accessToken ?: throw GmailException("Google gave no Gmail access"))
    }

    /** After the consent screen. */
    fun tokenFrom(context: Context, data: Intent?): String? =
        runCatching { Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data).accessToken }.getOrNull()
}
