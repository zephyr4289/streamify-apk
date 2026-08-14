package com.streamify.app.data.remote

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.streamify.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object AuthManager {

    suspend fun signInWithGoogle(context: Context): Result<UserProfile> = withContext(Dispatchers.Main) {
        try {
            val credentialManager = CredentialManager.create(context)

            val googleIdOption = GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(false)
                .setServerClientId(BuildConfig.GOOGLE_WEB_CLIENT_ID)
                .setAutoSelectEnabled(false)
                .build()

            val request = GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build()

            val result = credentialManager.getCredential(context = context, request = request)
            val credential = result.credential

            val googleIdToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
            if (googleIdToken.isBlank()) {
                return@withContext Result.failure(Exception("Google ID Token was empty."))
            }

            // Authenticate with Supabase
            return@withContext SupabaseClient.signInWithGoogleIdToken(googleIdToken)
        } catch (e: GetCredentialCancellationException) {
            return@withContext Result.failure(Exception("Sign-in cancelled."))
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext Result.failure(e)
        }
    }
}
