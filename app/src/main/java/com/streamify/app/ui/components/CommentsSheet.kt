package com.streamify.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.streamify.app.data.models.Track
import com.streamify.app.data.remote.TrackComment
import com.streamify.app.ui.theme.StreamifyColors
import com.streamify.app.ui.theme.StreamifyDimens
import com.streamify.app.ui.theme.StreamifyType
import com.streamify.app.util.DurationFormatter
import com.streamify.app.viewmodel.CommunityViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommentsSheet(
    track: Track?,
    currentPositionMs: Long,
    communityViewModel: CommunityViewModel,
    onSeekTo: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    val state by communityViewModel.uiState.collectAsState()
    var commentInput by remember { mutableStateOf("") }

    LaunchedEffect(track) {
        communityViewModel.loadCommentsForTrack(track)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = StreamifyColors.BgElevated,
        dragHandle = { BottomSheetDefaults.DragHandle(color = StreamifyColors.TextDimmed) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.75f)
                .padding(horizontal = StreamifyDimens.SpaceMD)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        "Live Song Reactions",
                        style = StreamifyType.HeadlineMedium,
                        color = StreamifyColors.TextMain
                    )
                    Text(
                        "${state.currentTrackComments.size} comments on this track",
                        style = StreamifyType.Caption,
                        color = StreamifyColors.TextSub
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = StreamifyColors.TextSub)
                }
            }

            Spacer(modifier = Modifier.height(StreamifyDimens.SpaceMD))

            // Comments List
            if (state.isCommentsLoading) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = StreamifyColors.Primary, modifier = Modifier.size(32.dp))
                }
            } else if (state.currentTrackComments.isEmpty()) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("💬", fontSize = 40.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("No comments yet", style = StreamifyType.BodyLarge, color = StreamifyColors.TextMain)
                        Text("Be the first to react at ${DurationFormatter.formatMs(currentPositionMs)}!", style = StreamifyType.Caption, color = StreamifyColors.TextSub)
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(state.currentTrackComments) { comment ->
                        CommentItem(
                            comment = comment,
                            currentPosMs = currentPositionMs,
                            onTimestampClick = { onSeekTo(comment.timestampMs) }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(StreamifyDimens.SpaceSM))

            // Input Bar with Timestamp pill
            Surface(
                color = StreamifyColors.BgCard,
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = StreamifyDimens.SpaceLG)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Timestamp Badge
                    Surface(
                        color = StreamifyColors.Primary.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = DurationFormatter.formatMs(currentPositionMs),
                            style = StreamifyType.CaptionBold,
                            color = StreamifyColors.Primary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    TextField(
                        value = commentInput,
                        onValueChange = { commentInput = it },
                        placeholder = { Text("Add reaction at ${DurationFormatter.formatMs(currentPositionMs)}...", color = StreamifyColors.TextDimmed, style = StreamifyType.BodySmall) },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedTextColor = StreamifyColors.TextMain,
                            unfocusedTextColor = StreamifyColors.TextMain,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        ),
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )

                    IconButton(
                        onClick = {
                            if (commentInput.isNotBlank()) {
                                communityViewModel.postComment(track, currentPositionMs, commentInput) {
                                    commentInput = ""
                                }
                            }
                        },
                        enabled = commentInput.isNotBlank()
                    ) {
                        Icon(
                            Icons.Default.Send,
                            contentDescription = "Send",
                            tint = if (commentInput.isNotBlank()) StreamifyColors.Primary else StreamifyColors.TextDimmed
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CommentItem(
    comment: TrackComment,
    currentPosMs: Long,
    onTimestampClick: () -> Unit
) {
    val isNearCurrentTime = kotlin.math.abs(comment.timestampMs - currentPosMs) < 3000

    Surface(
        color = if (isNearCurrentTime) StreamifyColors.Primary.copy(alpha = 0.12f) else StreamifyColors.BgCard,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            // Avatar
            if (comment.userAvatar.isNotBlank()) {
                AsyncImage(
                    model = comment.userAvatar,
                    contentDescription = null,
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(StreamifyColors.PrimaryDark),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        comment.userName.take(1).uppercase(),
                        style = StreamifyType.CaptionBold,
                        color = StreamifyColors.TextMain
                    )
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(comment.userName, style = StreamifyType.BodySmallBold, color = StreamifyColors.TextMain)
                    
                    // Clickable Timestamp Badge
                    Surface(
                        color = StreamifyColors.BgElevated,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.clickable { onTimestampClick() }
                    ) {
                        Text(
                            DurationFormatter.formatMs(comment.timestampMs),
                            style = StreamifyType.CaptionBold,
                            color = StreamifyColors.Primary,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))
                Text(comment.commentText, style = StreamifyType.BodyMedium, color = StreamifyColors.TextSub)
            }
        }
    }
}
