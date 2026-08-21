package com.streamify.app.service

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import com.streamify.app.data.models.Track
import com.streamify.app.data.network.YouTubeStreamResolver
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

@OptIn(UnstableApi::class)
class PredictivePreBufferManager(
    private val context: Context
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activePreCacheJobs = ConcurrentHashMap<String, Job>()

    // Initial 2MB pre-cache slice (~25-30s of 160kbps Opus audio)
    private val preCacheChunkBytes = 2 * 1024 * 1024L

    fun preBufferUpcomingTracks(upcomingTracks: List<Track>) {
        val targets = upcomingTracks.take(2)

        targets.forEach { track ->
            val trackKey = if (track.id != 0) track.id.toString() else track.title
            if (activePreCacheJobs[trackKey]?.isActive == true) return@forEach

            val job = scope.launch {
                runCatching {
                    val streamUrl = if (track.filepath.startsWith("http")) {
                        track.filepath
                    } else {
                        val vid = track.ytmVideoId ?: YouTubeStreamResolver.extractVideoId(track.filepath)
                        if (vid != null) YouTubeStreamResolver.resolveStreamUrl(vid)?.streamUrl else null
                    } ?: return@launch

                    val uri = Uri.parse(streamUrl)
                    val cache = AudioCacheManager.getCache(context)
                    val upstreamFactory = DefaultHttpDataSource.Factory()
                        .setUserAgent("com.google.android.apps.youtube.music/7.21.50")
                        .setConnectTimeoutMs(4000)
                        .setReadTimeoutMs(4000)

                    val cacheDataSourceFactory = CacheDataSource.Factory()
                        .setCache(cache)
                        .setUpstreamDataSourceFactory(upstreamFactory)
                        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

                    val dataSpec = DataSpec.Builder()
                        .setUri(uri)
                        .setPosition(0)
                        .setLength(preCacheChunkBytes)
                        .setKey(streamUrl)
                        .build()

                    val cacheWriter = CacheWriter(
                        cacheDataSourceFactory.createDataSource(),
                        dataSpec,
                        null,
                        null
                    )

                    cacheWriter.cache()
                }
            }

            activePreCacheJobs[trackKey] = job
        }
    }

    fun cancelAll() {
        activePreCacheJobs.values.forEach { it.cancel() }
        activePreCacheJobs.clear()
    }

    fun release() {
        cancelAll()
        scope.cancel()
    }
}
