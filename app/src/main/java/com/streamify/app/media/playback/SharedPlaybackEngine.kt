package com.streamify.app.media.playback

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.streamify.app.util.SLog

/**
 * SharedPlaybackEngine — process-wide holder for the playback engine
 * (Phase 4, Android Auto support).
 *
 * The phone UI and the Auto browse service both need a Media3 session over
 * the SAME player so a queue started from the head unit is exactly the
 * queue the phone shows (and vice versa). Media3 explicitly supports one
 * Player instance behind multiple MediaSessions; this holder is the seam
 * where the main [PlaybackService] publishes its engine and
 * [com.streamify.app.auto.StreamifyAutoMediaBrowserService] consumes it.
 *
 * Cold-start path: if the main service is not running when Auto binds, the
 * Auto service performs ONE best-effort warm start (background FGS
 * restrictions may decline it silently) — the next bind then finds the
 * engine alive.
 */
object SharedPlaybackEngine {

    /** The forwarding player backing the main app session (seek-safe). */
    @Volatile
    var player: androidx.media3.common.Player? = null
        private set

    /** Published by PlaybackService once its session is wired. */
    fun publish(player: androidx.media3.common.Player) {
        this.player = player
    }

    fun withdraw(player: androidx.media3.common.Player) {
        // Only the owner may withdraw — a late callback from a destroyed
        // service must not yank a freshly re-published engine.
        if (this.player === player) this.player = null
    }

    /**
     * Best-effort cold-start of the main playback service. Never throws;
     * when the platform refuses the background FGS start the Auto bind
     * simply retries later.
     */
    fun ensureEngine(context: Context) {
        if (player != null) return
        runCatching {
            ContextCompat.startForegroundService(
                context.applicationContext,
                Intent(context.applicationContext, PlaybackService::class.java)
            )
        }.onFailure { t ->
            SLog.st("SharedPlaybackEngine", "warm start declined", t)
        }
    }
}
