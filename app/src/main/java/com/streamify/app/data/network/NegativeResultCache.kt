package com.streamify.app.data.network

import java.util.concurrent.ConcurrentHashMap

/**
 * Calibrated negative result cache for walled or gated video IDs.
 *
 * An entry does NOT mean the track is unplayable — it serves as a fast-path
 * routing signal: "Skip Tier 1/R1 multi-client race for this specific ID and
 * route straight to Tier 2/R2 alternate-upload candidate matching".
 *
 * TTL is kept short (10 minutes) so network/IP changes self-heal quickly, and
 * entries are cleared immediately whenever any playback succeeds for that track.
 */
object NegativeResultCache {
    private const val WALLED_TTL_MS = 600_000L // 10 minutes

    private data class Entry(
        val walledAt: Long
    )

    private val cache = ConcurrentHashMap<String, Entry>()

    fun isWalled(videoId: String?): Boolean {
        if (videoId.isNullOrBlank()) return false
        val entry = cache[videoId] ?: return false
        val now = System.currentTimeMillis()
        return if (now - entry.walledAt < WALLED_TTL_MS) {
            true
        } else {
            cache.remove(videoId)
            false
        }
    }

    fun markWalled(videoId: String?) {
        if (videoId.isNullOrBlank()) return
        cache[videoId] = Entry(walledAt = System.currentTimeMillis())
    }

    fun clear(videoId: String?) {
        if (!videoId.isNullOrBlank()) {
            cache.remove(videoId)
        }
    }

    fun clearAll() {
        cache.clear()
    }
}
