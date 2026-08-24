package com.streamify.app.service

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.streamify.app.data.network.YouTubeStreamResolver
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PlaybackService : MediaSessionService() {
    companion object {
        // LEGACY objects kept alive only for non-chain consumers:
        //  - syncAudioProcessor: Jam lockstep hardware-latency compensation
        //    (PlayerViewModel.getAcousticPositionMs)
        //  - crossfadeAudioProcessor companion: user's crossfade pref storage
        // They are NO LONGER in the render chain — see streamifyProcessor.
        val syncAudioProcessor: SyncAudioProcessor = SyncAudioProcessor()
        val crossfadeAudioProcessor: CrossfadeAudioProcessor = CrossfadeAudioProcessor()

        /**
         * THE render-path processor: one fused native pass per buffer.
         * Old chain was [RustDsp, MeshPcm, Crossfade, Sync] = 5 copies +
         * 3 JNI crossings + a Kotlin sample loop on the real-time thread.
         */
        val streamifyProcessor: StreamifyAudioProcessor = StreamifyAudioProcessor()

        // ── CIRCUIT BREAKER (single-owner error recovery) ──
        // Set when THIS service initiates a CDN token renewal for the current
        // item (position-preserving replaceMediaItem). The ViewModel-level
        // error listener checks these to stay hands-off instead of running its
        // own re-resolve-and-reset-to-zero path concurrently (the old dual
        // engine ping-ponged progress resets on flaky CDN URLs).
        @Volatile var lastRenewalMediaId: String? = null
        @Volatile var lastRenewalAtMs: Long = 0L

        val isBuffering = kotlinx.coroutines.flow.MutableStateFlow(false)
        @Volatile var onSeekNextListener: (() -> Unit)? = null
        @Volatile var onSeekPrevListener: (() -> Unit)? = null
    }


    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
        } catch (_: Throwable) {}
        DolbySpatialManager.init(this)
        AudioDeviceManager.init(this)

        val renderersFactory = DefaultRenderersFactory(this)

        val audioLoadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 2500,
                /* maxBufferMs = */ 30000,
                /* bufferForPlaybackMs = */ 500,
                /* bufferForPlaybackAfterRebufferMs = */ 1000
            )
            .setTargetBufferBytes(C.LENGTH_UNSET)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val httpDataSourceFactory = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(
            com.streamify.app.data.network.NetworkEngine.exoPlayerClient
        ).setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")

        // Progressive 250MB Audio LRU Cache (Zero-latency seeking & offline replaying)
        val audioCache = AudioCacheManager.getCache(this)
        val cacheDataSourceFactory = androidx.media3.datasource.cache.CacheDataSource.Factory()
            .setCache(audioCache)
            .setUpstreamDataSourceFactory(httpDataSourceFactory)
            .setFlags(androidx.media3.datasource.cache.CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        val dataSourceFactory = androidx.media3.datasource.DefaultDataSource.Factory(this, cacheDataSourceFactory)

        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(dataSourceFactory)

        val exoPlayer = ExoPlayer.Builder(this, renderersFactory, mediaSourceFactory)
            .setAudioAttributes(
                AudioAttributes.Builder()
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .setUsage(C.USAGE_MEDIA)
                .setSpatializationBehavior(C.SPATIALIZATION_BEHAVIOR_AUTO)
                .build(), true
            )
            .setLoadControl(audioLoadControl)
            .setHandleAudioBecomingNoisy(true)
            .setPauseAtEndOfMediaItems(false)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()

        
        player = exoPlayer
        exoPlayer.setSkipSilenceEnabled(true)

        AudioDeviceManager.onHeadsetDisconnectedListener = {
            if (exoPlayer.isPlaying) {
                exoPlayer.pause()
            }
        }
        
        preBufferManager = PredictivePreBufferManager(this)

        exoPlayer.addListener(object : androidx.media3.common.Player.Listener {
            private var lastPlayStartMs: Long = 0L
            private var lastCountedMediaId: String? = null
            private var lastCountedAtMs: Long = 0L

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                val now = System.currentTimeMillis()
                if (isPlaying) {
                    lastPlayStartMs = now
                } else if (lastPlayStartMs > 0L) {
                    val deltaSec = ((now - lastPlayStartMs) / 1000L).coerceAtLeast(0L)
                    if (deltaSec > 0) {
                        com.streamify.app.data.YtStatsTelemetryEngine.recordListeningSeconds(deltaSec)
                    }
                    lastPlayStartMs = 0L
                }
            }

            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                runCatching {
                    com.streamify.app.data.NativeBridge.nativeResetAudioDSP()
                }

                val now = System.currentTimeMillis()
                if (lastPlayStartMs > 0L) {
                    val deltaSec = ((now - lastPlayStartMs) / 1000L).coerceAtLeast(0L)
                    if (deltaSec > 0) {
                        com.streamify.app.data.YtStatsTelemetryEngine.recordListeningSeconds(deltaSec)
                    }
                    lastPlayStartMs = if (exoPlayer.isPlaying) now else 0L
                }

                if (mediaItem != null) {
                    val title = mediaItem.mediaMetadata.title?.toString() ?: ""
                    val artist = mediaItem.mediaMetadata.artist?.toString() ?: ""
                    val cover = mediaItem.mediaMetadata.artworkUri?.toString() ?: ""
                    val path = mediaItem.localConfiguration?.uri?.toString() ?: ""
                    // STATS OVERHAUL: this listener is the SINGLE writer for
                    // listening seconds AND play counts. Real listen length is
                    // passed so sub-10s blips never inflate Top Songs, and
                    // same-track re-preparations (CDN 403 renewal) are deduped.
                    val nowMs = System.currentTimeMillis()
                    val isRenewalReplay = mediaItem.mediaId == lastCountedMediaId &&
                            (nowMs - lastCountedAtMs) < 1500L
                    if (title.isNotBlank() && !isRenewalReplay) {
                        lastCountedMediaId = mediaItem.mediaId
                        lastCountedAtMs = nowMs
                        val track = com.streamify.app.data.models.Track(
                            id = mediaItem.mediaId.toIntOrNull() ?: 0,
                            title = title,
                            artist = artist,
                            filepath = path,
                            coverArtPath = cover
                        )
                        val listenedForThisPlay =
                            ((nowMs - (lastPlayStartMs.takeIf { it > 0 } ?: nowMs)) / 1000L)
                                .coerceIn(0L, 3600L)
                        com.streamify.app.data.YtStatsTelemetryEngine.recordTrackPlay(track, listenedForThisPlay)
                    }
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                isBuffering.value = (playbackState == androidx.media3.common.Player.STATE_BUFFERING)
            }

            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                super.onAudioSessionIdChanged(audioSessionId)
                EqualizerManager.init(this@PlaybackService, audioSessionId)
            }


            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                super.onPlayerError(error)
                error.printStackTrace()

                // Engine 3: JIT CDN Token Auto-Renewer (403/410 Forbidden Shield)
                val isExpiredOrBadHttp = error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
                        error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                        error.message?.contains("403") == true || error.message?.contains("410") == true

                if (isExpiredOrBadHttp) {
                    val currentItem = exoPlayer.currentMediaItem
                    val currentPos = exoPlayer.currentPosition
                    val mediaUri = currentItem?.localConfiguration?.uri?.toString() ?: ""
                    val mediaId = currentItem?.mediaId ?: mediaUri

                    if (mediaId.isNotBlank()) {
                        // Announce ownership BEFORE the async renewal so the
                        // ViewModel listener defers to this path.
                        lastRenewalMediaId = mediaId
                        lastRenewalAtMs = System.currentTimeMillis()

                        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                            try {
                                val fresh = YouTubeStreamResolver.resolveStreamUrl(mediaId, forceFresh = true)
                                if (fresh != null && fresh.streamUrl.isNotBlank()) {
                                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                                        val updatedItem = currentItem!!.buildUpon()
                                            .setUri(android.net.Uri.parse(fresh.streamUrl))
                                            .build()
                                        val curIdx = exoPlayer.currentMediaItemIndex
                                        exoPlayer.replaceMediaItem(curIdx, updatedItem)
                                        exoPlayer.seekTo(curIdx, currentPos)
                                        exoPlayer.prepare()
                                        exoPlayer.play()
                                    }
                                }
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                    }
                }
            }
        })

        val forwardingPlayer = object : androidx.media3.common.ForwardingPlayer(exoPlayer) {
            override fun getAvailableCommands(): androidx.media3.common.Player.Commands {
                return super.getAvailableCommands().buildUpon()
                    .add(androidx.media3.common.Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
                    .add(androidx.media3.common.Player.COMMAND_SEEK_TO_MEDIA_ITEM)
                    .add(androidx.media3.common.Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                    .add(androidx.media3.common.Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                    .add(androidx.media3.common.Player.COMMAND_SEEK_TO_NEXT)
                    .add(androidx.media3.common.Player.COMMAND_SEEK_TO_PREVIOUS)
                    .add(androidx.media3.common.Player.COMMAND_SEEK_BACK)
                    .add(androidx.media3.common.Player.COMMAND_SEEK_FORWARD)
                    .build()
            }

            override fun isCommandAvailable(command: Int): Boolean {
                return when (command) {
                    androidx.media3.common.Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                    androidx.media3.common.Player.COMMAND_SEEK_TO_MEDIA_ITEM,
                    androidx.media3.common.Player.COMMAND_SEEK_TO_NEXT,
                    androidx.media3.common.Player.COMMAND_SEEK_TO_PREVIOUS,
                    androidx.media3.common.Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                    androidx.media3.common.Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                    androidx.media3.common.Player.COMMAND_SEEK_BACK,
                    androidx.media3.common.Player.COMMAND_SEEK_FORWARD -> true
                    else -> super.isCommandAvailable(command)
                }
            }

            override fun seekToNext() {
                if (onSeekNextListener != null) {
                    onSeekNextListener?.invoke()
                } else {
                    super.seekToNext()
                }
            }

            override fun seekToNextMediaItem() {
                if (onSeekNextListener != null) {
                    onSeekNextListener?.invoke()
                } else {
                    super.seekToNextMediaItem()
                }
            }

            override fun seekToPrevious() {
                if (onSeekPrevListener != null) {
                    onSeekPrevListener?.invoke()
                } else {
                    super.seekToPrevious()
                }
            }

            override fun seekToPreviousMediaItem() {
                if (onSeekPrevListener != null) {
                    onSeekPrevListener?.invoke()
                } else {
                    super.seekToPreviousMediaItem()
                }
            }
        }

        val sessionCallback = object : MediaSession.Callback {
            override fun onConnect(
                session: MediaSession,
                controller: MediaSession.ControllerInfo
            ): MediaSession.ConnectionResult {
                val availablePlayerCommands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                    .add(androidx.media3.common.Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
                    .add(androidx.media3.common.Player.COMMAND_SEEK_TO_MEDIA_ITEM)
                    .add(androidx.media3.common.Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                    .add(androidx.media3.common.Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                    .add(androidx.media3.common.Player.COMMAND_SEEK_TO_NEXT)
                    .add(androidx.media3.common.Player.COMMAND_SEEK_TO_PREVIOUS)
                    .add(androidx.media3.common.Player.COMMAND_SEEK_BACK)
                    .add(androidx.media3.common.Player.COMMAND_SEEK_FORWARD)
                    .build()

                return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                    .setAvailablePlayerCommands(availablePlayerCommands)
                    .build()
            }
        }


        mediaSession = MediaSession.Builder(this, forwardingPlayer)
            .setCallback(sessionCallback)
            .build()
    }

    private var preBufferManager: PredictivePreBufferManager? = null

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onDestroy() {
        AudioDeviceManager.release(this)
        EqualizerManager.release()
        preBufferManager?.release()
        preBufferManager = null
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        player = null
        super.onDestroy()
    }
}


