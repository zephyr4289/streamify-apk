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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PlayerState(
    val currentTrack: Track? = null,
    val queue: List<Track> = emptyList(),
    val isPlaying: Boolean = false,
    val currentPosition: Long = 0,
    val duration: Long = 0,
    val isShuffleActive: Boolean = false,
    val isRepeatActive: Boolean = false,
    val sleepTimerMinutesLeft: Int? = null,
    val sleepTimerEndTrack: Boolean = false,
    val isAutoPlayEnabled: Boolean = true,
    val isVideoMode: Boolean = false
)

class PlayerViewModel(private val repository: TrackRepository = TrackRepository) : ViewModel() {
    private val _playerState = MutableStateFlow(PlayerState())
    val playerState: StateFlow<PlayerState> = _playerState.asStateFlow()
    val currentTrack: StateFlow<Track?> = _playerState
        .map { it.currentTrack }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var appContext: Context? = null
    
    private var positionPollingJob: Job? = null
    private var sleepTimerJob: Job? = null
    private var lastPlayedTrackId: Int? = null
    private var preResolvingTrackKey: String? = null

    fun getController(): MediaController? = controller

    fun toggleVideoMode(enabled: Boolean) {
        _playerState.value = _playerState.value.copy(isVideoMode = enabled)
        val ctrl = controller ?: return
        val currentT = _playerState.value.currentTrack ?: return
        val currentPos = ctrl.currentPosition

        if (enabled) {
            viewModelScope.launch(Dispatchers.IO) {
                val videoStream = com.streamify.app.data.network.YouTubeStreamResolver.resolveVideoStreamUrl(currentT.filepath, currentT.coverArtPath)
                if (videoStream != null && videoStream.streamUrl.isNotBlank()) {
                    withContext(Dispatchers.Main) {
                        val currentItem = ctrl.currentMediaItem ?: return@withContext
                        val videoMediaItem = currentItem.buildUpon()
                            .setUri(android.net.Uri.parse(videoStream.streamUrl))
                            .setMimeType(androidx.media3.common.MimeTypes.APPLICATION_MP4)
                            .build()
                        ctrl.setMediaItem(videoMediaItem, currentPos)
                        ctrl.prepare()
                        ctrl.play()
                    }
                }
            }
        } else {
            viewModelScope.launch(Dispatchers.IO) {
                val audioStream = com.streamify.app.data.network.YouTubeStreamResolver.resolveTrackStream(currentT)
                val audioUrl = audioStream?.streamUrl ?: currentT.filepath
                if (audioUrl.isNotBlank()) {
                    withContext(Dispatchers.Main) {
                        val currentItem = ctrl.currentMediaItem ?: return@withContext
                        val audioMediaItem = currentItem.buildUpon()
                            .setUri(android.net.Uri.parse(audioUrl))
                            .setMimeType(audioStream?.mimeType ?: androidx.media3.common.MimeTypes.AUDIO_WEBM)
                            .build()
                        ctrl.setMediaItem(audioMediaItem, currentPos)
                        ctrl.prepare()
                        ctrl.play()
                    }
                }
            }
        }
    }

