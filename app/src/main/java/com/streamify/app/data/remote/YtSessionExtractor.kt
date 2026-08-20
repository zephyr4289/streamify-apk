package com.streamify.app.data.remote

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import com.streamify.app.data.NativeBridge
import java.util.concurrent.atomic.AtomicBoolean

class YtSessionExtractor(private val context: Context) {
    private val isIntercepted = AtomicBoolean(false)

    @SuppressLint("SetJavaScriptEnabled")
    fun launchAuthSession(
        webView: WebView,
        onSuccess: (authHeader: String, rawCookies: String) -> Unit,
        onError: (String) -> Unit
    ) {
        isIntercepted.set(false)
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            userAgentString = "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.6367.82 Mobile Safari/537.36"
        }

        fun checkAndHarvestCookies() {
            if (isIntercepted.get()) return

            // Query all candidate domains that carry Google/YouTube authorization state
            val ytMusicCookies = cookieManager.getCookie("https://music.youtube.com").orEmpty()
            val ytBaseCookies = cookieManager.getCookie("https://www.youtube.com").orEmpty()
            val googleCookies = cookieManager.getCookie("https://accounts.google.com").orEmpty()
            val aggregatedCookies = "$ytMusicCookies; $ytBaseCookies; $googleCookies"

            val sapisid = extractCookieValue(aggregatedCookies, "SAPISID")
                ?: extractCookieValue(aggregatedCookies, "__Secure-3PAPISID")

            if (!sapisid.isNullOrBlank()) {
                val authHeader = NativeBridge.getSapisidHash(sapisid)
                if (authHeader != null && isIntercepted.compareAndSet(false, true)) {
                    // Abort loading web resources instantly to avoid rendering YouTube Music UI
                    try {
                        webView.stopLoading()
                    } catch (e: Exception) {
                        // Ignore
                    }
                    onSuccess(authHeader, aggregatedCookies)
                }
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                checkAndHarvestCookies()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                checkAndHarvestCookies()
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                checkAndHarvestCookies()
            }
        }

        webView.loadUrl("https://accounts.google.com/ServiceLogin?service=youtube&continue=https%3A%2F%2Fmusic.youtube.com")
    }

    /**
     * Correctly handles cookie values containing '=' (Base64 padding, hashes, etc.)
     */
    private fun extractCookieValue(cookieHeader: String, key: String): String? {
        val tokens = cookieHeader.split(";")
        for (token in tokens) {
            val trimmed = token.trim()
            val separatorIndex = trimmed.indexOf('=')
            if (separatorIndex != -1) {
                val cookieKey = trimmed.substring(0, separatorIndex).trim()
                if (cookieKey.equals(key, ignoreCase = true)) {
                    return trimmed.substring(separatorIndex + 1).trim()
                }
            }
        }
        return null
    }
}
