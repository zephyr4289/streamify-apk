package com.streamify.app.service

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

class PlaybackService : MediaSessionService() {
    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val renderersFactory = object : androidx.media3.exoplayer.DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: android.content.Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): androidx.media3.exoplayer.audio.AudioSink {
                return androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                    .setAudioProcessors(arrayOf(CrossfadeAudioProcessor()))
                    .build()
            }
        }

        val exoPlayer = ExoPlayer.Builder(this, renderersFactory)
            .setAudioAttributes(
                AudioAttributes.Builder()
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .setUsage(C.USAGE_MEDIA)
                .build(), true
            )
            .setHandleAudioBecomingNoisy(true)
            .setPauseAtEndOfMediaItems(false)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        
        player = exoPlayer
        
        mediaSession = MediaSession.Builder(this, exoPlayer)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        player = null
        super.onDestroy()
    }
}
