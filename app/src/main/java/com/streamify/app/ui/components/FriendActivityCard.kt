package com.streamify.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.streamify.app.data.remote.FriendActivity
import com.streamify.app.ui.theme.StreamifyColors
import com.streamify.app.ui.theme.StreamifyDimens
import com.streamify.app.ui.theme.StreamifyType

@Composable
fun FriendActivityCard(
    friend: FriendActivity,
    modifier: Modifier = Modifier
) {
    Surface(
        color = StreamifyColors.BgCard,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.width(200.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (friend.avatarUrl.isNotBlank()) {
                    AsyncImage(
                        model = friend.avatarUrl,
                        contentDescription = null,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(StreamifyColors.PrimaryDark),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            friend.displayName.take(1).uppercase(),
                            style = StreamifyType.BodyMediumBold,
                            color = StreamifyColors.TextMain
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        friend.displayName,
                        style = StreamifyType.BodySmallBold,
                        color = StreamifyColors.TextMain,
                        maxLines = 1
                    )
                    Text(
                        friend.lastActiveAt,
                        style = StreamifyType.Caption,
                        color = StreamifyColors.Primary,
                        maxLines = 1
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.GraphicEq,
                    contentDescription = null,
                    tint = StreamifyColors.Primary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Column {
                    Text(
                        friend.trackTitle,
                        style = StreamifyType.CaptionBold,
                        color = StreamifyColors.TextMain,
                        maxLines = 1
                    )
                    Text(
                        friend.trackArtist,
                        style = StreamifyType.Caption,
                        color = StreamifyColors.TextSub,
                        maxLines = 1
                    )
                }
            }
        }
    }
}
