package com.streamify.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streamify.app.data.PlaylistRepository
import com.streamify.app.data.models.Track
import com.streamify.app.ui.components.ContextMenuSheet
import com.streamify.app.ui.components.YtPlaylistHeroHeader
import com.streamify.app.ui.components.YtQueueTrackItem
import com.streamify.app.ui.theme.*
import com.streamify.app.viewmodel.PlayerViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun AlbumScreen(
    albumName: String,
    allTracks: List<Track>,
    playerViewModel: PlayerViewModel,
    onBack: () -> Unit,
    onTrackClick: (Track, List<Track>) -> Unit,
    explicitTracks: List<Track>? = null,
    headerTitle: String? = null,
    headerSubtitle: String? = null
) {
    val context = LocalContext.current
    val albumTracks = remember(albumName, allTracks, explicitTracks) {
        explicitTracks ?: allTracks.filter { it.album.equals(albumName, ignoreCase = true) }
    }
    val displayTitle = headerTitle ?: albumName
    val currentTrack by playerViewModel.currentTrack.collectAsState()
    var selectedOptionsTrack by remember { mutableStateOf<Track?>(null) }
    val firstTrack = albumTracks.firstOrNull()

    val listState = rememberLazyListState()

    // 120fps GPU Parallax Calculator
    val scrollOffset by remember {
        derivedStateOf {
            if (listState.firstVisibleItemIndex == 0) {
                listState.firstVisibleItemScrollOffset.toFloat()
            } else {
                1000f // Fully collapsed
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgBase)
            .statusBarsPadding()
    ) {
        // Top Toolbar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = TextMain,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = if (explicitTracks != null) "Playlist" else "Album",
                style = LocalAppTypography.current.headlineMedium.copy(fontSize = 18.sp),
                color = TextMain
            )
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 120.dp) // Protect docked player
        ) {
            // 1. Parallax Collapsing Hero Header
            item(key = "album_hero_header", contentType = "heroHeader") {
                val displaySubtitle = headerSubtitle ?: "Album • ${firstTrack?.artist ?: "Unknown Artist"} • ${albumTracks.size} songs"
                YtPlaylistHeroHeader(
                    title = displayTitle,
                    subtitle = displaySubtitle,
                    artworkUrl = firstTrack?.coverArtPath,
                    scrollOffset = scrollOffset,
                    onPlay = {
                        if (albumTracks.isNotEmpty()) {
                            onTrackClick(albumTracks.first(), albumTracks)
                        }
                    },
                    onShuffle = {
                        if (albumTracks.isNotEmpty()) {
                            val shuffled = albumTracks.shuffled()
                            onTrackClick(shuffled.first(), shuffled)
                        }
                    },
                    onExportM3u = {
                        CoroutineScope(Dispatchers.Main).launch {
                            val file = PlaylistRepository.exportPlaylistToM3U8("album_$albumName", albumTracks, context)
                            if (file != null) {
                                android.widget.Toast.makeText(context, "Exported: ${file.name}", android.widget.Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                )
            }

            // 2. Tracklist Items
            items(
                items = albumTracks,
                key = { "album_track_${it.id}" },
                contentType = { "trackRow" }
            ) { track ->
                YtQueueTrackItem(
                    track = track,
                    isPlaying = currentTrack?.id == track.id,
                    showDragHandle = false,
                    onClick = { onTrackClick(track, albumTracks) },
                    onMoreClick = { selectedOptionsTrack = track }
                )
            }
        }
    }

    // Context Options Menu Bottom Sheet
    selectedOptionsTrack?.let { track ->
        ContextMenuSheet(
            track = track,
            onDismissRequest = { selectedOptionsTrack = null },
            onLikeClick = {
                playerViewModel.toggleLike(track)
                selectedOptionsTrack = null
            },
            onAddToPlaylistClick = { selectedOptionsTrack = null },
            onAddToQueueClick = {
                playerViewModel.addToQueue(track)
                selectedOptionsTrack = null
            }
        )
    }
}
