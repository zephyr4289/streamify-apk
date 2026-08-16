package com.streamify.app.data.remote

import android.content.Context
import com.streamify.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

sealed interface UpdateState {
    object Idle : UpdateState
    object Checking : UpdateState
    object NotAvailable : UpdateState
    data class UpdateAvailable(
        val version: String,
        val changelog: String,
        val apkUrl: String
    ) : UpdateState
    data class Error(val message: String) : UpdateState
}

object StreamifyUpdateManager {
    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

    private const val PREFS_NAME = "streamify_updates"
    private const val KEY_DISMISSED_VERSION = "dismissed_version"

    suspend fun checkForUpdates(context: Context, currentVersionName: String = BuildConfig.VERSION_NAME) {
        _updateState.value = UpdateState.Checking
        withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                val url = URL("https://api.github.com/repos/zephyr4289/streamify-apk/releases/latest")
                connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
                connection.setRequestProperty("User-Agent", "Streamify-Android-App")
                connection.connectTimeout = 6000
                connection.readTimeout = 6000

                val responseCode = connection.responseCode
                if (responseCode == 200) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(response)
                    val remoteTag = json.optString("tag_name", "")
                    val remoteVersion = remoteTag.removePrefix("v")
                    val changelog = json.optString("body", "Performance improvements and bug fixes.")

                    // Check if the user previously dismissed this exact version
                    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    val dismissedVersion = prefs.getString(KEY_DISMISSED_VERSION, null)

                    // Find the .apk asset URL
                    val assets = json.optJSONArray("assets")
                    var apkUrl = ""
                    if (assets != null) {
                        for (i in 0 until assets.length()) {
                            val asset = assets.getJSONObject(i)
                            val name = asset.optString("name", "")
                            if (name.endsWith(".apk", ignoreCase = true)) {
                                apkUrl = asset.optString("browser_download_url", "")
                                break
                            }
                        }
                    }

                    if (apkUrl.isNotEmpty() && isNewerVersion(remoteVersion, currentVersionName)) {
                        if (dismissedVersion == remoteVersion) {
                            _updateState.value = UpdateState.NotAvailable
                        } else {
                            _updateState.value = UpdateState.UpdateAvailable(
                                version = remoteVersion,
                                changelog = changelog,
                                apkUrl = apkUrl
                            )
                        }
                    } else {
                        _updateState.value = UpdateState.NotAvailable
                    }
                } else {
                    _updateState.value = UpdateState.Error("HTTP $responseCode from GitHub Releases")
                }
            } catch (e: Exception) {
                _updateState.value = UpdateState.Error(e.message ?: "Network error while checking updates")
            } finally {
                connection?.disconnect()
            }
        }
    }

    fun dismissUpdate(context: Context, version: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_DISMISSED_VERSION, version).apply()
        _updateState.value = UpdateState.NotAvailable
    }

    // Robust Segment-by-Segment Semantic Version Comparison (e.g. "1.104.0" vs "1.4.2")
    private fun isNewerVersion(remote: String, current: String): Boolean {
        val remoteParts = remote.split(".").map { it.filter { c -> c.isDigit() }.toIntOrNull() ?: 0 }
        val currentParts = current.split(".").map { it.filter { c -> c.isDigit() }.toIntOrNull() ?: 0 }
        val maxLen = maxOf(remoteParts.size, currentParts.size)

        for (i in 0 until maxLen) {
            val r = remoteParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (r > c) return true
            if (r < c) return false
        }
        return false
    }
}
