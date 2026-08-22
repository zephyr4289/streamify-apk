package com.streamify.app.viewmodel

import android.content.ComponentName
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.streamify.app.data.NativeBridge
import com.streamify.app.data.TrackRepository
import com.streamify.app.data.models.Track
import com.streamify.app.service.PlaybackService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class PlaybackButtonState {
    BUFFERING,
    PLAYING,
    PAUSED
}

data class PlayerState(
    val currentTrack: Track? = null,
    val queue: List<Track> = emptyList(),
    val currentIndex: Int = 0,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val currentPosition: Long = 0,
    val duration: Long = 0,
    val isShuffleActive: Boolean = false,
    val isRepeatActive: Boolean = false,
    val sleepTimerMinutesLeft: Int? = null,
    val sleepTimerEndTrack: Boolean = false,
    val isAutoPlayEnabled: Boolean = true,
    val isVideoMode: Boolean = false
) {
    val buttonState: PlaybackButtonState
        get() = when {
            isBuffering -> PlaybackButtonState.BUFFERING
            isPlaying -> PlaybackButtonState.PLAYING
            else -> PlaybackButtonState.PAUSED
        }
}

class PlayerViewModel(private val repository: TrackRepository = TrackRepository) : ViewModel(),
    com.streamify.app.jam.JamEngine.Bridge {

    // ── JamEngine.Bridge: live-player facade for the Lockstep protocol ──
    override fun loadTrack(track: Track, positionMs: Long, play: Boolean) {
        playTrack(track, listOf(track), autoHydrateRadio = false)
        if (positionMs > 0L) seekTo(positionMs)
        if (!play) pause()
    }

    override fun seekTo(positionMs: Long) = this@PlayerViewModel.seekTo(positionMs)

    override fun setPlaying(play: Boolean) {
        if (play) this@PlayerViewModel.play() else this@PlayerViewModel.pause()
    }
    private val _playerState = MutableStateFlow(PlayerState())
    val playerState: StateFlow<PlayerState> = _playerState.asStateFlow()

    val currentTrack: StateFlow<Track?> = _playerState
        .map { it.currentTrack }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val progressFraction: StateFlow<Float> = _playerState
        .map { state ->
            if (state.duration > 0L) (state.currentPosition.toFloat() / state.duration.toFloat()).coerceIn(0f, 1f) else 0f
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0f)

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var appContext: Context? = null
    
    private var positionPollingJob: Job? = null
    private var sleepTimerJob: Job? = null
    private var lastPlayedTrackId: Int? = null
    private var preResolvingTrackKey: String? = null
    private var lookaheadJob: Job? = null
    private var playJob: Job? = null
    private var hydrateJob: Job? = null
    private var pendingSeekTargetMs: Long? = null
    private var isOptimisticSeeking: Boolean = false
    private var seekTimeoutJob: Job? = null
    private val processedTitleHashes = java.util.Collections.synchronizedSet(mutableSetOf<Long>())
    private val sessionPlayedTrackIds = java.util.Collections.synchronizedSet(mutableSetOf<Int>())
    // Dedup guard for lyric fetches: key = "trackId:videoIdOrEmpty" so a retry is only
    // allowed when the video identity actually improved (e.g. after DB registration).
    private val lyricsFetchAttempts = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    init {
        com.streamify.app.jam.JamEngine.attachBridge(this)
    }
    private val isAdvancing = java.util.concurrent.atomic.AtomicBoolean(false)
    private var playbackStartTimeMs: Long = 0L

    fun getController(): MediaController? = controller

    private val isVideoSwitching = java.util.concurrent.atomic.AtomicBoolean(false)

    fun toggleVideoMode(enabled: Boolean) {
        _playerState.value = _playerState.value.copy(isVideoMode = enabled)
        val ctrl = controller ?: return
        val currentT = _playerState.value.currentTrack ?: return
        val currentPos = ctrl.currentPosition
        val wasPlaying = ctrl.isPlaying
        val currentIndex = ctrl.currentMediaItemIndex

        if (isVideoSwitching.compareAndSet(false, true)) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    if (enabled) {
                        val videoStream = com.streamify.app.data.network.YouTubeStreamResolver.resolveVideoStreamUrl(currentT)
                        if (videoStream != null && videoStream.streamUrl.isNotBlank()) {
                            withContext(Dispatchers.Main) {
                                val currentItem = ctrl.currentMediaItem ?: return@withContext
                                val videoMediaItem = currentItem.buildUpon()
                                    .setUri(android.net.Uri.parse(videoStream.streamUrl))
                                    .setMimeType(androidx.media3.common.MimeTypes.VIDEO_MP4)
                                    .build()
                                
                                if (currentIndex in 0 until ctrl.mediaItemCount) {
                                    ctrl.replaceMediaItem(currentIndex, videoMediaItem)
                                    ctrl.seekTo(currentIndex, currentPos)
                                    if (wasPlaying) ctrl.play()
                                }
                            }
                        } else {
                            withContext(Dispatchers.Main) {
                                UiEventBus.emitEvent(UiEvent.ShowSnackbar("Music video stream unavailable for this track"))
                            }
                        }
                    } else {
                        val audioStream = com.streamify.app.data.network.YouTubeStreamResolver.resolveTrackStream(currentT)
                        val audioUrl = audioStream?.streamUrl ?: currentT.filepath
                        if (audioUrl.isNotBlank()) {
                            withContext(Dispatchers.Main) {
                                val currentItem = ctrl.currentMediaItem ?: return@withContext
                                val audioMediaItem = currentItem.buildUpon()
                                    .setUri(android.net.Uri.parse(audioUrl))
                                    .setMimeType(audioStream?.mimeType ?: androidx.media3.common.MimeTypes.AUDIO_WEBM)
                                    .build()
                                
                                if (currentIndex in 0 until ctrl.mediaItemCount) {
                                    ctrl.replaceMediaItem(currentIndex, audioMediaItem)
                                    ctrl.seekTo(currentIndex, currentPos)
                                    if (wasPlaying) ctrl.play()
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    isVideoSwitching.set(false)
                }
            }
        }
    }

    fun initialize(context: Context) {
        val appCtx = context.applicationContext
        appContext = appCtx
        repository.appContext = appCtx
        if (controllerFuture != null) return

        val sessionToken = SessionToken(appCtx, ComponentName(appCtx, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(appCtx, sessionToken).buildAsync()
        controllerFuture?.addListener({
            controller = controllerFuture?.get()
            setupController(appCtx)
            restorePlayerState(appCtx)
            com.streamify.app.data.SmartOfflineVaultEngine.initialize(appCtx)
        }, MoreExecutors.directExecutor())
    }

    fun savePlayerState(context: Context) {
        val prefs = context.getSharedPreferences("player_state", Context.MODE_PRIVATE)
        val state = _playerState.value
        if (state.queue.isNotEmpty()) {
            val queueIds = state.queue.map { it.id }.joinToString(",")
            val currentId = state.currentTrack?.id ?: -1
            prefs.edit()
                .putString("saved_queue", queueIds)
                .putInt("saved_current_id", currentId)
                .putLong("saved_position", state.currentPosition)
                .apply()
        }
    }

    private fun restorePlayerState(context: Context) {
        val prefs = context.getSharedPreferences("player_state", Context.MODE_PRIVATE)
        val queueStr = prefs.getString("saved_queue", "") ?: ""
        if (queueStr.isNotEmpty()) {
            viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                val ids = queueStr.split(",").mapNotNull { it.toIntOrNull() }
                if (ids.isNotEmpty()) {
                    val tracks = repository.getTracksByIds(ids)
                    if (tracks.isNotEmpty()) {
                        val currentId = prefs.getInt("saved_current_id", -1)
                        val currentTrack = tracks.find { it.id == currentId } ?: tracks.first()
                        val position = prefs.getLong("saved_position", 0L)
                        val targetIndex = tracks.indexOfFirst { it.id == currentTrack.id }.coerceAtLeast(0)
                        
                        withContext(kotlinx.coroutines.Dispatchers.Main) {
                            // Restore UI state only in PAUSED mode (do not auto-play on app launch)
                            _playerState.value = _playerState.value.copy(
                                currentTrack = currentTrack,
                                currentIndex = targetIndex,
                                queue = tracks,
                                isPlaying = false,
                                isBuffering = false,
                                currentPosition = position,
                                duration = (currentTrack.durationSec * 1000L).coerceAtLeast(0L)
                            )
                        }
                    }
                }
            }
        }

        // Reactive One-Shot Lookahead Trigger (Fires strictly once at >=75% progress or <=30s remaining)
        viewModelScope.launch {
            _playerState
                .map { state ->
                    val dur = state.duration
                    val pos = state.currentPosition
                    state.isPlaying && dur > 0L && (pos.toFloat() / dur.toFloat() >= 0.75f || (dur - pos) <= 30000L)
                }
                .distinctUntilChanged()
                .filter { it }
                .collect {
                    val curState = _playerState.value
                    val currentIdx = controller?.currentMediaItemIndex ?: curState.currentIndex
                    preResolveLookaheadTrack(currentIdx, curState.queue)
                }
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {

            _playerState.value = _playerState.value.copy(isPlaying = isPlaying)
            if (isPlaying) startPollingPosition() else stopPollingPosition()
            if (!isApplyingJamSync && com.streamify.app.data.remote.SupabaseClient.activeJam.value != null) {
                broadcastJamAction(if (isPlaying) "PLAY" else "PAUSE", isPlaying = isPlaying)
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val d = controller?.duration ?: 0L
            if (d > 0) {
                val curr = _playerState.value.currentTrack
                val updated = if (curr != null && curr.durationSec <= 0) {
                    curr.copy(durationSec = (d / 1000).toInt())
                } else curr
                _playerState.value = _playerState.value.copy(
                    duration = d,
                    currentPosition = controller?.currentPosition?.coerceAtLeast(0L) ?: 0L,
                    currentTrack = updated
                )
            }

            when (playbackState) {
                Player.STATE_ENDED -> {
                    // Failsafe: Reached end of physical timeline without pre-buffered slot ready
                    if (!isAdvancing.get()) {
                        advanceQueue(isUserSkip = false)
                    }
                }
                Player.STATE_BUFFERING -> {
                    _playerState.value = _playerState.value.copy(isBuffering = true)
                }
                Player.STATE_READY -> {
                    isOptimisticSeeking = false
                    pendingSeekTargetMs = null
                    seekTimeoutJob?.cancel()
                    _playerState.value = _playerState.value.copy(isBuffering = false)
                }
                else -> {}
            }
        }

        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
            val d = controller?.duration ?: 0L
            if (d > 0) {
                val curr = _playerState.value.currentTrack
                val updated = if (curr != null && curr.durationSec <= 0) {
                    curr.copy(durationSec = (d / 1000).toInt())
                } else curr
                _playerState.value = _playerState.value.copy(
                    duration = d,
                    currentPosition = controller?.currentPosition?.coerceAtLeast(0L) ?: 0L,
                    currentTrack = updated
                )
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            seekTimeoutJob?.cancel()
            isOptimisticSeeking = false
            pendingSeekTargetMs = null

            when (reason) {
                Player.MEDIA_ITEM_TRANSITION_REASON_AUTO,
                Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> {
                    handleAutomaticTimelineTransition()
                }
                Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED -> {
                    val curState = _playerState.value
                    val activeTrack = curState.currentTrack
                    val expectedTrack = curState.queue.getOrNull(curState.currentIndex)
                    if (activeTrack != null && expectedTrack != null && (activeTrack.id == expectedTrack.id || (activeTrack.title == expectedTrack.title && activeTrack.artist == expectedTrack.artist))) {
                        // Already aligned with current queue index, ignore secondary playlist mutation event
                    } else {
                        updateCurrentTrackFromMediaItem(mediaItem)
                    }
                }
                else -> {
                    updateCurrentTrackFromMediaItem(mediaItem)
                }
            }
            
            val currentT = _playerState.value.currentTrack
            val newTrackId = mediaItem?.mediaId?.removePrefix("trk_")?.toIntOrNull() ?: currentT?.id?.takeIf { it > 0 }
            if (currentT != null) {
                com.streamify.app.data.YtStatsTelemetryEngine.recordTrackPlay(currentT)
                viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        val registered = repository.registerStreamedTrack(currentT, appContext)
                        val validId = if (registered.id > 0) registered.id else newTrackId ?: 0
                        if (validId > 0) {
                            repository.updateSessionVector(validId, 0.45f)
                            repository.recordTrackPlay(validId)
                        }
                        appContext?.let { ctx ->
                            com.streamify.app.data.EdgeMeshRepository.getInstance(ctx).scheduleOpportunisticCompute(
                                context = ctx,
                                trackId = (if (validId > 0) validId else currentT.id).toString(),
                                trackTitle = currentT.title,
                                trackArtist = currentT.artist,
                                audioPath = currentT.filepath
                            )
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
            
            // Project Chronos AI & Circadian Event Logging: Track change
            if (lastPlayedTrackId != null && newTrackId != null && lastPlayedTrackId != newTrackId) {
                val wasSkipped = reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK || 
                                 reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO
                val currentHour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
                val posSec = ((controller?.currentPosition ?: 0L) / 1000L).toInt()
                val durSec = if ((controller?.duration ?: 0L) > 0) ((controller?.duration ?: 0L) / 1000L).toInt() else (_playerState.value.currentTrack?.durationSec ?: 0)
                val ratio = if (durSec > 0) (posSec.toFloat() / durSec.toFloat()).coerceIn(0f, 1f) else 0.5f

                // Real-Time Cloud Telemetry Sync
                val currentTrackObj = _playerState.value.currentTrack
                val prevTrackId = lastPlayedTrackId
                if (currentTrackObj != null && posSec >= 10) {
                    viewModelScope.launch(Dispatchers.IO) {
                        try {
                            val cleanSig = (currentTrackObj.title.trim().lowercase() + "_" + currentTrackObj.artist.trim().lowercase())
                            val cloudId = "trk_${kotlin.math.abs(cleanSig.hashCode())}"
                            val eventJson = org.json.JSONObject().apply {
                                put("track_id", cloudId)
                                put("track_title", currentTrackObj.title)
                                put("track_artist", currentTrackObj.artist)
                                put("duration_sec", posSec.toLong())
                                put("completion_ratio", ratio.toDouble())
                                put("hour_of_day", currentHour)
                                put("action_type", if (wasSkipped && ratio < 0.85f) "SKIP" else "PLAY")
                            }
                            com.streamify.app.data.remote.SupabaseClient.ingestTelemetryBatch(listOf(eventJson))
                        } catch (e: Exception) {
                            // Non-blocking telemetry failure
                        }
                    }
                }

                if (prevTrackId != null) {
                    viewModelScope.launch(Dispatchers.IO) {
                        try {
                            if (wasSkipped && ratio < 0.85f) {
                                repository.logSkipEvent(prevTrackId, newTrackId)
                            } else {
                                repository.logPlayEvent(prevTrackId, newTrackId)
                            }
                        } catch (e: Exception) {
                            // Non-blocking
                        }
                    }
                }
            }
            lastPlayedTrackId = newTrackId
            
            // Automatic Dual-Engine Lyrics Scraping & Sync Cache Dispatch.
            // PlayerViewModel is the SINGLE lyric network fetch owner: UI surfaces only
            // read from cache, preventing duplicate races and unverified fuzzy matches.
            maybeFetchLyricsForTrack(_playerState.value.currentTrack)
            
            // Smart Acoustic EQ Profile auto-adaptation
            val currentPlaying = _playerState.value.currentTrack
            if (currentPlaying != null) {
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        com.streamify.app.data.network.SmartAcousticEngine.getSmartEqProfile(currentPlaying)
                    } catch (e: Exception) {}
                }
            }
            
            if (_playerState.value.sleepTimerEndTrack && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                controller?.pause()
                _playerState.value = _playerState.value.copy(sleepTimerEndTrack = false, sleepTimerMinutesLeft = null)
            }

            // CONTINUUM INFINITE RADIO: When approaching the end of the queue (or queue size <= 2), fetch next radio batch
            if (_playerState.value.isAutoPlayEnabled) {
                val currentQueue = _playerState.value.queue
                val currentIdx = _playerState.value.currentIndex

                if (currentIdx >= currentQueue.size - 2) {
                    viewModelScope.launch(Dispatchers.IO) {
                        val currentT = _playerState.value.currentTrack
                        if (currentT != null) {
                            val continuumRecs = com.streamify.app.data.UniversalCandidateBroker.fetchCandidates(
                                seedTrack = currentT,
                                activeQueue = currentQueue,
                                targetCount = 15
                            )
                            if (continuumRecs.isNotEmpty()) {
                                val currentQ = _playerState.value.queue.toMutableList()
                                for (track in continuumRecs) {
                                    val isDup = currentQ.any {
                                        com.streamify.app.data.FuzzyTitleMatcher.isSameSongVariation(it.title, it.artist, track.title, track.artist)
                                    }
                                    if (!isDup) {
                                        currentQ.add(track)
                                    }
                                }
                                val newQueue = currentQ.distinctBy { it.id }
                                withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    _playerState.value = _playerState.value.copy(queue = newQueue)
                                }
                            }
                        }
                    }
                }
            }
        }
        
        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            _playerState.value = _playerState.value.copy(isShuffleActive = shuffleModeEnabled)
        }
        
        override fun onRepeatModeChanged(repeatMode: Int) {
            _playerState.value = _playerState.value.copy(isRepeatActive = repeatMode != Player.REPEAT_MODE_OFF)
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                // Hardware confirmed seek completion
                pendingSeekTargetMs = null
                isOptimisticSeeking = false
                _playerState.value = _playerState.value.copy(currentPosition = newPosition.positionMs)
            }

            if (_playerState.value.isAutoPlayEnabled) {
                val currentIdx = _playerState.value.currentIndex
                val currentQueue = _playerState.value.queue
                if (currentQueue.isNotEmpty() && currentQueue.size - currentIdx <= 2) {
                    viewModelScope.launch(Dispatchers.IO) {
                        val currentT = _playerState.value.currentTrack
                        if (currentT != null) {
                            val newTracks = com.streamify.app.data.UniversalCandidateBroker.fetchCandidates(
                                seedTrack = currentT,
                                activeQueue = currentQueue,
                                targetCount = 15
                            )
                            if (newTracks.isNotEmpty()) {
                                val currentQ = _playerState.value.queue.toMutableList()
                                for (track in newTracks) {
                                    val isDup = currentQ.any {
                                        com.streamify.app.data.FuzzyTitleMatcher.isSameSongVariation(it.title, it.artist, track.title, track.artist)
                                    }
                                    if (!isDup) {
                                        currentQ.add(track)
                                    }
                                }
                                withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    _playerState.value = _playerState.value.copy(queue = currentQ)
                                }
                            }
                        }
                    }
                }
            }
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            pendingSeekTargetMs = null
            isOptimisticSeeking = false
            val currentT = _playerState.value.currentTrack
            if (currentT != null) {
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        val resolved = com.streamify.app.data.network.YouTubeStreamResolver.resolveTrackStream(currentT)
                        if (resolved != null && resolved.streamUrl.isNotBlank()) {
                            val updated = currentT.copy(filepath = resolved.streamUrl)
                            withContext(kotlinx.coroutines.Dispatchers.Main) {
                                val currentQueue = _playerState.value.queue.toMutableList()
                                val idx = currentQueue.indexOfFirst {
                                    it.id == currentT.id || (it.title == currentT.title && it.artist == currentT.artist)
                                }
                                if (idx >= 0) {
                                    currentQueue[idx] = updated
                                    _playerState.value = _playerState.value.copy(queue = currentQueue, currentTrack = updated)
                                    val mediaItem = buildMediaItem(updated)
                                    controller?.setMediaItem(mediaItem, 0L)
                                    controller?.prepare()
                                    controller?.play()
                                }
                            }
                        } else {
                            withContext(kotlinx.coroutines.Dispatchers.Main) {
                                advanceQueue(isUserSkip = false)
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        withContext(kotlinx.coroutines.Dispatchers.Main) {
                            advanceQueue(isUserSkip = false)
                        }
                    }
                }
            } else {
                advanceQueue(isUserSkip = false)
            }
        }
    }

    private fun setupController(context: Context) {
        val ctrl = controller ?: return
        
        com.streamify.app.service.PlaybackService.onSeekNextListener = {
            viewModelScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                advanceQueue(isUserSkip = true)
            }
        }
        com.streamify.app.service.PlaybackService.onSeekPrevListener = {
            viewModelScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                skipPrevious()
            }
        }

        ctrl.removeListener(playerListener)
        ctrl.addListener(playerListener)
    }
    
    private fun preResolveLookaheadTrack(currentIndex: Int, queue: List<Track>) {
        if (currentIndex < 0 || currentIndex >= queue.size - 1) return
        val nextTrack = queue[currentIndex + 1]
        val trackKey = "${nextTrack.title}_${nextTrack.artist}".lowercase()
        if (preResolvingTrackKey == trackKey) return

        val needsResolution = nextTrack.filepath.isBlank() ||
                nextTrack.filepath.startsWith("online://") ||
                nextTrack.filepath.startsWith("ytsearch:") ||
                (nextTrack.filepath.startsWith("http") && !nextTrack.filepath.contains("googlevideo.com")) ||
                (nextTrack.filepath.contains("googlevideo.com") && isCdnExpired(nextTrack.filepath))

        if (needsResolution) {
            preResolvingTrackKey = trackKey
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val dbPath = appContext?.getDatabasePath("streamify_universal.db")?.absolutePath ?: ""
                    val cadId = NativeBridge.generateCadId(nextTrack.title, nextTrack.artist, nextTrack.durationSec)

                    var vidId = nextTrack.ytmVideoId ?: if (nextTrack.filepath.startsWith("yt_") || (nextTrack.filepath.length == 11 && !nextTrack.filepath.contains("/"))) nextTrack.filepath.removePrefix("yt_") else null
                    if (vidId.isNullOrBlank() && dbPath.isNotBlank() && cadId.isNotBlank()) {
                        val authHeader = appContext?.let { com.streamify.app.data.remote.SpotifyAuthManager(it).getYtAuthHeader() } ?: ""
                        vidId = NativeBridge.resolveTrack(dbPath, cadId, nextTrack.isrc, nextTrack.title, nextTrack.artist, authHeader)
                    }

                    val nativeUrl = try {
                        NativeBridge.resolveCdnUrl(vidId, nextTrack.isrc, nextTrack.title, nextTrack.artist)
                    } catch (e: Exception) {
                        null
                    }

                    val finalUrl = if (!nativeUrl.isNullOrBlank()) nativeUrl else {
                        com.streamify.app.data.network.YouTubeStreamResolver.resolveStreamJit(if (vidId != null) nextTrack.copy(ytmVideoId = vidId) else nextTrack).getOrNull()?.streamUrl
                    }

                    if (!finalUrl.isNullOrBlank()) {
                        val warmTrack = nextTrack.copy(filepath = finalUrl, ytmVideoId = vidId ?: nextTrack.ytmVideoId)
                        withContext(Dispatchers.Main) {
                            val currentQ = _playerState.value.queue
                            if (currentIndex + 1 < currentQ.size && (currentQ[currentIndex + 1].id == nextTrack.id || currentQ[currentIndex + 1].title == nextTrack.title)) {
                                val updatedQ = currentQ.toMutableList()
                                updatedQ[currentIndex + 1] = warmTrack
                                _playerState.value = _playerState.value.copy(queue = updatedQ)
                                controller?.let { ctrl ->
                                    val warmItem = buildMediaItem(warmTrack)
                                    if (ctrl.mediaItemCount > 1) {
                                        ctrl.replaceMediaItem(1, warmItem)
                                    } else if (ctrl.mediaItemCount == 1) {
                                        ctrl.addMediaItem(warmItem)
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Ignore background pre-resolve error
                } finally {
                    if (preResolvingTrackKey == trackKey) preResolvingTrackKey = null
                }
            }
        }
    }

    fun toggleAutoPlay() {
        _playerState.value = _playerState.value.copy(isAutoPlayEnabled = !_playerState.value.isAutoPlayEnabled)
    }

    fun playNext(track: Track) {
        val currentQueue = _playerState.value.queue.toMutableList()
        val currentIndex = _playerState.value.currentIndex
        val insertIndex = (currentIndex + 1).coerceAtMost(currentQueue.size)

        currentQueue.add(insertIndex, track)
        _playerState.value = _playerState.value.copy(queue = currentQueue)
        armLookaheadPreBuffer(currentIndex + 1, currentQueue)
    }

    fun addToQueue(track: Track) {
        val currentQueue = _playerState.value.queue.toMutableList()
        currentQueue.add(track)
        _playerState.value = _playerState.value.copy(queue = currentQueue)
        val currentIndex = _playerState.value.currentIndex
        if (currentIndex + 1 == currentQueue.size - 1) {
            armLookaheadPreBuffer(currentIndex + 1, currentQueue)
        }
    }
    
    private fun updateCurrentTrackFromMediaItem(mediaItem: MediaItem?) {
        if (mediaItem == null) return
        
        val mediaId = mediaItem.mediaId
        val metaTitle = mediaItem.mediaMetadata.title?.toString()
        val metaArtist = mediaItem.mediaMetadata.artist?.toString()

        // Match against current logical queue using stable identifier or metadata
        val track = _playerState.value.queue.find { 
            (it.id != 0 && it.id.toString() == mediaId) ||
            "trk_${kotlin.math.abs((it.title.trim().lowercase() + "_" + it.artist.trim().lowercase()).hashCode())}" == mediaId ||
            (metaTitle != null && metaArtist != null && it.title.equals(metaTitle, ignoreCase = true) && it.artist.equals(metaArtist, ignoreCase = true)) ||
            (metaTitle != null && it.title.equals(metaTitle, ignoreCase = true))
        }

        if (track != null) {
            _playerState.value = _playerState.value.copy(
                currentTrack = track,
                duration = if (track.durationSec > 0) track.durationSec * 1000L else _playerState.value.duration
            )
        }
    }

    private fun startPollingPosition() {
        positionPollingJob?.cancel()
        positionPollingJob = viewModelScope.launch {
            var lastTickMs = System.currentTimeMillis()
            var accumulatedPlaySec = 0L
            var lastJamHeartbeatMs = 0L
            while (true) {
                val now = System.currentTimeMillis()
                val elapsedSec = (now - lastTickMs) / 1000L
                if (elapsedSec >= 1L) {
                    lastTickMs = now
                    if (_playerState.value.isPlaying) {
                        accumulatedPlaySec += elapsedSec.coerceIn(1L, 5L)
                        if (accumulatedPlaySec >= 10L) {
                            com.streamify.app.data.YtStatsTelemetryEngine.recordListeningSeconds(accumulatedPlaySec)
                            accumulatedPlaySec = 0L
                        }
                    } else if (accumulatedPlaySec > 0L) {
                        com.streamify.app.data.YtStatsTelemetryEngine.recordListeningSeconds(accumulatedPlaySec)
                        accumulatedPlaySec = 0L
                    }
                }

                controller?.let { ctrl ->
                    val now = System.currentTimeMillis()
                    val curState = _playerState.value
                    val playerDuration = if (ctrl.duration > 0) ctrl.duration else 0L
                    val currentTrack = curState.currentTrack
                    val trackDuration = (currentTrack?.durationSec?.toLong() ?: 0L) * 1000L
                    val finalDuration = if (playerDuration > 0) playerDuration else if (trackDuration > 0) trackDuration else curState.duration
                    
                    val updatedTrack = if (currentTrack != null && currentTrack.durationSec <= 0 && finalDuration > 0) {
                        currentTrack.copy(durationSec = (finalDuration / 1000).toInt())
                    } else currentTrack

                    if (!isOptimisticSeeking) {
                        val ctrlPos = ctrl.currentPosition.coerceAtLeast(0L)
                        if (curState.currentPosition != ctrlPos || curState.duration != finalDuration || curState.currentTrack !== updatedTrack) {
                            _playerState.value = curState.copy(
                                currentPosition = ctrlPos,
                                duration = finalDuration,
                                currentTrack = updatedTrack
                            )
                        }
                    } else {
                        // Safety fallback: Unlatch optimistic seek if engine caught up within 250ms threshold
                        pendingSeekTargetMs?.let { target ->
                            if (kotlin.math.abs(ctrl.currentPosition - target) < 250L) {
                                isOptimisticSeeking = false
                                pendingSeekTargetMs = null
                            }
                        }
                    }

                    // 500ms High-Resolution Jam Lockstep Heartbeat Broadcast (<15ms WebSocket transport)
                    if (curState.isPlaying && !isApplyingJamSync &&
                        com.streamify.app.jam.JamEngine.isHost()   // LOCKSTEP: only the host drives the room clock
                    ) {
                        if (now - lastJamHeartbeatMs >= 500L) {
                            lastJamHeartbeatMs = now
                            com.streamify.app.jam.JamEngine.heartbeatTick(
                                track = curState.currentTrack,
                                positionMs = _playerState.value.currentPosition,
                                isPlaying = true
                            )
                        }
                    }
                }
                delay(200)
            }
        }
    }

    private fun stopPollingPosition() {
        positionPollingJob?.cancel()
    }

    private fun isCdnExpired(url: String): Boolean {
        try {
            val match = Regex("[?&]expire=([0-9]+)").find(url)
            if (match != null) {
                val expireSec = match.groupValues[1].toLongOrNull() ?: return false
                val nowSec = System.currentTimeMillis() / 1000L
                return nowSec >= (expireSec - 300L) // Expired if within 5 min of expiry
            }
        } catch (e: Exception) {
            // ignore parsing error
        }
        return false
    }

    private fun sanitizeStreamUri(rawUrl: String): android.net.Uri {
        return try {
            if (!rawUrl.contains("range=") && !rawUrl.contains("rn=")) {
                android.net.Uri.parse(rawUrl)
            } else {
                val uri = android.net.Uri.parse(rawUrl)
                val cleanBuilder = uri.buildUpon().clearQuery()
                uri.queryParameterNames
                    ?.filterNot { it.equals("range", ignoreCase = true) || it.equals("rn", ignoreCase = true) }
                    ?.forEach { key ->
                        cleanBuilder.appendQueryParameter(key, uri.getQueryParameter(key))
                    }
                cleanBuilder.build()
            }
        } catch (e: Throwable) {
            try {
                android.net.Uri.parse(rawUrl)
            } catch (ex: Throwable) {
                android.net.Uri.EMPTY
            }
        }
    }

    private fun buildMediaItem(t: Track): MediaItem {
        val uri = if (t.filepath.startsWith("http://") || t.filepath.startsWith("https://")) {
            sanitizeStreamUri(t.filepath)
        } else if (t.filepath.startsWith("file://")) {
            android.net.Uri.parse(t.filepath)
        } else if (t.filepath.isNotBlank() && !t.filepath.startsWith("online://") && !t.filepath.startsWith("ytsearch:")) {
            android.net.Uri.fromFile(java.io.File(t.filepath))
        } else {
            android.net.Uri.EMPTY
        }

        val stableMediaId = if (t.id != 0) {
            t.id.toString()
        } else {
            "trk_${kotlin.math.abs((t.title.trim().lowercase() + "_" + t.artist.trim().lowercase()).hashCode())}"
        }

        return MediaItem.Builder()
            .setMediaId(stableMediaId)
            .setUri(uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(t.title)
                    .setArtist(t.artist)
                    .setAlbumTitle(t.album)
                    .setArtworkUri(if (!t.coverArtPath.isNullOrBlank()) {
                        if (t.coverArtPath.startsWith("http") || t.coverArtPath.startsWith("file")) {
                            android.net.Uri.parse(t.coverArtPath)
                        } else {
                            android.net.Uri.fromFile(java.io.File(t.coverArtPath))
                        }
                    } else null)
                    .build()
            )
            .build()
    }

    fun playFromSearch(tappedTrack: Track, searchContext: List<Track> = listOf(tappedTrack)) {
        playTrack(tappedTrack, searchContext.ifEmpty { listOf(tappedTrack) }, autoHydrateRadio = true)
    }

    var isApplyingJamSync: Boolean = false

    fun broadcastJamAction(
        action: String,
        track: Track? = _playerState.value.currentTrack,
        positionMs: Long = _playerState.value.currentPosition,
        isPlaying: Boolean = _playerState.value.isPlaying
    ) {
        if (isApplyingJamSync) return
        if (com.streamify.app.data.remote.SupabaseClient.activeJam.value == null) return
        // Lockstep routing: hosts emit authoritative epochs, members emit
        // policy-checked intents — receiver-side gates enforce authority.
        com.streamify.app.jam.JamEngine.onLocalPlaybackAction(
            action = action, track = track, positionMs = positionMs, isPlaying = isPlaying
        )
    }

    fun playTrack(track: Track, queue: List<Track> = listOf(track), autoHydrateRadio: Boolean = true) {
        val hydratedTrack = repository.hydrateTrack(track)
        val hydratedQueue = queue.map { qTrack ->
            if (qTrack.id == track.id || (qTrack.title.equals(track.title, ignoreCase = true) && qTrack.artist.equals(track.artist, ignoreCase = true))) {
                hydratedTrack
            } else {
                repository.hydrateTrack(qTrack)
            }
        }
        val targetIndex = hydratedQueue.indexOfFirst {
            // Identity-safe matching: a bare title collision must never hijack the
            // queue slot of a different song (different artists, covers, remixes).
            (it.id != 0 && it.id == hydratedTrack.id) ||
                (it.filepath.isNotBlank() && it.filepath == hydratedTrack.filepath) ||
                (it.title == hydratedTrack.title && it.artist == hydratedTrack.artist)
        }.takeIf { it >= 0 } ?: 0

        // 1. Immediately update UI state and pause old track for instantaneous tactile response
        _playerState.value = _playerState.value.copy(
            currentTrack = hydratedTrack,
            currentIndex = targetIndex,
            queue = hydratedQueue,
            isPlaying = true,
            isBuffering = true
        )
        controller?.pause()

        // Broadcast Track Change to active Jam room
        broadcastJamAction("TRACK_CHANGE", track = hydratedTrack, positionMs = 0L, isPlaying = true)

        // 2. Tracked Single Job - cancel previous resolution jobs to prevent race conditions
        playJob?.cancel()
        playJob = viewModelScope.launch {
            // Register track and prime hash deduplication set without clearing history
            sessionPlayedTrackIds.add(hydratedTrack.id)
            val playedH = com.streamify.app.data.FuzzyTitleMatcher.extractRootHash(hydratedTrack.title)
            if (playedH != 0L) processedTitleHashes.add(playedH)

            for (t in hydratedQueue) {
                val h = com.streamify.app.data.FuzzyTitleMatcher.extractRootHash(t.title)
                if (h != 0L) processedTitleHashes.add(h)
                if (t.id != 0) sessionPlayedTrackIds.add(t.id)
            }

            com.streamify.app.data.NeuroQueueManager.onTrackStarted(hydratedTrack)
            playTrackInternal(hydratedTrack, targetIndex, hydratedQueue)

            // Asynchronously hydrate upcoming continuum radio queue seeded directly from the tapped track
            if (autoHydrateRadio) {
                hydrateContinuumRadio(hydratedTrack)
            }
        }
    }



    private fun hydrateContinuumRadio(seedTrack: Track) {
        if (!_playerState.value.isAutoPlayEnabled) return
        hydrateJob?.cancel()
        hydrateJob = viewModelScope.launch(Dispatchers.Default) {
            try {
                val currentQ = _playerState.value.queue

                // Harvest full 25+ candidate batch across Innertube, Spotify, and Local
                val radioTracks = com.streamify.app.data.UniversalCandidateBroker.fetchCandidates(
                    seedTrack = seedTrack,
                    activeQueue = currentQ,
                    targetCount = 25
                )

                if (radioTracks.isNotEmpty()) {
                    // O(1) Root Hash & Session History Deduplication: Skip already played songs
                    val uniqueCandidates = radioTracks.filter { candidate ->
                        val hash = com.streamify.app.data.FuzzyTitleMatcher.extractRootHash(candidate.title)
                        if (hash == 0L || processedTitleHashes.contains(hash) || sessionPlayedTrackIds.contains(candidate.id)) {
                            false
                        } else {
                            processedTitleHashes.add(hash)
                            if (candidate.id != 0) sessionPlayedTrackIds.add(candidate.id)
                            true
                        }
                    }

                    if (uniqueCandidates.isNotEmpty()) {
                        withContext(Dispatchers.Main) {
                            val currentQueue = _playerState.value.queue.toMutableList()
                            for (rt in uniqueCandidates) {
                                val isDup = currentQueue.any {
                                    com.streamify.app.data.FuzzyTitleMatcher.isSameSongVariation(it.title, it.artist, rt.title, rt.artist)
                                }
                                if (!isDup) {
                                    currentQueue.add(rt)
                                }
                            }
                            _playerState.value = _playerState.value.copy(queue = currentQueue)
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }


    private fun handleAutomaticTimelineTransition() {
        seekTimeoutJob?.cancel()
        isOptimisticSeeking = false
        pendingSeekTargetMs = null

        val curState = _playerState.value
        val queue = curState.queue
        val nextIndex = curState.currentIndex + 1
        if (nextIndex < queue.size) {
            val activeTrack = queue[nextIndex]
            _playerState.value = _playerState.value.copy(
                currentTrack = activeTrack,
                currentIndex = nextIndex,
                currentPosition = 0L,
                isPlaying = true,
                isBuffering = false,
                isVideoMode = false
            )
            controller?.let { ctrl ->
                if (ctrl.mediaItemCount > 1) {
                    ctrl.removeMediaItem(0)
                }
            }
            armLookaheadPreBuffer(nextIndex + 1, queue)
        } else {
            advanceQueue(isUserSkip = false)
        }
    }

    fun advanceQueue(isUserSkip: Boolean = false) {
        if (!isAdvancing.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                lookaheadJob?.cancel()

                // JAM LOCKSTEP: room advancement belongs to the host alone.
                // Host pops the shared queue head; guests deliberately idle —
                // personal radio must never hijack a live session.
                if (com.streamify.app.jam.JamEngine.interceptAdvance()) return@launch
                val curState = _playerState.value
                val queue = curState.queue
                if (queue.isEmpty()) return@launch

                // Fast Skip Guard: Only trigger emergency comfort track if queue is exhausted and no upcoming tracks exist
                if (isUserSkip && curState.currentTrack != null && curState.currentIndex + 1 >= queue.size && !curState.isAutoPlayEnabled) {
                    val dwellTime = System.currentTimeMillis() - playbackStartTimeMs
                    if (dwellTime in 1..9999L) {
                        val comfortTrack = withContext(Dispatchers.IO) {
                            repository.getEmergencyComfortTrack()
                        }
                        if (comfortTrack != null && comfortTrack.id != curState.currentTrack?.id) {
                            android.util.Log.d("PlayerViewModel", "⚡ Fast-skip (<10s) on exhausted queue! Triggering comfort anchor: ${comfortTrack.title}")
                            playTrackInternal(comfortTrack, 0, listOf(comfortTrack) + queue.filter { it.id != comfortTrack.id })
                            return@launch
                        }
                    }
                }

                val currentIndex = curState.currentIndex
                val isAutoplayEnabled = curState.isAutoPlayEnabled
                val isRepeatActive = curState.isRepeatActive
                val ctrl = controller

                if (isRepeatActive && ctrl?.repeatMode == Player.REPEAT_MODE_ONE && !isUserSkip) {
                    ctrl.seekTo(0L)
                    ctrl.play()
                    return@launch
                }

                val nextIndex = currentIndex + 1
                when {
                    // Path A: Next track exists within current queue bounds
                    nextIndex < queue.size -> {
                        playTrackInternal(queue[nextIndex], nextIndex, queue)
                    }
                    // Path B: End of queue reached with REPEAT_ALL enabled
                    nextIndex >= queue.size && isRepeatActive && queue.isNotEmpty() -> {
                        playTrackInternal(queue.first(), 0, queue)
                    }
                    // Path C: End of queue reached with AUTOPLAY enabled (Continuum Engine)
                    nextIndex >= queue.size && isAutoplayEnabled && queue.isNotEmpty() -> {
                        _playerState.value = _playerState.value.copy(isBuffering = true)
                        val seedTrack = queue.last()
                        val freshCandidates = withContext(Dispatchers.IO) {
                            com.streamify.app.data.UniversalCandidateBroker.fetchCandidates(
                                seedTrack = seedTrack,
                                activeQueue = queue,
                                targetCount = 15
                            )
                        }
                        if (freshCandidates.isNotEmpty()) {
                            val updatedQueue = queue.toMutableList()
                            for (cand in freshCandidates) {
                                val isDup = updatedQueue.any {
                                    com.streamify.app.data.FuzzyTitleMatcher.isSameSongVariation(it.title, it.artist, cand.title, cand.artist)
                                }
                                if (!isDup) {
                                    updatedQueue.add(cand)
                                }
                            }
                            _playerState.value = _playerState.value.copy(queue = updatedQueue)
                            playTrackInternal(freshCandidates.first(), nextIndex, updatedQueue)
                        } else {
                            ctrl?.pause()
                            _playerState.value = _playerState.value.copy(isPlaying = false, isBuffering = false)
                        }
                    }
                    // Path D: Queue fully exhausted
                    else -> {
                        ctrl?.pause()
                        _playerState.value = _playerState.value.copy(isPlaying = false, isBuffering = false)
                    }
                }
            } finally {
                isAdvancing.set(false)
            }
        }
    }

    /**
     * Single-owner lyric network fetch. Resolves the strongest available YouTube video
     * identity and forwards it to the verified LyricsResolver pipeline (exact pinned
     * video → same-song-gated provider race). Results are persisted to:
     *  1. The canonical app-private LRU cache (shared by every UI surface), and
     *  2. A user-accessible .lrc mirror under Downloads/.Streamify/lyrics (best effort).
     * Attempts are deduplicated per (trackId, videoId): a retry is only permitted when
     * the resolved video identity improves (e.g. after async DB registration pins it).
     */
    private fun maybeFetchLyricsForTrack(playingTrack: Track?) {
        if (playingTrack == null) return
        if (!playingTrack.lyricsPath.isNullOrBlank()) return

        val vid = playingTrack.ytmVideoId
            ?: com.streamify.app.data.network.YouTubeStreamResolver.extractVideoId(
                playingTrack.filepath,
                playingTrack.coverArtPath
            )

        val attemptKey = "${playingTrack.id}:${vid ?: ""}"
        if (!lyricsFetchAttempts.add(attemptKey)) return

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val lyricsText = com.streamify.app.data.network.LyricsResolver.fetchSyncedLyrics(
                    title = playingTrack.title,
                    artist = playingTrack.artist,
                    durationSec = playingTrack.durationSec,
                    videoId = vid ?: ""
                ) ?: ""

                if (lyricsText.isNotBlank() && (lyricsText.contains("[") || lyricsText.length > 40)) {
                    var storedPath: String? = null

                    // 1. Canonical app-private cache — authoritative source for all UI surfaces
                    val ctx = appContext
                    if (ctx != null) {
                        try {
                            val cachedFile = com.streamify.app.data.LyricsCacheManager.getCachedLyricsFile(
                                ctx, playingTrack.title, playingTrack.artist
                            )
                            cachedFile.writeText(lyricsText)
                            storedPath = cachedFile.absolutePath
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }

                    // 2. Optional external mirror for user-accessible .lrc files
                    try {
                        val lyricsDir = java.io.File(
                            android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
                            ".Streamify/lyrics"
                        )
                        if (!lyricsDir.exists()) lyricsDir.mkdirs()
                        val lrcFile = java.io.File(lyricsDir, "${playingTrack.id}.lrc")
                        lrcFile.writeText(lyricsText)
                        if (storedPath == null) storedPath = lrcFile.absolutePath
                    } catch (_: Exception) {
                    }

                    if (storedPath != null) {
                        withContext(Dispatchers.Main) {
                            val cur = _playerState.value.currentTrack
                            if (cur != null && cur.id == playingTrack.id && cur.lyricsPath.isNullOrBlank()) {
                                _playerState.value = _playerState.value.copy(
                                    currentTrack = cur.copy(lyricsPath = storedPath)
                                )
                            }
                        }
                    }
                } else {
                    // Nothing usable found: clear the attempt so a later transition can retry
                    lyricsFetchAttempts.remove(attemptKey)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                lyricsFetchAttempts.remove(attemptKey)
            }
        }
    }

    private suspend fun playTrackInternal(track: Track, index: Int, queue: List<Track>) {
        seekTimeoutJob?.cancel()
        isOptimisticSeeking = false
        pendingSeekTargetMs = null

        _playerState.value = _playerState.value.copy(
            currentTrack = track,
            currentIndex = index,
            currentPosition = 0L,
            queue = queue,
            isPlaying = true,
            isBuffering = true,
            isVideoMode = false
        )
        playbackStartTimeMs = System.currentTimeMillis()

        // 0. SMART OFFLINE VAULT GATE (0ms instant local playback if pre-cached)
        val vaulted = com.streamify.app.data.SmartOfflineVaultEngine.getOfflineTrack(track, appContext)
        val trackToPlay = vaulted ?: track

        // 1. FAST-PATH GATE: If trackToPlay.filepath is already a direct playable local file or unexpired CDN stream
        val isAlreadyDirectCdn = (trackToPlay.filepath.contains("googlevideo.com") || trackToPlay.filepath.contains(".googlevideo.")) &&
                !com.streamify.app.data.network.YouTubeStreamResolver.isCdnExpired(trackToPlay.filepath)
        val isLocalFile = trackToPlay.filepath.startsWith("/") || trackToPlay.filepath.startsWith("file://") || java.io.File(trackToPlay.filepath).exists()

        val resolvedTrack = if (isLocalFile || isAlreadyDirectCdn) {
            trackToPlay
        } else {
            withContext(Dispatchers.IO) {
                val dbPath = appContext?.getDatabasePath("streamify_universal.db")?.absolutePath ?: ""
                val cadId = NativeBridge.generateCadId(trackToPlay.title, trackToPlay.artist, trackToPlay.durationSec)

                var vidId = trackToPlay.ytmVideoId ?: if (trackToPlay.filepath.startsWith("yt_") || (trackToPlay.filepath.length == 11 && !trackToPlay.filepath.contains("/"))) trackToPlay.filepath.removePrefix("yt_") else null
                if (vidId.isNullOrBlank() && dbPath.isNotBlank() && cadId.isNotBlank()) {
                    val authHeader = appContext?.let { com.streamify.app.data.remote.SpotifyAuthManager(it).getYtAuthHeader() } ?: ""
                    vidId = NativeBridge.resolveTrack(dbPath, cadId, trackToPlay.isrc, trackToPlay.title, trackToPlay.artist, authHeader)
                }

                // Tier 1: Native Rust Tokio JIT Stream Resolver
                val nativeUrl = try {
                    NativeBridge.resolveCdnUrl(vidId, trackToPlay.isrc, trackToPlay.title, trackToPlay.artist)
                } catch (e: Exception) {
                    null
                }

                if (!nativeUrl.isNullOrBlank()) {
                    trackToPlay.copy(filepath = nativeUrl, ytmVideoId = vidId ?: trackToPlay.ytmVideoId)
                } else {
                    // Fallback to Kotlin multi-client cascade
                    val res = com.streamify.app.data.network.YouTubeStreamResolver.resolveStreamJit(if (vidId != null) trackToPlay.copy(ytmVideoId = vidId) else trackToPlay)
                    val resolved = res.getOrNull()
                    if (resolved != null && resolved.streamUrl.isNotBlank()) {
                        trackToPlay.copy(filepath = resolved.streamUrl, ytmVideoId = vidId ?: trackToPlay.ytmVideoId)
                    } else {
                        trackToPlay
                    }
                }
            }
        }

        val isDirectStream = resolvedTrack.filepath.startsWith("http") &&
                !resolvedTrack.filepath.contains("youtube.com/watch") &&
                !resolvedTrack.filepath.contains("music.youtube.com") &&
                !resolvedTrack.filepath.startsWith("ytsearch:")
        val isPlayable = isDirectStream ||
                resolvedTrack.filepath.startsWith("file") ||
                java.io.File(resolvedTrack.filepath).exists()

        if (isPlayable) {
            val mediaItem = buildMediaItem(resolvedTrack)
            withContext(Dispatchers.Main) {
                try {
                    controller?.let { ctrl ->
                        ctrl.setMediaItem(mediaItem, 0L)
                        ctrl.prepare()
                        ctrl.play()
                    }
                } catch (e: Throwable) {
                    e.printStackTrace()
                }
                val isCtrlBuffering = controller?.let { it.playbackState == androidx.media3.common.Player.STATE_BUFFERING || it.playbackState == androidx.media3.common.Player.STATE_IDLE } ?: true
                _playerState.value = _playerState.value.copy(
                    currentTrack = resolvedTrack,
                    isBuffering = isCtrlBuffering
                )
            }

            // 2. Arm background lookahead pre-buffer for slot 1 (track N+1)
            armLookaheadPreBuffer(index + 1, queue)

            // 3. Fire-and-Forget Asynchronous Database Registration (Moved OFF critical playback start path)
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val registered = repository.registerStreamedTrack(track, appContext)
                    if (registered.id > 0) {
                        withContext(Dispatchers.Main) {
                            val cur = _playerState.value.currentTrack
                            if (cur != null && cur.title == track.title && cur.artist == track.artist) {
                                val finalVid = registered.ytmVideoId ?: track.ytmVideoId ?: resolvedTrack.ytmVideoId
                                _playerState.value = _playerState.value.copy(
                                    currentTrack = registered.copy(
                                        filepath = resolvedTrack.filepath,
                                        ytmVideoId = finalVid
                                    )
                                )
                                // Exact video identity is now pinned: if the initial lyric
                                // fetch ran without it, this authorized retry upgrades to
                                // the perfectly synced ATV timed lyrics for that upload.
                                maybeFetchLyricsForTrack(_playerState.value.currentTrack)
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        } else {
            android.util.Log.e("PlayerViewModel", "Track stream unresolvable for ${track.title}, auto-skipping")
            withContext(Dispatchers.Main) {
                advanceQueue(isUserSkip = false)
            }
        }
    }

    private fun armLookaheadPreBuffer(nextIndex: Int, queue: List<Track>) {
        if (nextIndex >= queue.size) return
        val nextTrack = queue[nextIndex]
        lookaheadJob?.cancel()
        lookaheadJob = viewModelScope.launch(Dispatchers.IO) {
            // If upcoming queue is low (<= 2 songs remaining), proactively prefetch next radio batch
            if (queue.size - nextIndex <= 2 && _playerState.value.isAutoPlayEnabled) {
                try {
                    val seed = queue.lastOrNull() ?: nextTrack
                    val fresh = com.streamify.app.data.UniversalCandidateBroker.fetchCandidates(
                        seedTrack = seed,
                        activeQueue = queue,
                        targetCount = 15
                    )
                    if (fresh.isNotEmpty()) {
                        withContext(Dispatchers.Main) {
                            val liveQ = _playerState.value.queue.toMutableList()
                            for (ft in fresh) {
                                val isDup = liveQ.any {
                                    com.streamify.app.data.FuzzyTitleMatcher.isSameSongVariation(it.title, it.artist, ft.title, ft.artist)
                                }
                                if (!isDup) {
                                    liveQ.add(ft)
                                }
                            }
                            _playerState.value = _playerState.value.copy(queue = liveQ)
                        }
                    }
                } catch (e: Exception) {
                    // Non-fatal prefetch error
                }
            }

            try {
                // Tier 1: Try Native Rust Tokio JIT Stream Resolver
                val nativeUrl = try {
                    val vidId = nextTrack.ytmVideoId ?: if (nextTrack.filepath.startsWith("yt_") || (nextTrack.filepath.length == 11 && !nextTrack.filepath.contains("/"))) nextTrack.filepath.removePrefix("yt_") else null
                    NativeBridge.resolveCdnUrl(vidId, nextTrack.isrc, nextTrack.title, nextTrack.artist)
                } catch (e: Exception) {
                    null
                }

                val finalUrl = if (!nativeUrl.isNullOrBlank()) nativeUrl else {
                    val res = com.streamify.app.data.network.YouTubeStreamResolver.resolveStreamJit(nextTrack)
                    res.getOrNull()?.streamUrl
                }

                if (!finalUrl.isNullOrBlank()) {
                    val warmTrack = nextTrack.copy(filepath = finalUrl)
                    val lookaheadItem = buildMediaItem(warmTrack)
                    withContext(Dispatchers.Main) {
                        controller?.let { ctrl ->
                            if (ctrl.mediaItemCount == 1) {
                                ctrl.addMediaItem(lookaheadItem)
                            } else if (ctrl.mediaItemCount > 1) {
                                ctrl.replaceMediaItem(1, lookaheadItem)
                            }
                        }
                    }
                }

                appContext?.let { ctx ->
                    try {
                        val upcomingSlice = queue.subList(nextIndex, queue.size)
                        com.streamify.app.service.PredictivePreBufferManager(ctx).preBufferUpcomingTracks(upcomingSlice)
                    } catch (e: Exception) {
                        // Non-fatal pre-buffer error
                    }
                }
            } catch (e: Exception) {
                // Non-fatal background lookahead error
            }
        }
    }

    fun togglePlayPause() {
        val ctrl = controller ?: return
        if (ctrl.isPlaying) {
            ctrl.pause()
            broadcastJamAction("PAUSE", isPlaying = false)
        } else {
            ctrl.play()
            broadcastJamAction("PLAY", isPlaying = true)
        }
    }

    fun play() {
        controller?.play()
        broadcastJamAction("PLAY", isPlaying = true)
    }

    fun pause() {
        controller?.pause()
        broadcastJamAction("PAUSE", isPlaying = false)
    }

    fun setPlaybackSpeed(speed: Float) {
        val ctrl = controller ?: return
        val currentSpeed = ctrl.playbackParameters.speed
        if (kotlin.math.abs(currentSpeed - speed) > 0.001f) {
            ctrl.playbackParameters = androidx.media3.common.PlaybackParameters(speed, 1.0f)
        }
    }

    fun getAcousticPositionMs(): Long {
        val rawPos = controller?.currentPosition ?: _playerState.value.currentPosition
        return com.streamify.app.service.PlaybackService.syncAudioProcessor.getAcousticPositionMs(rawPos)
    }

    fun scheduleAtomicPlayback(
        track: Track,
        targetAtomicTimestampMs: Long,
        startPositionMs: Long = 0L,
        precisionProtocol: com.streamify.app.service.PrecisionTimeProtocol
    ) {
        val ctrl = controller ?: return
        val scheduler = com.streamify.app.service.ScheduledAudioScheduler(ctrl, precisionProtocol)
        _playerState.value = _playerState.value.copy(currentTrack = track, queue = listOf(track))
        scheduler.scheduleAtomicPlayback(track, targetAtomicTimestampMs, startPositionMs) {
            _playerState.value = _playerState.value.copy(isPlaying = true)
        }
    }

    fun seekRelative(deltaMs: Long) {
        val currentPos = _playerState.value.currentPosition
        seekTo(currentPos + deltaMs)
    }

    fun seekTo(positionMs: Long) {
        val ctrl = controller ?: return
        val currentT = _playerState.value.currentTrack
        val maxDurationMs = if (_playerState.value.duration > 0) _playerState.value.duration else ((currentT?.durationSec ?: 0) * 1000L)
        val validPos = if (maxDurationMs > 0) positionMs.coerceIn(0L, maxDurationMs) else positionMs.coerceAtLeast(0L)
        
        // 1. Enter optimistic seeking state to prevent poller snapback
        isOptimisticSeeking = true
        pendingSeekTargetMs = validPos
        _playerState.value = _playerState.value.copy(currentPosition = validPos)

        seekTimeoutJob?.cancel()
        seekTimeoutJob = viewModelScope.launch {
            kotlinx.coroutines.delay(1200)
            isOptimisticSeeking = false
            pendingSeekTargetMs = null
        }

        // 2. Dispatch IPC seek command to ExoPlayer
        ctrl.seekTo(validPos)
        broadcastJamAction("SEEK", positionMs = validPos)
        
        if (currentT != null && currentT.id > 0) {
            NativeBridge.pushTelemetryEvent(NativeBridge.EVENT_SCRUB_SEEK, currentT.id.toLong(), validPos.toFloat())
        }
    }

    fun logLyricsDwell(dwellSeconds: Int) {
        val currentT = _playerState.value.currentTrack
        if (currentT != null && currentT.id > 0 && dwellSeconds > 0) {
            NativeBridge.pushTelemetryEvent(NativeBridge.EVENT_LYRICS_DWELL, currentT.id.toLong(), dwellSeconds.toFloat())
        }
    }

    fun logVolumeFlare() {
        val currentT = _playerState.value.currentTrack
        if (currentT != null && currentT.id > 0) {
            NativeBridge.pushTelemetryEvent(NativeBridge.EVENT_VOLUME_CHANGE, currentT.id.toLong(), 1.0f)
        }
    }
    
    fun skipNext() {
        advanceQueue(isUserSkip = true)
    }
    
    fun skipPrevious() {
        val ctrl = controller
        if (ctrl != null && ctrl.currentPosition > 3000L) {
            ctrl.seekTo(0L)
            return
        }
        viewModelScope.launch {
            val curState = _playerState.value
            val queue = curState.queue
            val currentIndex = curState.currentIndex
            val prevIndex = (currentIndex - 1).coerceAtLeast(0)
            if (queue.isNotEmpty() && prevIndex != currentIndex) {
                playTrackInternal(queue[prevIndex], prevIndex, queue)
            } else {
                ctrl?.seekTo(0L)
            }
        }
    }
    
    fun toggleShuffle() {
        val ctrl = controller ?: return
        ctrl.shuffleModeEnabled = !ctrl.shuffleModeEnabled
    }
    
    fun toggleRepeat() {
        val ctrl = controller ?: return
        ctrl.repeatMode = if (ctrl.repeatMode == Player.REPEAT_MODE_OFF) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
    }
    
    fun toggleLike(trackToToggle: Track? = null, context: android.content.Context? = null) {
        val currentTrack = trackToToggle ?: _playerState.value.currentTrack ?: return

        // 1. Launch authoritative DB toggle operation
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val actualIsLiked = repository.toggleLike(currentTrack.id, track = currentTrack)
                withContext(Dispatchers.Main) {
                    val updatedQueue = _playerState.value.queue.map { item ->
                        if ((item.id != 0 && item.id == currentTrack.id) || 
                            (item.filepath.isNotBlank() && item.filepath == currentTrack.filepath) ||
                            (item.title.equals(currentTrack.title, ignoreCase = true) && item.artist.equals(currentTrack.artist, ignoreCase = true))) {
                            item.copy(isLiked = actualIsLiked)
                        } else item
                    }
                    val updatedCurrent = if (_playerState.value.currentTrack?.let { 
                        (it.id != 0 && it.id == currentTrack.id) || 
                        (it.filepath.isNotBlank() && it.filepath == currentTrack.filepath) ||
                        (it.title.equals(currentTrack.title, ignoreCase = true) && it.artist.equals(currentTrack.artist, ignoreCase = true))
                    } == true) {
                        _playerState.value.currentTrack?.copy(isLiked = actualIsLiked)
                    } else {
                        _playerState.value.currentTrack
                    }

                    _playerState.value = _playerState.value.copy(
                        queue = updatedQueue,
                        currentTrack = updatedCurrent
                    )

                    val msg = if (actualIsLiked) "Added to Liked Songs" else "Removed from Liked Songs"
                    UiEventBus.emitEvent(UiEvent.ShowSnackbar(msg))
                }

                if (actualIsLiked && currentTrack.filepath.isNotBlank()) {
                    com.streamify.app.service.AudioCacheManager.markStickyTrack(currentTrack.filepath)
                }

                // Auto-download liked online songs if setting is enabled
                if (actualIsLiked && (currentTrack.filepath.startsWith("http") || currentTrack.source.contains("online", ignoreCase = true))) {
                    try {
                        com.streamify.app.viewmodel.IngestionViewModel.enqueueDownloadDirect(
                            url = if (currentTrack.filepath.startsWith("http")) currentTrack.filepath else "https://www.youtube.com/watch?v=${currentTrack.id}",
                            title = currentTrack.title,
                            artist = currentTrack.artist,
                            album = "Streamify",
                            quality = "320"
                        )
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun removeFromQueue(trackId: Int) {
        val curState = _playerState.value
        val currentQueue = curState.queue.toMutableList()
        val index = currentQueue.indexOfFirst { it.id == trackId }
        if (index != -1) {
            currentQueue.removeAt(index)
            val newCurrentIndex = when {
                index < curState.currentIndex -> curState.currentIndex - 1
                index == curState.currentIndex -> curState.currentIndex.coerceAtMost(currentQueue.size - 1)
                else -> curState.currentIndex
            }
            _playerState.value = curState.copy(
                queue = currentQueue,
                currentIndex = newCurrentIndex.coerceAtLeast(0)
            )
            try {
                controller?.removeMediaItem(index)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun reorderQueue(fromIndex: Int, toIndex: Int) {
        val curState = _playerState.value
        val currentQueue = curState.queue.toMutableList()
        if (fromIndex in currentQueue.indices && toIndex in currentQueue.indices && fromIndex != toIndex) {
            val item = currentQueue.removeAt(fromIndex)
            currentQueue.add(toIndex, item)
            val oldCurrent = curState.currentIndex
            val newCurrentIndex = when {
                oldCurrent == fromIndex -> toIndex
                fromIndex < oldCurrent && toIndex >= oldCurrent -> oldCurrent - 1
                fromIndex > oldCurrent && toIndex <= oldCurrent -> oldCurrent + 1
                else -> oldCurrent
            }
            _playerState.value = curState.copy(
                queue = currentQueue,
                currentIndex = newCurrentIndex
            )
            try {
                controller?.moveMediaItem(fromIndex, toIndex)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun clearQueue() {
        _playerState.value = _playerState.value.copy(queue = emptyList())
        try {
            controller?.clearMediaItems()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun startSongRadio(seedTrack: Track? = null) {
        val currentT = _playerState.value.currentTrack
        val target = seedTrack ?: currentT ?: return
        val isCurrentPlaying = currentT != null && (target.id == currentT.id || (target.title == currentT.title && target.artist == currentT.artist))

        viewModelScope.launch {
            val radioTracks = com.streamify.app.data.UniversalCandidateBroker.fetchCandidates(
                seedTrack = target,
                activeQueue = if (isCurrentPlaying) _playerState.value.queue else emptyList(),
                targetCount = 20
            )
            if (radioTracks.isNotEmpty()) {
                if (isCurrentPlaying) {
                    // Seamlessly append to active queue without interrupting or restarting current track timestamp!
                    val currentQueue = _playerState.value.queue
                    val existingIds = currentQueue.map { it.id }.toSet()
                    val existingTitles = currentQueue.map { "${it.title}_${it.artist}".lowercase() }.toSet()
                    val newTracks = radioTracks.filter {
                        (it.id == 0 || !existingIds.contains(it.id)) &&
                                !existingTitles.contains("${it.title}_${it.artist}".lowercase())
                    }

                    if (newTracks.isNotEmpty()) {
                        val updatedQueue = currentQueue + newTracks
                        _playerState.value = _playerState.value.copy(queue = updatedQueue)
                        val newMediaItems = newTracks.map { buildMediaItem(it) }
                        controller?.addMediaItems(newMediaItems)
                        UiEventBus.emitEvent(UiEvent.ShowSnackbar("Appended ${newTracks.size} Radio tracks to queue 📻"))
                    } else {
                        UiEventBus.emitEvent(UiEvent.ShowSnackbar("Radio tracks already in queue"))
                    }
                } else {
                    val fullList = listOf(target) + radioTracks.filter { it.id != target.id }
                    playTrack(target, fullList)
                    UiEventBus.emitEvent(UiEvent.ShowSnackbar("Started ${target.title} Radio 📻"))
                }
            } else {
                UiEventBus.emitEvent(UiEvent.ShowSnackbar("Could not load radio for this track"))
            }
        }
    }

    fun setSleepTimer(minutes: Int?, endOfTrack: Boolean = false) {
        sleepTimerJob?.cancel()
        _playerState.value = _playerState.value.copy(
            sleepTimerMinutesLeft = minutes,
            sleepTimerEndTrack = endOfTrack
        )
        if (minutes != null) {
            sleepTimerJob = viewModelScope.launch {
                var remaining = minutes
                while (remaining > 0) {
                    delay(60000L) // 1 minute
                    remaining--
                    _playerState.value = _playerState.value.copy(sleepTimerMinutesLeft = remaining)
                }
                controller?.pause()
                _playerState.value = _playerState.value.copy(sleepTimerMinutesLeft = null, sleepTimerEndTrack = false)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        com.streamify.app.service.PlaybackService.onSeekNextListener = null
        com.streamify.app.service.PlaybackService.onSeekPrevListener = null
        controller?.removeListener(playerListener)
        controller = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
    }

}

