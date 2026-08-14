package com.streamify.app.service

import android.net.Uri
import androidx.media3.common.Player
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.SimpleCache
import com.streamify.app.data.network.YouTubeStreamResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class PredictivePreBufferManager(
    private val player: Player,
    private val simpleCache: SimpleCache
) : Player.Listener {

    private val scope = CoroutineScope(Dispatchers.IO)
    private var preBufferJob: Job? = null
    private var monitorJob: Job? = null
    private var lastPreBufferedMediaId: String? = null

    init {
        startPlaybackMonitor()
    }

    private fun startPlaybackMonitor() {
        monitorJob?.cancel()
        monitorJob = scope.launch {
            while (isActive) {
                try {
                    checkAndPreBufferNext()
                } catch (e: Exception) {
                    // Ignore monitoring errors
                }
                delay(3000) // Check every 3 seconds
            }
        }
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int
    ) {
        if (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
            lastPreBufferedMediaId = null
        }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_READY) {
            checkAndPreBufferNext()
        }
    }

    private fun checkAndPreBufferNext() {
        if (!player.isPlaying) return

        val duration = player.duration
        val position = player.currentPosition
        if (duration <= 0) return

        val remainingMs = duration - position

        // Trigger pre-buffering at T-minus 35 seconds
        if (remainingMs in 1..35000) {
            val nextIndex = player.nextMediaItemIndex
            if (nextIndex == -1 || nextIndex >= player.mediaItemCount) return

            val nextMediaItem = player.getMediaItemAt(nextIndex)
            val nextMediaId = nextMediaItem.mediaId.ifBlank { nextMediaItem.localConfiguration?.uri?.toString() ?: "" }

            if (nextMediaId.isBlank() || nextMediaId == lastPreBufferedMediaId) return

            lastPreBufferedMediaId = nextMediaId
            preBufferNextTrack(nextMediaId)
        }
    }

    private fun preBufferNextTrack(mediaIdOrUrl: String) {
        preBufferJob?.cancel()
        preBufferJob = scope.launch {
            try {
                // 1. Resolve stream URL via Engine 4
                val resolved = YouTubeStreamResolver.resolveStreamUrl(mediaIdOrUrl) ?: return@launch
                val streamUrl = resolved.streamUrl
                if (streamUrl.isBlank()) return@launch

                // 2. Pre-cache first 2MB (approx 35s of high-quality audio) into Media3 SimpleCache
                val httpFactory = DefaultHttpDataSource.Factory()
                    .setConnectTimeoutMs(4000)
                    .setReadTimeoutMs(6000)
                    .setAllowCrossProtocolRedirects(true)

                val cacheFactory = CacheDataSource.Factory()
                    .setCache(simpleCache)
                    .setUpstreamDataSourceFactory(httpFactory)
                    .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

                val dataSpec = DataSpec.Builder()
                    .setUri(Uri.parse(streamUrl))
                    .setPosition(0)
                    .setLength(2 * 1024 * 1024L) // 2MB pre-buffer chunk
                    .build()

                val cacheWriter = CacheWriter(
                    cacheFactory.createDataSource(),
                    dataSpec,
                    ByteArray(32 * 1024),
                    null
                )

                cacheWriter.cache()
            } catch (e: Exception) {
                // Silently ignore pre-buffer failures (ExoPlayer will stream natively on fallback)
            }
        }
    }

    fun release() {
        monitorJob?.cancel()
        preBufferJob?.cancel()
    }
}
