package com.streamify.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import com.streamify.app.ui.animations.cardPressEffect
import com.streamify.app.ui.theme.StreamifyColors
import com.streamify.app.ui.theme.StreamifyDimens
import com.streamify.app.ui.theme.StreamifyShapes
import com.streamify.app.ui.theme.StreamifyType

@Composable
fun RecentPlayCard(
    title: String,
    imageUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .height(StreamifyDimens.RecentCardHeight)
            .clip(StreamifyShapes.CardShape)
            .background(StreamifyColors.BgCard)
            .cardPressEffect(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TrackCoverArt(
            coverArtPath = imageUrl,
            title = title,
            artist = "",
            modifier = Modifier.size(StreamifyDimens.RecentCardArt),
            shape = StreamifyShapes.CardShape
        )
        Spacer(modifier = Modifier.width(StreamifyDimens.SpaceSM))
        Text(
            text = title,
            style = StreamifyType.TitleSmall,
            color = StreamifyColors.TextMain,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(end = StreamifyDimens.SpaceSM)
        )
    }
}

