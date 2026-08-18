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
        val syncAudioProcessor: SyncAudioProcessor = SyncAudioProcessor()
        val isBuffering = kotlinx.coroutines.flow.MutableStateFlow(false)
    }

    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: android.content.Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): androidx.media3.exoplayer.audio.AudioSink? {
                return DefaultAudioSink.Builder(context)
                    .setAudioProcessors(arrayOf(CrossfadeAudioProcessor(), syncAudioProcessor, MeshPcmAudioProcessor()))
                    .build()
            }
        }

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
                .build(), true
            )
            .setHandleAudioBecomingNoisy(true)
            .setPauseAtEndOfMediaItems(false)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        
        player = exoPlayer
        exoPlayer.setSkipSilenceEnabled(true)
        
        preBufferManager = PredictivePreBufferManager(exoPlayer, audioCache)
        exoPlayer.addListener(preBufferManager!!)

        exoPlayer.addListener(object : androidx.media3.common.Player.Listener {
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

        mediaSession = MediaSession.Builder(this, exoPlayer)
            .build()
    }

    private var preBufferManager: PredictivePreBufferManager? = null

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onDestroy() {
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

