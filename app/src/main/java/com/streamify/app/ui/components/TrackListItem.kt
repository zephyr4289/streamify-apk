package com.streamify.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import coil.compose.AsyncImage
import androidx.compose.ui.unit.dp
import com.streamify.app.data.models.Track
import com.streamify.app.ui.theme.StreamifyColors
import com.streamify.app.ui.theme.StreamifyDimens
import com.streamify.app.ui.theme.StreamifyShapes
import com.streamify.app.ui.theme.StreamifyType

@Composable
fun TrackListItem(
    track: Track,
    onClick: () -> Unit,
    onOptionsClick: () -> Unit,
    modifier: Modifier = Modifier,
    isPlaying: Boolean = false
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(StreamifyDimens.TrackRowHeight)
            .clickable(onClick = onClick)
            .padding(horizontal = StreamifyDimens.SpaceLG),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = track.coverArtPath,
            contentDescription = "Cover for ${track.title}",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(StreamifyDimens.TrackRowArt)
                .clip(StreamifyShapes.MiniPlayerShape)
        )
        Spacer(modifier = Modifier.width(StreamifyDimens.SpaceMD))
        
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title,
                style = StreamifyType.TitleMedium,
                color = if (isPlaying) StreamifyColors.Primary else StreamifyColors.TextMain,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = track.artist,
                    style = StreamifyType.BodyMedium,
                    color = StreamifyColors.TextSub,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (track.isProcessed) {
                    Spacer(modifier = Modifier.width(StreamifyDimens.SpaceXS))
                    androidx.compose.material3.Surface(
                        color = StreamifyColors.Primary.copy(alpha = 0.2f),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = "AI ⚡",
                            style = StreamifyType.Caption,
                            color = StreamifyColors.Primary,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                }
            }
        }
        
        IconButton(onClick = onOptionsClick) {
            Icon(
                imageVector = Icons.Filled.MoreVert,
                contentDescription = "More options",
                tint = StreamifyColors.TextSub
            )
        }
    }
}
