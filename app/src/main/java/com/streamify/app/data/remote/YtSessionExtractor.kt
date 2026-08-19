package com.streamify.app.data.remote

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import com.streamify.app.data.NativeBridge

class YtSessionExtractor(private val context: Context) {

    @SuppressLint("SetJavaScriptEnabled")
    fun launchAuthSession(
        webView: WebView,
        onSuccess: (authHeader: String, rawCookies: String) -> Unit,
        onError: (message: String) -> Unit
    ) {
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            userAgentString = "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.6367.82 Mobile Safari/537.36"
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                val cookies = cookieManager.getCookie("https://music.youtube.com") ?: ""
                val sapisid = extractCookie(cookies, "SAPISID") ?: extractCookie(cookies, "__Secure-3PAPISID")
                if (!sapisid.isNullOrEmpty()) {
                    val authHeader = NativeBridge.getSapisidHash(sapisid)
                    if (authHeader != null) {
                        onSuccess(authHeader, cookies)
                    } else {
                        onError("Hash generation failed")
                    }
                }
            }
        }

        webView.loadUrl("https://accounts.google.com/ServiceLogin?service=youtube&continue=https%3A%2F%2Fmusic.youtube.com")
    }

    private fun extractCookie(cookieStr: String, key: String): String? =
        cookieStr.split(";")
            .map { it.trim().split("=") }
            .firstOrNull { it.size == 2 && it[0] == key }
            ?.get(1)
}
