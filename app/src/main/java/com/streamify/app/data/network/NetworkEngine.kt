package com.streamify.app.data.network

import android.util.LruCache
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.util.concurrent.TimeUnit

object NetworkEngine {

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
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
    private val cache = LruCache<String, CachedStream>(64)

    data class CachedStream(
        val stream: ResolvedStream,
        val timestamp: Long = System.currentTimeMillis()
    )

    fun getStream(videoId: String): ResolvedStream? {
        val entry = cache.get(videoId) ?: return null
        if (System.currentTimeMillis() - entry.timestamp > CACHE_TTL_MS) {
            cache.remove(videoId)
            return null
        }
        return entry.stream
    }

    fun putStream(videoId: String, stream: ResolvedStream) {
        cache.put(videoId, CachedStream(stream))
    }

    fun clear() {
        cache.evictAll()
    }
}
