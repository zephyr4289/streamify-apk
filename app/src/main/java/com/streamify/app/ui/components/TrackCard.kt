package com.streamify.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import coil.compose.AsyncImage
import com.streamify.app.data.models.Track
import com.streamify.app.ui.animations.cardPressEffect
import com.streamify.app.ui.theme.StreamifyColors
import com.streamify.app.ui.theme.StreamifyDimens
import com.streamify.app.ui.theme.StreamifyShapes
import com.streamify.app.ui.theme.StreamifyType

@Composable
fun TrackCard(
    track: Track,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .width(StreamifyDimens.CardWidth)
            .cardPressEffect(onClick = onClick)
    ) {
        AsyncImage(
            model = track.coverArtPath,
            contentDescription = "Cover for ${track.title}",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(StreamifyDimens.CardArtSize)
                .clip(StreamifyShapes.CardShape)
        )
        Spacer(modifier = Modifier.height(StreamifyDimens.SpaceSM))
        Text(
            text = track.title,
            style = StreamifyType.CardTitle,
            color = StreamifyColors.TextMain,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(StreamifyDimens.SpaceXXS))
        Text(
            text = track.artist,
            style = StreamifyType.CardSubtitle,
            color = StreamifyColors.TextSub,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}
