package com.streamify.app.data.network

import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object StreamifyDnsCache : Dns {
    private val cache = ConcurrentHashMap<String, List<InetAddress>>()

    override fun lookup(hostname: String): List<InetAddress> {
        // Fast-path: return cached IPs in <0.1ms
        cache[hostname]?.let { return it }

        // Slow-path: query system resolver and cache
        val addresses = try {
            InetAddress.getAllByName(hostname).toList()
        } catch (e: Exception) {
            Dns.SYSTEM.lookup(hostname)
        }
        if (addresses.isNotEmpty()) {
            cache[hostname] = addresses
        }
        return addresses
    }

    fun preWarm(hostname: String) {
        if (cache.containsKey(hostname)) return
        kotlin.concurrent.thread(name = "streamify-dns-prewarm", isDaemon = true) {
            try {
                val addresses = InetAddress.getAllByName(hostname).toList()
                if (addresses.isNotEmpty()) {
                    cache[hostname] = addresses
                }
            } catch (_: Throwable) {}
        }
    }
}

object ConnectionWarmer {
    suspend fun preWarmCDN(cdnUrl: String) = withContext(Dispatchers.IO) {
        if (cdnUrl.isBlank() || !cdnUrl.startsWith("http")) return@withContext
        try {
            val uri = android.net.Uri.parse(cdnUrl)
            val host = uri.host ?: return@withContext
            StreamifyDnsCache.preWarm(host)

            // Lightweight HTTP request to force DNS resolution + TLS 1.3 handshake and pool the socket
            val warmRequest = Request.Builder()
                .url(cdnUrl)
                .header("Range", "bytes=0-1")
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                .build()

            NetworkEngine.exoPlayerClient.newCall(warmRequest).execute().use { _ ->
                // Socket is now warm and pooled in exoPlayerClient's connection pool
            }
        } catch (_: Throwable) {
            // Fail silently; ExoPlayer will establish connection normally
        }
    }
}

object ZeroCopyBufferPool {
    // Thread-local DirectByteBuffer pool (512KB off-heap per thread) for zero-copy JNI parsing
    private val localBuffer = ThreadLocal.withInitial {
        java.nio.ByteBuffer.allocateDirect(512 * 1024).order(java.nio.ByteOrder.nativeOrder())
    }

    fun obtain(): java.nio.ByteBuffer {
        val buf = localBuffer.get()
        buf.clear()
        return buf
    }

    fun readResponseDirect(response: okhttp3.Response, buffer: java.nio.ByteBuffer): Int {
        buffer.clear()
        val source = response.body?.source() ?: return -1
        val channel = java.nio.channels.Channels.newChannel(source.inputStream())
        var totalBytesRead = 0
        while (buffer.hasRemaining()) {
            val read = channel.read(buffer)
            if (read == -1) break
            totalBytesRead += read
        }
        buffer.flip()
        return totalBytesRead
    }
}

object NetworkEngine {

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(StreamifyDnsCache)
            .connectionPool(ConnectionPool(32, 5, TimeUnit.MINUTES))
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .connectTimeout(3000, TimeUnit.MILLISECONDS)
            .readTimeout(4000, TimeUnit.MILLISECONDS)
            .writeTimeout(3000, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    val prioritizedInterceptor = Interceptor { chain ->
        val request = chain.request()
        val newRequest = if (request.url.host.contains("googlevideo.com")) {
            request.newBuilder()
                .header("X-Streamify-Priority", "CRITICAL")
                .build()
        } else {
            request
        }
        chain.proceed(newRequest)
    }

    val exoPlayerClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(StreamifyDnsCache)
            .addInterceptor(prioritizedInterceptor)
            .connectionPool(ConnectionPool(16, 5, TimeUnit.MINUTES))
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .connectTimeout(10000, TimeUnit.MILLISECONDS)
            .readTimeout(10000, TimeUnit.MILLISECONDS)
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
