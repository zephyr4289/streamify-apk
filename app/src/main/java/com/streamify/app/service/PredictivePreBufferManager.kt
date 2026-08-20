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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class PredictivePreBufferManager(
    private val player: Player,
    private val simpleCache: SimpleCache
) : Player.Listener {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    private val isReleased = AtomicBoolean(false)
    private var preBufferJob: Job? = null
    private var monitorJob: Job? = null
    private var lastPreBufferedMediaId: String? = null

    init {
        player.addListener(this)
        startPlaybackMonitor()
    }

    private fun startPlaybackMonitor() {
        monitorJob?.cancel()
        monitorJob = scope.launch {
            while (isActive && !isReleased.get()) {
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

        // Trigger pre-buffering at T-minus 45 seconds or if track is short
        if (remainingMs in 1..45000 || duration < 30000) {
            val nextIndex = player.nextMediaItemIndex
            if (nextIndex == -1 || nextIndex >= player.mediaItemCount) return

            // Lookahead pre-buffer next track (Slot N+1)
            val nextMediaItem = player.getMediaItemAt(nextIndex)
            val nextMediaId = nextMediaItem.mediaId.ifBlank { nextMediaItem.localConfiguration?.uri?.toString() ?: "" }

            if (nextMediaId.isNotBlank() && nextMediaId != lastPreBufferedMediaId) {
                lastPreBufferedMediaId = nextMediaId
                preBufferNextTrack(nextMediaId)
            }

            // Also warm Slot N+2 if available
            val overNextIndex = nextIndex + 1
            if (overNextIndex < player.mediaItemCount) {
                val overNextItem = player.getMediaItemAt(overNextIndex)
                val overNextId = overNextItem.mediaId.ifBlank { overNextItem.localConfiguration?.uri?.toString() ?: "" }
                if (overNextId.isNotBlank()) {
                    preBufferNextTrack(overNextId, isSecondary = true)
                }
            }
        }
    }

    private fun preBufferNextTrack(mediaIdOrUrl: String, isSecondary: Boolean = false) {
        if (isSecondary) {
            scope.launch {
                doPreBuffer(mediaIdOrUrl, 1024 * 1024L) // 1MB for secondary
            }
        } else {
            preBufferJob?.cancel()
            preBufferJob = scope.launch {
                doPreBuffer(mediaIdOrUrl, 2 * 1024 * 1024L) // 2MB for primary
            }
        }
    }

    private suspend fun doPreBuffer(mediaIdOrUrl: String, cacheBytes: Long) {
        try {
            // 1. Resolve stream URL
            val resolved = YouTubeStreamResolver.resolveStreamUrl(mediaIdOrUrl) ?: return
            val streamUrl = resolved.streamUrl
            if (streamUrl.isBlank() || streamUrl.startsWith("/") || streamUrl.startsWith("file://")) return

            // 2. Pre-cache into Media3 SimpleCache
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
                .setLength(cacheBytes)
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

    fun release() {
        if (isReleased.compareAndSet(false, true)) {
            try {
                player.removeListener(this)
            } catch (e: Exception) {
                // Ignore detachment error if player is already torn down
            }
            monitorJob?.cancel()
            preBufferJob?.cancel()
            job.cancel()
        }
    }
}
