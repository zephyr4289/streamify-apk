package com.streamify.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.streamify.app.data.remote.CommunityPlaylist
import com.streamify.app.ui.theme.StreamifyColors
import com.streamify.app.ui.theme.StreamifyDimens
import com.streamify.app.ui.theme.StreamifyType
import com.streamify.app.viewmodel.CommunityViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommunityHubScreen(
    communityViewModel: CommunityViewModel,
    onBack: () -> Unit,
    onPlaylistClick: (CommunityPlaylist) -> Unit
) {
    val state by communityViewModel.uiState.collectAsState()
    var selectedGenre by remember { mutableStateOf("All") }
    val genres = listOf("All", "Top Hits", "Hip-Hop", "Chill Lo-Fi", "Electronic", "Focus", "Rock", "Workout")

    LaunchedEffect(Unit) {
        communityViewModel.loadCommunityFeed()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Community Hub", style = StreamifyType.HeadlineMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = StreamifyColors.TextMain)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = StreamifyColors.BgBase)
            )
        },
        containerColor = StreamifyColors.BgBase
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Genre Filter Chips
            LazyRow(
                contentPadding = PaddingValues(horizontal = StreamifyDimens.SpaceLG),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(vertical = 8.dp)
            ) {
                items(genres) { genre ->
                    val isSelected = selectedGenre == genre
                    Surface(
                        color = if (isSelected) StreamifyColors.Primary else StreamifyColors.BgCard,
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.clickable { selectedGenre = genre }
                    ) {
                        Text(
                            text = genre,
                            style = if (isSelected) StreamifyType.BodyMediumBold else StreamifyType.BodyMedium,
                            color = if (isSelected) StreamifyColors.BgBase else StreamifyColors.TextMain,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Playlists List
            if (state.isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = StreamifyColors.Primary)
                }
            } else if (state.communityPlaylists.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("🎶", fontSize = 48.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("No community playlists yet", style = StreamifyType.HeadlineSmall, color = StreamifyColors.TextMain)
                        Text("Publish your custom playlist from Library to see it here!", style = StreamifyType.Caption, color = StreamifyColors.TextSub)
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = StreamifyDimens.SpaceLG, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    items(state.communityPlaylists) { playlist ->
                        CommunityPlaylistCard(
                            playlist = playlist,
                            onClick = { onPlaylistClick(playlist) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CommunityPlaylistCard(
    playlist: CommunityPlaylist,
    onClick: () -> Unit
) {
    Surface(
        color = StreamifyColors.BgCard,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Artwork / Cover
            if (playlist.coverUrl.isNotBlank()) {
                AsyncImage(
                    model = playlist.coverUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(12.dp))
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(StreamifyColors.BgElevated),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.QueueMusic,
                        contentDescription = null,
                        tint = StreamifyColors.Primary,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    playlist.name,
                    style = StreamifyType.BodyLargeBold,
                    color = StreamifyColors.TextMain,
                    maxLines = 1
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    playlist.description,
                    style = StreamifyType.Caption,
                    color = StreamifyColors.TextSub,
                    maxLines = 1
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "by ${playlist.creatorName}",
                        style = StreamifyType.CaptionBold,
                        color = StreamifyColors.Primary
                    )
                    Text(" • ${playlist.trackCount} tracks", style = StreamifyType.Caption, color = StreamifyColors.TextDimmed)
                }
            }

            // Like / Clone action
            Surface(
                color = StreamifyColors.BgElevated,
                shape = CircleShape
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Favorite, contentDescription = null, tint = StreamifyColors.Primary, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("${playlist.likesCount}", style = StreamifyType.CaptionBold, color = StreamifyColors.TextMain)
                }
            }
        }
    }
}
