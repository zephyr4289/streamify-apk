package com.streamify.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import coil.compose.AsyncImage
import com.streamify.app.data.models.Track
import com.streamify.app.ui.theme.StreamifyColors
import com.streamify.app.ui.theme.StreamifyDimens
import com.streamify.app.ui.theme.StreamifyShapes
import com.streamify.app.ui.theme.StreamifyType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContextMenuSheet(
    track: Track,
    onDismissRequest: () -> Unit,
    onLikeClick: () -> Unit,
    onAddToPlaylistClick: () -> Unit,
    onAddToQueueClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = StreamifyColors.BgElevated,
        shape = StreamifyShapes.BottomSheet,
        dragHandle = { BottomSheetDefaults.DragHandle(color = StreamifyColors.TextDimmed) }
    ) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(bottom = StreamifyDimens.SpaceXL) // Navigation bar padding
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(StreamifyDimens.SpaceLG),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AsyncImage(
                    model = track.coverArtPath,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(StreamifyShapes.CardShape)
                )
                Spacer(modifier = Modifier.width(StreamifyDimens.SpaceMD))
                Column {
                    Text(
                        text = track.title,
                        style = StreamifyType.TitleLarge,
                        color = StreamifyColors.TextMain,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = track.artist,
                        style = StreamifyType.BodyMedium,
                        color = StreamifyColors.TextSub,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Divider(color = StreamifyColors.Divider, thickness = 1.dp)

            // Actions
            ContextActionItem(
                icon = Icons.Filled.Favorite,
                text = if (track.isLiked) "Remove from Liked Songs" else "Like",
                iconTint = if (track.isLiked) StreamifyColors.Primary else StreamifyColors.TextSub,
                onClick = {
                    onLikeClick()
                    onDismissRequest()
                }
            )
            ContextActionItem(
                icon = Icons.Filled.Add,
                text = "Add to Playlist",
                onClick = {
                    onAddToPlaylistClick()
                    onDismissRequest()
                }
            )
            ContextActionItem(
                icon = Icons.Filled.QueueMusic,
                text = "Add to Queue",
                onClick = {
                    onAddToQueueClick()
                    onDismissRequest()
                }
            )
            ContextActionItem(
                icon = Icons.Filled.Download,
                text = "Download",
                onClick = {
                    // Navigate to download screen or trigger download service
                    onDismissRequest()
                }
            )
            ContextActionItem(
                icon = Icons.Filled.Share,
                text = "Share",
                onClick = {
                    // Trigger system share sheet
                    onDismissRequest()
                }
            )
        }
    }
}

@Composable
private fun ContextActionItem(
    icon: ImageVector,
    text: String,
    iconTint: androidx.compose.ui.graphics.Color = StreamifyColors.TextSub,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(
                horizontal = StreamifyDimens.SpaceLG,
                vertical = StreamifyDimens.SpaceMD
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = text,
            tint = iconTint,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(StreamifyDimens.SpaceLG))
        Text(
            text = text,
            style = StreamifyType.TitleMedium,
            color = StreamifyColors.TextMain
        )
    }
}
