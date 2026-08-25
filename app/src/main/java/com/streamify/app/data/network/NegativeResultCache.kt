package com.streamify.app.data.network

import android.util.LruCache

/**
 * Memory-bounded negative-result memory for stream-resolution ROUTING.
 *
 * Deliberately distinct from [StreamifyCircuitBreaker], which hard-fast-fails
 * definitively dead IDs. This cache marks IDs whose failure is content-gated
 * (LOGIN_REQUIRED-class walls observed by a full losing race) so the resolver
 * can route straight to alternate-upload search (Tier 2 / R2) instead of
 * burning another doomed client race on every retry.
 *
 * Entries are cleared the moment ANY rung resolves the ID successfully.
 * Process-lifetime by design; disk persistence can ride on the vault later.
 */
object NegativeResultCache {

    private const val TTL_MS = 7L * 24 * 60 * 60 * 1000
    private const val MAX_ENTRIES = 500

    private val marked = object : LruCache<String, Pair<Long, String>>(MAX_ENTRIES) {}

    @Synchronized
    fun mark(videoId: String, reason: String) {
        if (videoId.isNotBlank()) {
            marked.put(videoId, System.currentTimeMillis() to reason)
        }
    }

    @Synchronized
    fun isWalled(videoId: String): Boolean {
        if (videoId.isBlank()) return false
        val entry = marked.get(videoId) ?: return false
        return if (System.currentTimeMillis() - entry.first >= TTL_MS) {
            marked.remove(videoId)
            false
        } else {
            true
        }
    }

    @Synchronized
    fun clear(videoId: String) {
        if (videoId.isNotBlank()) marked.remove(videoId)
    }
}
