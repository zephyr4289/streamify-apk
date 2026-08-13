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
import com.streamify.app.data.TrackRepository
import com.streamify.app.data.models.Track
import com.streamify.app.service.PlaybackService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    val isAutoPlayEnabled: Boolean = true
)

class PlayerViewModel(private val repository: TrackRepository = TrackRepository) : ViewModel() {
    private val _playerState = MutableStateFlow(PlayerState())
    val playerState: StateFlow<PlayerState> = _playerState.asStateFlow()
    val currentTrack: StateFlow<Track?> = _playerState
        .map { it.currentTrack }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    
    private var positionPollingJob: Job? = null
    private var sleepTimerJob: Job? = null
    private var lastPlayedTrackId: Int? = null

    fun initialize(context: Context) {
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

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                updateCurrentTrackFromMediaItem(mediaItem)
                
                // AI Event Logging: Track change
                val newTrackId = mediaItem?.mediaId?.toIntOrNull()
                if (lastPlayedTrackId != null && newTrackId != null && lastPlayedTrackId != newTrackId) {
                    val wasSkipped = reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK || 
                                     reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO
                    viewModelScope.launch {
                        if (wasSkipped && ctrl.currentPosition < 10000) {
                            repository.logSkipEvent(lastPlayedTrackId!!, newTrackId)
                        } else {
                            repository.logPlayEvent(lastPlayedTrackId!!, newTrackId)
                        }
                    }
                }
                lastPlayedTrackId = newTrackId
                savePlayerState(context)
                
                // Auto-Fetch Lyrics if missing using Chaquopy robust engine
                val currentT = _playerState.value.currentTrack
                if (currentT != null && currentT.id > 0 && currentT.lyricsPath.isNullOrBlank()) {
                    viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        try {
                            val lyricsText = try {
                                val py = com.chaquo.python.Python.getInstance()
                                val lyricsModule = py.getModule("download_engine.lyrics")
                                lyricsModule.callAttr("fetch_lyrics", currentT.title, currentT.artist, currentT.durationSec).toString()
                            } catch (e: Exception) {
                                ""
                            }

                            if (lyricsText.isNotBlank()) {
                                val lyricsDir = java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), ".Streamify/lyrics")
                                if (!lyricsDir.exists()) lyricsDir.mkdirs()
                                
                                val lrcFile = java.io.File(lyricsDir, "${currentT.id}.lrc")
                                lrcFile.writeText(lyricsText)
                                
                                withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    val updatedTrack = currentT.copy(lyricsPath = lrcFile.absolutePath)
                                    _playerState.value = _playerState.value.copy(currentTrack = updatedTrack)
                                }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
                
                if (_playerState.value.sleepTimerEndTrack && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                    ctrl.pause()
                    _playerState.value = _playerState.value.copy(sleepTimerEndTrack = false, sleepTimerMinutesLeft = null)
                }

                // NEURAL INFINITY RADIO: If we reached the last song in the queue (and auto-play is enabled), fetch recommendations
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && _playerState.value.isAutoPlayEnabled) {
                    val currentQueue = _playerState.value.queue
                    val currentIdx = currentQueue.indexOfFirst { it.id.toString() == mediaItem?.mediaId }
                    if (currentIdx >= 0 && currentIdx == currentQueue.size - 1) {
                        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            val currentT = _playerState.value.currentTrack
                            if (currentT != null) {
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
                                    if (validId > 0 && !currentT.coverArtPath.isNullOrBlank()) {
                                        com.streamify.app.data.NativeBridge.updateTrackCoverArt(validId, currentT.coverArtPath!!)
                                    }
                                }
                                if (validId > 0) {
                                    val recentHistory = currentQueue.takeLast(20).map { it.id }.toIntArray()
                                    val recs = repository.getRecommendations(validId, recentHistory, 1, 5)
                                    if (recs.isNotEmpty()) {
                                        val newQueue = currentQueue.toMutableList()
                                        val newMediaItems = mutableListOf<MediaItem>()
                                        for (rec in recs) {
                                            if (!newQueue.any { it.id == rec.id }) {
                                                newQueue.add(rec)
                                                newMediaItems.add(
                                                    MediaItem.Builder()
                                                        .setMediaId(rec.id.toString())
                                                        .setUri(rec.filepath)
                                                        .setMediaMetadata(
                                                            MediaMetadata.Builder()
                                                                .setTitle(rec.title)
                                                                .setArtist(rec.artist)
                                                                .setAlbumTitle(rec.album)
                                                                .setArtworkUri(android.net.Uri.parse(rec.coverArtPath ?: ""))
                                                                .build()
                                                        ).build()
                                                )
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
            
            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                _playerState.value = _playerState.value.copy(isShuffleActive = shuffleModeEnabled)
            }
            
            override fun onRepeatModeChanged(repeatMode: Int) {
                _playerState.value = _playerState.value.copy(isRepeatActive = repeatMode != Player.REPEAT_MODE_OFF)
            }
        })
    }
    
    fun toggleAutoPlay() {
        _playerState.value = _playerState.value.copy(isAutoPlayEnabled = !_playerState.value.isAutoPlayEnabled)
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
            while (true) {
                controller?.let {
                    val actualDuration = if (it.duration > 0) it.duration else _playerState.value.duration
                    _playerState.value = _playerState.value.copy(
                        currentPosition = it.currentPosition,
                        duration = actualDuration
                    )
                }
                delay(200)
            }
        }
    }

    private fun stopPollingPosition() {
        positionPollingJob?.cancel()
    }

    fun playTrack(track: Track, queue: List<Track> = listOf(track)) {
        _playerState.value = _playerState.value.copy(queue = queue)
        
        val mediaItems = queue.map { t ->
            val uri = if (t.filepath.startsWith("http://") || t.filepath.startsWith("https://")) {
                android.net.Uri.parse(t.filepath)
            } else if (t.filepath.startsWith("file://")) {
                android.net.Uri.parse(t.filepath)
            } else if (t.filepath.isNotBlank()) {
                android.net.Uri.fromFile(java.io.File(t.filepath))
            } else {
                android.net.Uri.EMPTY
            }

            MediaItem.Builder()
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

    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs)
        _playerState.value = _playerState.value.copy(currentPosition = positionMs)
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
            val msg = if (newIsLiked) "Added to Liked Songs ❤️" else "Removed from Liked Songs"
            UiEventBus.emitEvent(UiEvent.ShowSnackbar(msg))
        }

        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            repository.toggleLike(track.id, track = track)
        }
    }

    fun addToQueue(track: Track) {
        val currentQueue = _playerState.value.queue
        if (!currentQueue.contains(track)) {
            _playerState.value = _playerState.value.copy(
                queue = currentQueue + track
            )
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

    fun reorderQueue(fromIndex: Int, toIndex: Int) {
        val currentQueue = _playerState.value.queue.toMutableList()
        if (fromIndex in currentQueue.indices && toIndex in currentQueue.indices) {
            val item = currentQueue.removeAt(fromIndex)
            currentQueue.add(toIndex, item)
            _playerState.value = _playerState.value.copy(queue = currentQueue)
            
            // Re-sync media items in controller
            val mediaItems = currentQueue.map { t ->
                MediaItem.Builder()
                    .setMediaId(t.id.toString())
                    .setUri(t.filepath)
                    .build()
            }
            controller?.setMediaItems(mediaItems)
        }
    }

    override fun onCleared() {
        super.onCleared()
        controllerFuture?.let { MediaController.releaseFuture(it) }
    }
}
