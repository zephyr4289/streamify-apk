package com.streamify.app.util.newpipe

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.annotation.MainThread
import com.streamify.app.util.SLog
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Headers.Companion.toHeaders
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import java.time.Instant
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Hidden WebView that loads YouTube's BotGuard VM and mints PO tokens.
 * Faithful port of NewPipe's PoTokenWebView, logging through SLog.
 */
class PoTokenWebView private constructor(
    context: Context,
    // to be used exactly once only during initialization!
    private val generatorContinuation: Continuation<PoTokenWebView>,
) {
    private val webView = WebView(context)
    private val poTokenContinuations = mutableMapOf<String, Continuation<String>>()
    private val exceptionHandler = CoroutineExceptionHandler { _, exception ->
        onInitializationError(exception)
    }
    private lateinit var expirationInstant: Instant

    //region Initialization
    init {
        webView.settings.apply {
            javaScriptEnabled = true
            if (Build.VERSION.SDK_INT >= 26) {
                safeBrowsingEnabled = false
            }
            userAgentString = USER_AGENT
            blockNetworkLoads = true // the WebView does not need internet access itself
        }

        // so that we can run async functions and get back the result
        webView.addJavascriptInterface(this, JS_INTERFACE)
    }

    /**
     * Loads the local BotGuard bootstrap HTML and kicks off the challenge flow.
     */
    private fun loadHtmlAndObtainBotguard(context: Context) {
        SLog.d(TAG, "loadHtmlAndObtainBotguard()")

        CoroutineScope(Dispatchers.IO).launch(exceptionHandler) {
            try {
                val html = context.assets.open("po_token.html").bufferedReader().use { it.readText() }
                withContext(Dispatchers.Main) {
                    webView.loadDataWithBaseURL(
                        "https://www.youtube.com",
                        html.replaceFirst(
                            "</script>",
                            // calls downloadAndRunBotguard() when the page has finished loading
                            "\n$JS_INTERFACE.downloadAndRunBotguard()</script>"
                        ),
                        "text/html",
                        "utf-8",
                        null,
                    )
                }
            } catch (e: Exception) {
                onInitializationError(e)
            }
        }
    }

    /** Called by the appended JS snippet once the page has loaded. */
    @JavascriptInterface
    fun downloadAndRunBotguard() {
        SLog.d(TAG, "downloadAndRunBotguard()")

        CoroutineScope(Dispatchers.IO).launch(exceptionHandler) {
            val responseBody = makeBotguardServiceRequest(
                "https://www.youtube.com/api/jnn/v1/Create",
                listOf(REQUEST_KEY)
            )
            val parsedChallengeData = JavascriptUtil.parseChallengeData(responseBody)
            withContext(Dispatchers.Main) {
                webView.evaluateJavascript(
                    """try {
                             data = $parsedChallengeData
                             runBotGuard(data).then(function (result) {
                                 this.webPoSignalOutput = result.webPoSignalOutput
                                 $JS_INTERFACE.onRunBotguardResult(result.botguardResponse)
                             }, function (error) {
                                 $JS_INTERFACE.onJsInitializationError(error + "\n" + error.stack)
                             })
                         } catch (error) {
                             $JS_INTERFACE.onJsInitializationError(error + "\n" + error.stack)
                         }""",
                    null
                )
            }
        }
    }

    @JavascriptInterface
    fun onJsInitializationError(error: String) {
        SLog.e(TAG, "Initialization error from JavaScript: $error")
        onInitializationError(Exception(error))
    }

    @JavascriptInterface
    fun onRunBotguardResult(botguardResponse: String) {
        CoroutineScope(Dispatchers.IO).launch(exceptionHandler) {
            val response = makeBotguardServiceRequest(
                "https://www.youtube.com/api/jnn/v1/GenerateIT",
                listOf(REQUEST_KEY, botguardResponse)
            )
            val (integrityToken, expirationTimeInSeconds) = JavascriptUtil.parseIntegrityTokenData(response)

            // leave 10 minutes of margin just to be sure
            expirationInstant = Instant.now().plusSeconds(expirationTimeInSeconds - 600)

            withContext(Dispatchers.Main) {
                webView.evaluateJavascript("this.integrityToken = $integrityToken") {
                    SLog.d(TAG, "initialization finished, expiration=${expirationTimeInSeconds}s")
                    generatorContinuation.resume(this@PoTokenWebView)
                }
            }
        }
    }
    //endregion

    //region Obtaining poTokens
    suspend fun generatePoToken(identifier: String): String {
        return suspendCancellableCoroutine { continuation ->
            poTokenContinuations[identifier] = continuation
            val u8Identifier = JavascriptUtil.stringToU8(identifier)

            Handler(Looper.getMainLooper()).post {
                webView.evaluateJavascript(
                    """try {
                        identifier = "$identifier"
                        u8Identifier = $u8Identifier
                        poTokenU8 = obtainPoToken(webPoSignalOutput, integrityToken, u8Identifier)
                        poTokenU8String = ""
                        for (i = 0; i < poTokenU8.length; i++) {
                            if (i != 0) poTokenU8String += ","
                            poTokenU8String += poTokenU8[i]
                        }
                        $JS_INTERFACE.onObtainPoTokenResult(identifier, poTokenU8String)
                    } catch (error) {
                        $JS_INTERFACE.onObtainPoTokenError(identifier, error + "\n" + error.stack)
                    }""",
                ) {}
            }
        }
    }

    @JavascriptInterface
    fun onObtainPoTokenError(identifier: String, error: String) {
        SLog.e(TAG, "obtainPoToken error from JavaScript for $identifier: $error")
        poTokenContinuations.remove(identifier)?.resumeWithException(Exception(error))
    }

    @JavascriptInterface
    fun onObtainPoTokenResult(identifier: String, poTokenU8: String) {
        val poToken = try {
            JavascriptUtil.u8ToBase64(poTokenU8)
        } catch (t: Throwable) {
            poTokenContinuations.remove(identifier)?.resumeWithException(t)
            return
        }
        SLog.d(TAG, "Generated poToken for identifier=$identifier")
        poTokenContinuations.remove(identifier)?.resume(poToken)
    }

    fun isExpired(): Boolean {
        return Instant.now().isAfter(expirationInstant)
    }
    //endregion

    //region Utils
    private suspend fun makeBotguardServiceRequest(url: String, data: List<String>): String =
        withContext(Dispatchers.IO) {
            val body = JSONArray(data).toString()
            val requestBuilder = okhttp3.Request.Builder()
                .post(body.toRequestBody())
                .headers(
                    mapOf(
                        "User-Agent" to USER_AGENT,
                        "Accept" to "application/json",
                        "Content-Type" to "application/json+protobuf",
                        "x-goog-api-key" to GOOGLE_API_KEY,
                        "x-user-agent" to "grpc-web-javascript/0.1",
                    ).toHeaders()
                )
                .url(url)
            httpClient.newCall(requestBuilder.build()).execute().use { response ->
                if (response.code != 200) throw Exception("Invalid response code: ${response.code}")
                response.body?.string() ?: throw Exception("Empty BotGuard response body")
            }
        }

    private fun onInitializationError(error: Throwable) {
        CoroutineScope(Dispatchers.Main).launch {
            close()
            generatorContinuation.resumeWithException(error)
        }
    }

    @MainThread
    fun close() = with(webView) {
        clearHistory()
        clearCache(true)
        loadUrl("about:blank")
        onPause()
        removeAllViews()
        destroy()
    }
    //endregion

    companion object {
        private const val TAG = "PoTokenWebView"
        // LibreTube's public key — same one the entire open-source ecosystem uses.
        private const val GOOGLE_API_KEY = "AIzaSyDyT5W0Jh49F30Pqqtyfdf7pDLFKLJoAnw"
        private const val REQUEST_KEY = "O43z0dpjhgX20SCx4KAo"
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        private const val JS_INTERFACE = "PoTokenWebView"

        private val httpClient = OkHttpClient.Builder().build()

        suspend fun getNewPoTokenGenerator(context: Context): PoTokenWebView {
            return withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { cont ->
                    val potWv = PoTokenWebView(context, cont)
                    potWv.loadHtmlAndObtainBotguard(context)
                }
            }
        }
    }
}