    fun initialize(context: Context) {
        appContext = context.applicationContext
        repository.appContext = context.applicationContext
        if (controllerFuture != null) return

        val sessionToken = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
        controllerFuture?.addListener({
            controller = controllerFuture?.get()
            setupController(context)
            restorePlayerState(context)
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
                        
                        withContext(kotlinx.coroutines.Dispatchers.Main) {
                            playTrack(currentTrack, tracks)
                            controller?.seekTo(position)
                            controller?.pause()
                        }
                    }
                }
            }
        }
    }

    private fun setupController(context: Context) {
        val ctrl = controller ?: return
        
        ctrl.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _playerState.value = _playerState.value.copy(isPlaying = isPlaying)
                if (isPlaying) startPollingPosition() else stopPollingPosition()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                val d = ctrl.duration
                if (d > 0) {
                    val curr = _playerState.value.currentTrack
                    val updated = if (curr != null && curr.durationSec <= 0) {
                        curr.copy(durationSec = (d / 1000).toInt())
                    } else curr
                    _playerState.value = _playerState.value.copy(
                        duration = d,
                        currentPosition = ctrl.currentPosition.coerceAtLeast(0L),
                        currentTrack = updated
                    )
                }
            }

            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                val d = ctrl.duration
                if (d > 0) {
                    val curr = _playerState.value.currentTrack
                    val updated = if (curr != null && curr.durationSec <= 0) {
                        curr.copy(durationSec = (d / 1000).toInt())
                    } else curr
                    _playerState.value = _playerState.value.copy(
                        duration = d,
                        currentPosition = ctrl.currentPosition.coerceAtLeast(0L),
                        currentTrack = updated
                    )
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                updateCurrentTrackFromMediaItem(mediaItem)
                
                val currentT = _playerState.value.currentTrack
                val newTrackId = mediaItem?.mediaId?.toIntOrNull()
                if (currentT != null) {
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
                                    trackId = currentT.id.toString(),
                                    trackTitle = currentT.title,
                                    trackArtist = currentT.artist,
                                    audioPath = currentT.filepath
                                )
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                } else if (newTrackId != null && newTrackId > 0) {
                    viewModelScope.launch {
                        repository.updateSessionVector(newTrackId, 0.45f)
                        repository.recordTrackPlay(newTrackId)
                    }
                }
                
                // Project Chronos AI & Circadian Event Logging: Track change
                if (lastPlayedTrackId != null && newTrackId != null && lastPlayedTrackId != newTrackId) {
                    val wasSkipped = reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK || 
                                     reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO
                    val currentHour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
                    val posSec = (ctrl.currentPosition / 1000L).toInt()
                    val durSec = if (ctrl.duration > 0) (ctrl.duration / 1000L).toInt() else (_playerState.value.currentTrack?.durationSec ?: 0)
                    val ratio = if (durSec > 0) (posSec.toFloat() / durSec.toFloat()).coerceIn(0f, 1f) else 0.5f

                    // Real-Time Cloud Telemetry Sync
                    val currentTrackObj = _playerState.value.currentTrack
                    if (currentTrackObj != null && posSec > 5) {
                        viewModelScope.launch(Dispatchers.IO) {
                            try {
                                val cleanSig = (currentTrackObj.title.trim().lowercase() + "_" + currentTrackObj.artist.trim().lowercase())
                                val cloudId = "trk_${kotlin.math.abs(cleanSig.hashCode())}"
                                val eventJson = org.json.JSONObject().apply {
                                    put("track_id", cloudId)
                                    put("duration_sec", posSec)
                                    put("completion_ratio", ratio)
                                    put("hour_of_day", currentHour)
                                }
                                com.streamify.app.data.remote.SupabaseClient.ingestTelemetryBatch(listOf(eventJson))
                            } catch (e: Exception) {
                                // Safe fallback
                            }
                        }
                    }

                    NativeBridge.pushTelemetryEvent(NativeBridge.EVENT_PLAY_TRANSITION, lastPlayedTrackId!!.toLong(), newTrackId.toFloat())
                    viewModelScope.launch {
                        if (wasSkipped && ctrl.currentPosition < 10000) {
                            repository.logSkipEvent(lastPlayedTrackId!!, newTrackId)
                        } else {
                            repository.logPlayEvent(lastPlayedTrackId!!, newTrackId)
                        }
                        repository.logEngagementEvent(lastPlayedTrackId!!, posSec, ratio, currentHour)
                        repository.recordTrackCooccurrence(lastPlayedTrackId!!, newTrackId)
                    }
                }
                lastPlayedTrackId = newTrackId
                savePlayerState(context)
                
                // Auto-Fetch Lyrics if missing using ResilientMediaRouter (Kotlin LRCLIB/NetEase racer first, lazy Python fallback)
                val playingTrack = _playerState.value.currentTrack
                if (playingTrack != null && playingTrack.id > 0 && playingTrack.lyricsPath.isNullOrBlank()) {
                    viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        try {
                            val lyricsText = com.streamify.app.data.network.ResilientMediaRouter.fetchWithFallback<String>(
                                timeoutMs = 2500L,
                                primary = {
                                    com.streamify.app.data.network.LyricsResolver.fetchSyncedLyrics(
                                        playingTrack.title,
                                        playingTrack.artist,
                                        playingTrack.durationSec
                                    )
                                },
                                fallback = {
                                    val pyRes = com.streamify.app.data.network.PythonEngine.executeFallback(
                                        "download_engine.lyrics",
                                        "fetch_lyrics",
                                        playingTrack.title,
                                        playingTrack.artist,
                                        playingTrack.durationSec
                                    ) { pyObj -> pyObj.toString() }
                                    pyRes.getOrNull()
                                }
                            ) ?: ""

                            if (lyricsText.isNotBlank() && (lyricsText.contains("[") || lyricsText.length > 20)) {
                                val lyricsDir = java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), ".Streamify/lyrics")
                                if (!lyricsDir.exists()) lyricsDir.mkdirs()
                                
                                val lrcFile = java.io.File(lyricsDir, "${playingTrack.id}.lrc")
                                lrcFile.writeText(lyricsText)
                                
                                withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    val updatedTrack = playingTrack.copy(lyricsPath = lrcFile.absolutePath)
                                    _playerState.value = _playerState.value.copy(currentTrack = updatedTrack)
                                }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
                
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
                    ctrl.pause()
                    _playerState.value = _playerState.value.copy(sleepTimerEndTrack = false, sleepTimerMinutesLeft = null)
                }

                // CONTINUUM INFINITE RADIO: When approaching the end of the queue (or queue size <= 2), fetch next radio batch
                if (_playerState.value.isAutoPlayEnabled) {
                    val currentQueue = _playerState.value.queue
                    val currentIdx = currentQueue.indexOfFirst {
                        (it.id != 0 && it.id.toString() == mediaItem?.mediaId) || it.filepath == mediaItem?.mediaId || it.title == mediaItem?.mediaMetadata?.title
                    }.takeIf { it >= 0 } ?: (ctrl.currentMediaItemIndex)

                    if (currentIdx >= currentQueue.size - 2) {
                        viewModelScope.launch(Dispatchers.IO) {
                            val currentT = _playerState.value.currentTrack
                            if (currentT != null) {
                                // 1. Query Infinite Continuum Radio Engine (Innertube continuation + O(1) deduplication)
                                val continuumRecs = com.streamify.app.data.ContinuumRadioEngine.fetchNextRadioBatch(currentT, limit = 15)
                                if (continuumRecs.isNotEmpty()) {
                                    val newQueue = _playerState.value.queue.toMutableList()
                                    val newMediaItems = mutableListOf<MediaItem>()
                                    for (rec in continuumRecs) {
                                        val isDup = newQueue.any {
                                            com.streamify.app.data.FuzzyTitleMatcher.isSameSongVariation(it.title, it.artist, rec.title, rec.artist)
                                        }
                                        if (!isDup) {
                                            newQueue.add(rec)
                                            newMediaItems.add(buildMediaItem(rec))
                                        }
                                    }
                                    if (newMediaItems.isNotEmpty()) {
                                        withContext(kotlinx.coroutines.Dispatchers.Main) {
                                            _playerState.value = _playerState.value.copy(queue = newQueue)
                                            ctrl.addMediaItems(newMediaItems)
                                        }
                                    }
                                } else {
                                    // 2. Fallback to on-device C++ embeddings if offline
                                    var validId = currentT.id
                                    if (validId <= 0 && currentT.filepath.isNotBlank()) {
                                        validId = com.streamify.app.data.NativeBridge.insertTrack(
                                            filepath = currentT.filepath,
                                            title = currentT.title,
                                            artist = currentT.artist,
                                            album = currentT.album,
                                            durationSec = currentT.durationSec,
                                            bpm = currentT.bpm
                                        ).toInt()
                                    }
                                    if (validId > 0) {
                                        val recentHistory = currentQueue.takeLast(20).map { it.id }.toIntArray()
                                        val localRecs = repository.getRecommendations(validId, recentHistory, 1, 5)
                                        if (localRecs.isNotEmpty()) {
                                            val newQueue = _playerState.value.queue.toMutableList()
                                            val newMediaItems = mutableListOf<MediaItem>()
                                            for (rec in localRecs) {
                                                if (!newQueue.any { it.id == rec.id }) {
                                                    newQueue.add(rec)
                                                    newMediaItems.add(buildMediaItem(rec))
                                                }
                                            }
                                            if (newMediaItems.isNotEmpty()) {
                                                withContext(kotlinx.coroutines.Dispatchers.Main) {
                                                    _playerState.value = _playerState.value.copy(queue = newQueue)
                                                    ctrl.addMediaItems(newMediaItems)
                                                }
                                            }
                                        }
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
                if (_playerState.value.isAutoPlayEnabled) {
                    val currentIndex = ctrl.currentMediaItemIndex
                    val totalItems = ctrl.mediaItemCount
                    if (totalItems > 0 && totalItems - currentIndex <= 2) {
                        viewModelScope.launch(Dispatchers.IO) {
                            val currentT = _playerState.value.currentTrack
                            val newTracks = com.streamify.app.data.ContinuumRadioEngine.ensureQueueDepth(
                                currentQueueSize = totalItems - currentIndex,
                                seedTrack = currentT
                            )
                            if (newTracks.isNotEmpty()) {
                                withContext(Dispatchers.Main) {
                                    val currentQ = _playerState.value.queue.toMutableList()
                                    val newMediaItems = mutableListOf<MediaItem>()
                                    for (track in newTracks) {
                                        val isDup = currentQ.any {
                                            com.streamify.app.data.FuzzyTitleMatcher.isSameSongVariation(it.title, it.artist, track.title, track.artist)
                                        }
                                        if (!isDup) {
                                            currentQ.add(track)
                                            newMediaItems.add(buildMediaItem(track))
                                        }
                                    }
                                    if (newMediaItems.isNotEmpty()) {
                                        _playerState.value = _playerState.value.copy(queue = currentQ)
                                        ctrl.addMediaItems(newMediaItems)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
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
                                        ctrl.replaceMediaItem(idx, mediaItem)
                                        ctrl.seekTo(idx, 0L)
                                        ctrl.prepare()
                                        ctrl.play()
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
            }
        })
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
                    val resolved = com.streamify.app.data.network.YouTubeStreamResolver.resolveStreamJit(nextTrack).getOrNull()
                    if (resolved != null && resolved.streamUrl.isNotBlank()) {
                        val warmTrack = nextTrack.copy(filepath = resolved.streamUrl)
                        withContext(Dispatchers.Main) {
                            val currentQ = _playerState.value.queue
                            if (currentIndex + 1 < currentQ.size && (currentQ[currentIndex + 1].id == nextTrack.id || currentQ[currentIndex + 1].title == nextTrack.title)) {
                                val updatedQ = currentQ.toMutableList()
                                updatedQ[currentIndex + 1] = warmTrack
                                _playerState.value = _playerState.value.copy(queue = updatedQ)
                                controller?.replaceMediaItem(currentIndex + 1, buildMediaItem(warmTrack))
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
        val ctrl = controller ?: return
        val currentQueue = _playerState.value.queue.toMutableList()
        val currentIndex = ctrl.currentMediaItemIndex.coerceAtLeast(0)
        val insertIndex = (currentIndex + 1).coerceAtMost(currentQueue.size)

        currentQueue.add(insertIndex, track)
        _playerState.value = _playerState.value.copy(queue = currentQueue)
        ctrl.addMediaItem(insertIndex, buildMediaItem(track))
    }

    fun addToQueue(track: Track) {
        val ctrl = controller ?: return
        val currentQueue = _playerState.value.queue.toMutableList()
        currentQueue.add(track)
        _playerState.value = _playerState.value.copy(queue = currentQueue)
        ctrl.addMediaItem(buildMediaItem(track))
    }
    
    private fun updateCurrentTrackFromMediaItem(mediaItem: MediaItem?) {
        if (mediaItem == null) {
            _playerState.value = _playerState.value.copy(currentTrack = null)
            return
        }
        
        // Find in queue
        val track = _playerState.value.queue.find { 
            it.id.toString() == mediaItem.mediaId || it.filepath == mediaItem.mediaId
        } ?: _playerState.value.queue.firstOrNull()

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
            while (true) {
                val now = System.currentTimeMillis()
                val elapsedSec = (now - lastTickMs) / 1000L
                if (elapsedSec >= 1L) {
                    lastTickMs = now
                    if (_playerState.value.isPlaying) {
                        com.streamify.app.data.YtStatsTelemetryEngine.recordListeningSeconds(elapsedSec.coerceIn(1L, 5L))
                    }
                }

                controller?.let { ctrl ->
                    val curState = _playerState.value
                    val playerDuration = if (ctrl.duration > 0) ctrl.duration else 0L
                    val currentTrack = curState.currentTrack
                    val trackDuration = (currentTrack?.durationSec?.toLong() ?: 0L) * 1000L
                    val finalDuration = if (playerDuration > 0) playerDuration else if (trackDuration > 0) trackDuration else curState.duration
                    
                    val updatedTrack = if (currentTrack != null && currentTrack.durationSec <= 0 && finalDuration > 0) {
                        currentTrack.copy(durationSec = (finalDuration / 1000).toInt())
                    } else currentTrack

                    val newPos = ctrl.currentPosition.coerceAtLeast(0L)
                    if (curState.currentPosition != newPos || curState.duration != finalDuration || curState.currentTrack !== updatedTrack) {
                        _playerState.value = curState.copy(
                            currentPosition = newPos,
                            duration = finalDuration,
                            currentTrack = updatedTrack
                        )
                    }

                    // 30-Second Predictive Lookahead Pre-Resolver for 0ms Gapless Playback
                    if (curState.isPlaying && finalDuration > 0L) {
                        val remainingMs = finalDuration - newPos
                        val progressFraction = newPos.toFloat() / finalDuration.toFloat()
                        if (remainingMs <= 30000L || progressFraction >= 0.75f) {
                            val currentIdx = ctrl.currentMediaItemIndex
                            preResolveLookaheadTrack(currentIdx, curState.queue)
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

    private fun buildMediaItem(t: Track): MediaItem {
        val uri = if (t.filepath.startsWith("http://") || t.filepath.startsWith("https://")) {
            android.net.Uri.parse(t.filepath)
        } else if (t.filepath.startsWith("file://")) {
            android.net.Uri.parse(t.filepath)
        } else if (t.filepath.isNotBlank() && !t.filepath.startsWith("online://")) {
            android.net.Uri.fromFile(java.io.File(t.filepath))
        } else {
            android.net.Uri.EMPTY
        }

        return MediaItem.Builder()
            .setMediaId(if (t.id != 0) t.id.toString() else t.filepath)
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
        viewModelScope.launch {
            // 1. Play tapped track immediately with 0ms delay
            playTrack(tappedTrack, listOf(tappedTrack))

            // 2. Asynchronously fetch YouTube Innertube Algorithmic Radio (RDAMVM...)
            val radioTracks = com.streamify.app.data.ContinuumRadioEngine.startRadio(tappedTrack)
            if (radioTracks.isNotEmpty()) {
                withContext(Dispatchers.Main) {
                    val currentQ = _playerState.value.queue.toMutableList()
                    val newMediaItems = mutableListOf<MediaItem>()
                    for (rt in radioTracks) {
                        val isDup = currentQ.any {
                            com.streamify.app.data.FuzzyTitleMatcher.isSameSongVariation(it.title, it.artist, rt.title, rt.artist)
                        }
                        if (!isDup) {
                            currentQ.add(rt)
                            newMediaItems.add(buildMediaItem(rt))
                        }
                    }
                    if (newMediaItems.isNotEmpty()) {
                        _playerState.value = _playerState.value.copy(queue = currentQ)
                        controller?.addMediaItems(newMediaItems)
                    }
                }
            }
        }
    }

    fun validateAndPrepareTrackForPlayback(
        track: Track,
        onReady: (Track) -> Unit,
        onFailure: (Throwable) -> Unit = {}
    ) {
        val needsResolution = track.filepath.isBlank() ||
                track.filepath.startsWith("online://") ||
                track.filepath.startsWith("ytsearch:") ||
                (track.filepath.startsWith("http") && !track.filepath.contains("googlevideo.com")) ||
                (track.filepath.startsWith("http") && track.filepath.contains("googlevideo.com") && isCdnExpired(track.filepath)) ||
                (!track.filepath.startsWith("http") && !track.filepath.startsWith("file") && !java.io.File(track.filepath).exists())

        if (needsResolution) {
            viewModelScope.launch(Dispatchers.IO) {
                val result = com.streamify.app.data.network.YouTubeStreamResolver.resolveStreamJit(track)
                val resolved = result.getOrNull()
                if (resolved != null && resolved.streamUrl.isNotBlank()) {
                    val playableTrack = track.copy(filepath = resolved.streamUrl)
                    withContext(Dispatchers.Main) {
                        onReady(playableTrack)
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        val ex = result.exceptionOrNull() ?: Exception("Unresolvable track stream")
                        onFailure(ex)
                    }
                }
            }
        } else {
            onReady(track)
        }
    }

    fun playTrack(track: Track, queue: List<Track> = listOf(track)) {
        _playerState.value = _playerState.value.copy(currentTrack = track, queue = queue)
        validateAndPrepareTrackForPlayback(
            track = track,
            onReady = { playableTrack ->
                val updatedQueue = queue.map {
                    if (it.id == track.id || (it.title == track.title && it.artist == track.artist)) playableTrack else it
                }
                executePlayback(playableTrack, updatedQueue)
            },
            onFailure = { error ->
                android.util.Log.e("PlayerViewModel", "Playback failed for ${track.title}: ${error.message}")
            }
        )
    }

    private fun executePlayback(track: Track, queue: List<Track>) {
        _playerState.value = _playerState.value.copy(currentTrack = track, queue = queue)

        // Auto-register streamed track into SQLite & Streamify Playlist & AI Vector store
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val registered = repository.registerStreamedTrack(track, appContext)
                if (registered.id > 0) {
                    withContext(Dispatchers.Main) {
                        val cur = _playerState.value.currentTrack
                        if (cur != null && cur.title == track.title && cur.artist == track.artist) {
                            _playerState.value = _playerState.value.copy(currentTrack = registered)
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        val mediaItems = queue.map { buildMediaItem(it) }

        controller?.apply {
            setMediaItems(mediaItems)
            val startIndex = queue.indexOfFirst {
                (it.id != 0 && it.id == track.id) || (it.filepath.isNotBlank() && it.filepath == track.filepath) || it.title == track.title
            }.takeIf { it >= 0 } ?: 0
            seekTo(startIndex, C.TIME_UNSET)
            prepare()
            play()
        }
    }

    fun togglePlayPause() {
        val ctrl = controller ?: return
        if (ctrl.isPlaying) ctrl.pause() else ctrl.play()
    }

    fun play() {
        controller?.play()
    }

    fun pause() {
        controller?.pause()
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

    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs)
        _playerState.value = _playerState.value.copy(currentPosition = positionMs)
        
        val currentT = _playerState.value.currentTrack
        if (currentT != null && currentT.id > 0) {
            NativeBridge.pushTelemetryEvent(NativeBridge.EVENT_SCRUB_SEEK, currentT.id.toLong(), positionMs.toFloat())
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
        controller?.seekToNextMediaItem()
    }
    
    fun skipPrevious() {
        controller?.seekToPreviousMediaItem()
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
        val track = trackToToggle ?: _playerState.value.currentTrack ?: return

        val newIsLiked = !track.isLiked
        val updatedQueue = _playerState.value.queue.map { item ->
            if ((item.id != 0 && item.id == track.id) || (item.filepath.isNotBlank() && item.filepath == track.filepath)) {
                item.copy(isLiked = newIsLiked)
            } else item
        }
        val updatedCurrent = if (_playerState.value.currentTrack?.let { 
            (it.id != 0 && it.id == track.id) || (it.filepath.isNotBlank() && it.filepath == track.filepath) 
        } == true) {
            _playerState.value.currentTrack?.copy(isLiked = newIsLiked)
        } else {
            _playerState.value.currentTrack
        }

        _playerState.value = _playerState.value.copy(
            queue = updatedQueue,
            currentTrack = updatedCurrent
        )

        viewModelScope.launch {
            val msg = if (newIsLiked) "Added to Liked Songs" else "Removed from Liked Songs"
            UiEventBus.emitEvent(UiEvent.ShowSnackbar(msg))
        }

        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            repository.toggleLike(track.id, track = track)
            if (newIsLiked && track.filepath.isNotBlank()) {
                com.streamify.app.service.AudioCacheManager.markStickyTrack(track.filepath)
            }
        }

        // Auto-download liked online songs if setting is enabled
        if (newIsLiked && (track.filepath.startsWith("http") || track.source.contains("online", ignoreCase = true))) {
            try {
                com.streamify.app.viewmodel.IngestionViewModel.enqueueDownloadDirect(
                    url = if (track.filepath.startsWith("http")) track.filepath else "https://www.youtube.com/watch?v=${track.id}",
                    title = track.title,
                    artist = track.artist,
                    album = "Streamify",
                    quality = "320"
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun removeFromQueue(trackId: Int) {
        val currentQueue = _playerState.value.queue.toMutableList()
        val index = currentQueue.indexOfFirst { it.id == trackId }
        if (index != -1) {
            currentQueue.removeAt(index)
            _playerState.value = _playerState.value.copy(queue = currentQueue)
            try {
                controller?.removeMediaItem(index)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun reorderQueue(fromIndex: Int, toIndex: Int) {
        val currentQueue = _playerState.value.queue.toMutableList()
        if (fromIndex in currentQueue.indices && toIndex in currentQueue.indices && fromIndex != toIndex) {
            val item = currentQueue.removeAt(fromIndex)
            currentQueue.add(toIndex, item)
            _playerState.value = _playerState.value.copy(queue = currentQueue)
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
            val radioTracks = repository.getCloudSongRadio(target, limit = 25)
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
        controllerFuture?.let { MediaController.releaseFuture(it) }
    }
}

