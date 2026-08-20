package com.streamify.app.data.remote

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.streamify.app.data.network.NetworkEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom

class SpotifyAuthManager(private val context: Context) {

    companion object {
        const val DEFAULT_SPOTIFY_CLIENT_ID = SpotifyConfig.CLIENT_ID
        const val DEFAULT_REDIRECT_URI = SpotifyConfig.REDIRECT_URI

        private val _spotifyConnectedState = MutableStateFlow(false)
        val isSpotifyConnectedFlow: StateFlow<Boolean> = _spotifyConnectedState.asStateFlow()

        private val _ytConnectedState = MutableStateFlow(false)
        val isYtConnectedFlow: StateFlow<Boolean> = _ytConnectedState.asStateFlow()
    }

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val securePrefs = try {
        EncryptedSharedPreferences.create(
            context,
            "spotify_secure_tokens",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        context.getSharedPreferences("spotify_secure_tokens_fallback", Context.MODE_PRIVATE)
    }

    init {
        _spotifyConnectedState.value = isConnected()
        _ytConnectedState.value = isYtConnected()
    }

    fun generateCodeVerifier(): String {
        val bytes = ByteArray(64)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    fun generateCodeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    fun saveCodeVerifier(verifier: String) {
        securePrefs.edit().putString("code_verifier", verifier).apply()
    }

    fun getCodeVerifier(): String? = securePrefs.getString("code_verifier", null)

    fun buildAuthUri(
        clientId: String = DEFAULT_SPOTIFY_CLIENT_ID,
        redirectUri: String = DEFAULT_REDIRECT_URI,
        codeChallenge: String
    ): Uri = buildAuthorizationUri(clientId, redirectUri, codeChallenge)

    fun buildAuthorizationUri(
        clientId: String = DEFAULT_SPOTIFY_CLIENT_ID,
        redirectUri: String = DEFAULT_REDIRECT_URI,
        codeChallenge: String
    ): Uri {
        return Uri.parse("https://accounts.spotify.com/authorize").buildUpon()
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", redirectUri)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", codeChallenge)
            .appendQueryParameter("scope", "user-library-read playlist-read-private playlist-read-collaborative user-top-read")
            .build()
    }

    suspend fun exchangeCodeForTokens(
        code: String,
        clientId: String = DEFAULT_SPOTIFY_CLIENT_ID,
        redirectUri: String = DEFAULT_REDIRECT_URI
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val verifier = getCodeVerifier() ?: ""
            val formBody = FormBody.Builder()
                .add("client_id", clientId)
                .add("grant_type", "authorization_code")
                .add("code", code)
                .add("redirect_uri", redirectUri)
                .add("code_verifier", verifier)
                .build()

            val request = Request.Builder()
                .url("https://accounts.spotify.com/api/token")
                .post(formBody)
                .build()

            val response = NetworkEngine.client.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("Spotify token exchange failed HTTP ${response.code}"))
            }

            val json = JSONObject(response.body?.string() ?: "{}")
            val accessToken = json.optString("access_token")
            val refreshToken = json.optString("refresh_token")
            val expiresIn = json.optLong("expires_in", 3600L)

            if (accessToken.isNotBlank()) {
                saveAccessToken(accessToken, expiresIn)
            }
            if (refreshToken.isNotBlank()) {
                saveRefreshToken(refreshToken)
            }

            _spotifyConnectedState.value = true
            Result.success(true)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun refreshAccessToken(clientId: String = DEFAULT_SPOTIFY_CLIENT_ID): Result<String> = withContext(Dispatchers.IO) {
        val refreshToken = getRefreshToken() ?: return@withContext Result.failure(Exception("No refresh token stored"))
        try {
            val formBody = FormBody.Builder()
                .add("client_id", clientId)
                .add("grant_type", "refresh_token")
                .add("refresh_token", refreshToken)
                .build()

            val request = Request.Builder()
                .url("https://accounts.spotify.com/api/token")
                .post(formBody)
                .build()

            val response = NetworkEngine.client.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("Spotify token refresh failed HTTP ${response.code}"))
            }

            val json = JSONObject(response.body?.string() ?: "{}")
            val newAccessToken = json.optString("access_token")
            val expiresIn = json.optLong("expires_in", 3600L)
            val newRefreshToken = json.optString("refresh_token", "")

            if (newAccessToken.isNotBlank()) {
                saveAccessToken(newAccessToken, expiresIn)
            }
            if (newRefreshToken.isNotBlank()) {
                saveRefreshToken(newRefreshToken)
            }

            _spotifyConnectedState.value = true
            Result.success(newAccessToken)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun saveRefreshToken(token: String) {
        securePrefs.edit().putString("refresh_token", token).apply()
        _spotifyConnectedState.value = true
    }

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
        _spotifyConnectedState.value = false
        _ytConnectedState.value = false
    }

    fun isConnected(): Boolean = !getRefreshToken().isNullOrEmpty()

    // --- YouTube Music Session Management ---

    fun saveYtSession(authHeader: String, rawCookies: String) {
        securePrefs.edit()
            .putString("yt_auth_header", authHeader)
            .putString("yt_raw_cookies", rawCookies)
            .putLong("yt_session_timestamp", System.currentTimeMillis())
            .apply()
        _ytConnectedState.value = true
    }

    fun getYtAuthHeader(): String? = securePrefs.getString("yt_auth_header", null)

    fun getYtRawCookies(): String? = securePrefs.getString("yt_raw_cookies", null)

    fun isYtConnected(): Boolean = !getYtAuthHeader().isNullOrEmpty()

    fun clearYtSession() {
        securePrefs.edit()
            .remove("yt_auth_header")
            .remove("yt_raw_cookies")
            .remove("yt_session_timestamp")
            .apply()
        _ytConnectedState.value = false
    }
}

