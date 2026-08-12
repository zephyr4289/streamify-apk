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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PlayerState(
    val currentTrack: Track? = null,
    val queue: List<Track> = emptyList(),
    val isPlaying: Boolean = false,
    val currentPosition: Long = 0,
    val duration: Long = 0,
    val isShuffleActive: Boolean = false,
    val isRepeatActive: Boolean = false
)

class PlayerViewModel(private val repository: TrackRepository = TrackRepository()) : ViewModel() {
    private val _playerState = MutableStateFlow(PlayerState())
    val playerState: StateFlow<PlayerState> = _playerState.asStateFlow()

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    
    private var positionPollingJob: Job? = null
    private var lastPlayedTrackId: Int? = null

    fun initialize(context: Context) {
        if (controllerFuture != null) return

        val sessionToken = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
        controllerFuture?.addListener({
            controller = controllerFuture?.get()
            setupController()
        }, MoreExecutors.directExecutor())
    }

    private fun setupController() {
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
            }
            
            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                _playerState.value = _playerState.value.copy(isShuffleActive = shuffleModeEnabled)
            }
            
            override fun onRepeatModeChanged(repeatMode: Int) {
                _playerState.value = _playerState.value.copy(isRepeatActive = repeatMode != Player.REPEAT_MODE_OFF)
            }
        })
    }
    
    private fun updateCurrentTrackFromMediaItem(mediaItem: MediaItem?) {
        if (mediaItem == null) {
            _playerState.value = _playerState.value.copy(currentTrack = null)
            return
        }
        
        // Find in queue
        val track = _playerState.value.queue.find { it.id.toString() == mediaItem.mediaId }
        if (track != null) {
            _playerState.value = _playerState.value.copy(
                currentTrack = track,
                duration = track.durationSec * 1000L
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
                delay(1000)
            }
        }
    }

    private fun stopPollingPosition() {
        positionPollingJob?.cancel()
    }

    fun playTrack(track: Track, queue: List<Track> = listOf(track)) {
        _playerState.value = _playerState.value.copy(queue = queue)
        
        val mediaItems = queue.map { t ->
            MediaItem.Builder()
                .setMediaId(t.id.toString())
                .setUri(t.filepath)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(t.title)
                        .setArtist(t.artist)
                        .setAlbumTitle(t.album)
                        .setArtworkUri(android.net.Uri.parse(t.coverArtPath ?: ""))
                        .build()
                )
                .build()
        }
        
        controller?.apply {
            setMediaItems(mediaItems)
            val startIndex = queue.indexOfFirst { it.id == track.id }.takeIf { it >= 0 } ?: 0
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
    
    fun toggleLike(trackToToggle: Track? = null) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val track = trackToToggle ?: _playerState.value.currentTrack ?: return@launch
            repository.toggleLike(track.id)
            
            // If the toggled track is the currently playing one, update the player state optimistically
            if (_playerState.value.currentTrack?.id == track.id) {
                val updatedTrack = track.copy(isLiked = !track.isLiked)
                _playerState.value = _playerState.value.copy(currentTrack = updatedTrack)
            }
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

    override fun onCleared() {
        super.onCleared()
        controllerFuture?.let { MediaController.releaseFuture(it) }
    }
}
