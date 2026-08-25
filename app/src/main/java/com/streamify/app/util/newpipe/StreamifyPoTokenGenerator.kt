package com.streamify.app.util.newpipe

import android.os.Handler
import android.os.Looper
import com.streamify.app.data.network.YouTubeStreamResolver
import com.streamify.app.util.SLog
import kotlinx.coroutines.runBlocking
import org.schabi.newpipe.extractor.services.youtube.InnertubeClientRequestInfo
import org.schabi.newpipe.extractor.services.youtube.PoTokenProvider
import org.schabi.newpipe.extractor.services.youtube.PoTokenResult
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper

/**
 * Mints web-client PO tokens through a hidden WebView (BotGuard VM) and feeds
 * them to the NewPipe YoutubeStreamExtractor.
 *
 * Port of NewPipe's NewPipePoTokenGenerator with SLog forensics.
 */
class StreamifyPoTokenGenerator : PoTokenProvider {
    private val supportsWebView by lazy { runCatching { android.webkit.CookieManager.getInstance() }.isSuccess }

    private object WebPoTokenGenLock
    private var webPoTokenVisitorData: String? = null
    private var webPoTokenStreamingPot: String? = null
    private var webPoTokenGenerator: PoTokenWebView? = null

    override fun getWebClientPoToken(videoId: String): PoTokenResult? {
        if (!supportsWebView) {
            SLog.w(TAG, "WebView unavailable — PO tokens cannot be generated")
            return null
        }

        val result = runCatching {
            getWebClientPoToken(videoId, false)
        }
        result.onFailure {
            SLog.e(TAG, "PO token generation failed for $videoId: ${it.message}")
        }
        return result.getOrNull()
    }

    /**
     * @param forceRecreate whether to force recreation of [webPoTokenGenerator], used when the
     * current generator threw during the last call (e.g. WebView content lost in background).
     */
    private fun getWebClientPoToken(videoId: String, forceRecreate: Boolean): PoTokenResult {
        data class Quadruple<T1, T2, T3, T4>(val t1: T1, val t2: T2, val t3: T3, val t4: T4)

        val (poTokenGenerator, visitorData, streamingPot, hasBeenRecreated) =
            synchronized(WebPoTokenGenLock) {
                val shouldRecreate =
                    webPoTokenGenerator == null || forceRecreate || webPoTokenGenerator!!.isExpired()

                if (shouldRecreate) {
                    val innertubeClientRequestInfo = InnertubeClientRequestInfo.ofWebClient()
                    innertubeClientRequestInfo.clientInfo.clientVersion =
                        YoutubeParsingHelper.getClientVersion()

                    webPoTokenVisitorData = YoutubeParsingHelper.getVisitorDataFromInnertube(
                        innertubeClientRequestInfo,
                        org.schabi.newpipe.extractor.NewPipe.getPreferredLocalization(),
                        org.schabi.newpipe.extractor.NewPipe.getPreferredContentCountry(),
                        YoutubeParsingHelper.getYouTubeHeaders(),
                        YoutubeParsingHelper.YOUTUBEI_V1_URL,
                        null,
                        false
                    )

                    runBlocking {
                        // close the current generator on the main thread
                        webPoTokenGenerator?.let {
                            Handler(Looper.getMainLooper()).post { it.close() }
                        }

                        // create a new generator; the streaming poToken must be minted exactly
                        // once before any per-video player token.
                        webPoTokenGenerator = PoTokenWebView.getNewPoTokenGenerator(context())
                        webPoTokenStreamingPot =
                            webPoTokenGenerator!!.generatePoToken(webPoTokenVisitorData!!)
                    }
                    SLog.i(TAG, "BotGuard generator ready visitor=${webPoTokenVisitorData?.take(12)}…")
                }

                Quadruple(
                    webPoTokenGenerator!!,
                    webPoTokenVisitorData!!,
                    webPoTokenStreamingPot!!,
                    shouldRecreate
                )
            }

        val playerPot = try {
            runBlocking {
                poTokenGenerator.generatePoToken(videoId)
            }
        } catch (throwable: Throwable) {
            if (hasBeenRecreated) {
                throw throwable
            } else {
                SLog.w(TAG, "poToken failed once, recreating generator and retrying $videoId")
                return getWebClientPoToken(videoId = videoId, forceRecreate = true)
            }
        }

        SLog.d(TAG, "poToken minted for $videoId")
        return PoTokenResult(visitorData, playerPot, streamingPot)
    }

    override fun getWebEmbedClientPoToken(videoId: String?): PoTokenResult? = null
    override fun getAndroidClientPoToken(videoId: String?): PoTokenResult? = null
    override fun getIosClientPoToken(videoId: String?): PoTokenResult? = null

    private fun context(): android.content.Context =
        YouTubeStreamResolver.appContext
            ?: throw IllegalStateException("appContext not attached for PO token generation")

    companion object { private const val TAG = "PoTokenGen" }
}
