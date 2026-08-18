package com.streamify.app.data.network

import android.util.LruCache
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.util.concurrent.TimeUnit

object NetworkEngine {

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectionPool(ConnectionPool(32, 5, TimeUnit.MINUTES))
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .connectTimeout(3000, TimeUnit.MILLISECONDS)
            .readTimeout(4000, TimeUnit.MILLISECONDS)
            .writeTimeout(3000, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}

object StreamEdgeCache {
    private const val CACHE_TTL_MS = 1000L * 60 * 60 * 4 // 4 Hours
    private val audioCache = LruCache<String, CachedStream>(64)
    private val videoCache = LruCache<String, CachedStream>(64)
    private val expiryRegex = Regex("[?&]expire=([0-9]+)")

    data class CachedStream(
        val stream: ResolvedStream,
        val timestamp: Long = System.currentTimeMillis()
    )

    fun isNearExpiry(url: String, safetyMarginSec: Long = 7200L): Boolean {
        if (url.isBlank() || !url.startsWith("http")) return true
        val expireEpoch = expiryRegex.find(url)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: return false
        val nowSec = System.currentTimeMillis() / 1000L
        return nowSec >= (expireEpoch - safetyMarginSec) // 2-hour early safety buffer
    }

    fun getStream(videoId: String): ResolvedStream? {
        val entry = audioCache.get(videoId) ?: return null
        if (System.currentTimeMillis() - entry.timestamp > CACHE_TTL_MS || isNearExpiry(entry.stream.streamUrl)) {
            audioCache.remove(videoId)
            return null
        }
        return entry.stream
    }

    fun putStream(videoId: String, stream: ResolvedStream) {
        audioCache.put(videoId, CachedStream(stream))
    }

    fun getVideoStream(videoId: String): ResolvedStream? {
        val entry = videoCache.get(videoId) ?: return null
        if (System.currentTimeMillis() - entry.timestamp > CACHE_TTL_MS || isNearExpiry(entry.stream.streamUrl)) {
            videoCache.remove(videoId)
            return null
        }
        return entry.stream
    }

    fun evictStream(videoId: String) {
        audioCache.remove(videoId)
        videoCache.remove(videoId)
    }

    fun putVideoStream(videoId: String, stream: ResolvedStream) {
        videoCache.put(videoId, CachedStream(stream))
    }

    fun clear() {
        audioCache.evictAll()
        videoCache.evictAll()
    }
}
