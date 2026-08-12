package com.streamify.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.streamify.app.data.models.Track
import com.streamify.app.ui.components.TrackListItem
import com.streamify.app.ui.theme.StreamifyColors
import com.streamify.app.ui.theme.StreamifyDimens
import com.streamify.app.ui.theme.StreamifyType

@Composable
fun QueueScreen(
    nowPlaying: Track?,
    upNext: List<Track>,
    onTrackClick: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(StreamifyColors.BgBase)
            .padding(top = StreamifyDimens.SpaceGiant)
    ) {
        Text(
            text = "Now Playing",
            style = StreamifyType.HeadlineMedium,
            color = StreamifyColors.TextMain,
            modifier = Modifier.padding(horizontal = StreamifyDimens.SpaceLG, vertical = StreamifyDimens.SpaceMD)
        )

        if (nowPlaying != null) {
            TrackListItem(
                track = nowPlaying,
                isPlaying = true,
                onClick = { },
                onOptionsClick = { }
            )
        }

        Spacer(modifier = Modifier.height(StreamifyDimens.SpaceLG))

        Text(
            text = "Next In Queue",
            style = StreamifyType.HeadlineMedium,
            color = StreamifyColors.TextMain,
            modifier = Modifier.padding(horizontal = StreamifyDimens.SpaceLG, vertical = StreamifyDimens.SpaceMD)
        )

        LazyColumn(
            contentPadding = PaddingValues(bottom = StreamifyDimens.PlayerBarHeight + StreamifyDimens.SpaceXL)
        ) {
            itemsIndexed(upNext) { _, track ->
                TrackListItem(
                    track = track,
                    isPlaying = false,
                    onClick = { onTrackClick(track.id) },
                    onOptionsClick = { }
                )
            }
        }
    }
}
