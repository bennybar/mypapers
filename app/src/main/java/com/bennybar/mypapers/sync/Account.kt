package com.bennybar.mypapers.sync

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.bennybar.mypapers.BuildConfig
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import java.io.IOException

class SignInException(message: String) : IOException(message)

/** Sign in with Google: which account's Drive the papers live in. */
class Account {
    /** Shows Google's account picker; returns the account's email. Needs an Activity context. */
    suspend fun signIn(activity: Context): String {
        val option = GetSignInWithGoogleOption.Builder(BuildConfig.GOOGLE_WEB_CLIENT_ID).build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        val credential = CredentialManager.create(activity).getCredential(activity, request).credential
        if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            return GoogleIdTokenCredential.createFrom(credential.data).id
        }
        throw SignInException("Unexpected credential type ${credential.type}")
    }
}
