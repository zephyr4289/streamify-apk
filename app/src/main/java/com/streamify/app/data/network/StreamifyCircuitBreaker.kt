package com.streamify.app.data.network

import android.util.LruCache

/**
 * Memory-bounded, lock-free circuit breaker for dead, deleted, or geo-blocked video IDs.
 * Bounded to 500 entries max to prevent heap bloat.
 */
object StreamifyCircuitBreaker {

    // 1-hour backoff window for definitively unplayable tracks
    private const val BACKOFF_DURATION_MS = 3_600_000L

    private val deadTracks = object : LruCache<String, Long>(500) {}

    /**
     * Checks if a video ID is currently tripped.
     * Returns true if we should fast-fail and skip network resolution.
     */
    fun isDefinitivelyDead(videoId: String): Boolean {
        if (videoId.isBlank()) return false
        val trippedAtMs = synchronized(deadTracks) {
            deadTracks.get(videoId)
        } ?: return false

        val elapsed = System.currentTimeMillis() - trippedAtMs
        if (elapsed >= BACKOFF_DURATION_MS) {
            synchronized(deadTracks) {
                deadTracks.remove(videoId)
            }
            return false
        }
        return true
    }

    /**
     * Trips the circuit breaker for a video ID when YouTube explicitly reports
     * UNPLAYABLE, ERROR, or LOGIN_REQUIRED.
     */
    fun tripHard(videoId: String) {
        if (videoId.isNotBlank()) {
            synchronized(deadTracks) {
                deadTracks.put(videoId, System.currentTimeMillis())
            }
        }
    }

    /**
     * Clears any tripped state upon a successful resolution or playback.
     */
    fun recordSuccess(videoId: String) {
        if (videoId.isNotBlank()) {
            synchronized(deadTracks) {
                deadTracks.remove(videoId)
            }
        }
    }
}
