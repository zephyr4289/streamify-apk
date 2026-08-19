package com.streamify.app.data.remote

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.MessageDigest
import java.security.SecureRandom

class SpotifyAuthManager(context: Context) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val securePrefs = EncryptedSharedPreferences.create(
        context,
        "spotify_secure_tokens",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun generateCodeVerifier(): String {
        val bytes = ByteArray(64)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    fun generateCodeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    fun buildAuthorizationUri(clientId: String, redirectUri: String, codeChallenge: String): Uri {
        return Uri.parse("https://accounts.spotify.com/authorize").buildUpon()
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", redirectUri)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", codeChallenge)
            .appendQueryParameter("scope", "user-library-read playlist-read-private playlist-read-collaborative")
            .build()
    }

    fun saveRefreshToken(token: String) = securePrefs.edit().putString("refresh_token", token).apply()

    fun getRefreshToken(): String? = securePrefs.getString("refresh_token", null)

    fun saveAccessToken(token: String, expiresInSec: Long = 3600) {
        val expiryMs = System.currentTimeMillis() + (expiresInSec * 1000)
        securePrefs.edit()
            .putString("access_token", token)
            .putLong("token_expiry", expiryMs)
            .apply()
    }

    fun getAccessToken(): String? {
        val expiryMs = securePrefs.getLong("token_expiry", 0L)
        if (System.currentTimeMillis() >= expiryMs) return null
        return securePrefs.getString("access_token", null)
    }

    fun clearTokens() {
        securePrefs.edit().clear().apply()
    }

    fun isConnected(): Boolean = !getRefreshToken().isNullOrEmpty()
}
