package com.streamify.app.ui.screens

import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streamify.app.data.models.LyricsLine
import com.streamify.app.ui.components.YtLyricLineItem
import com.streamify.app.ui.components.YtLyricsHeader
import com.streamify.app.ui.theme.*

@Composable
fun LyricsScreen(
    lyrics: List<LyricsLine>,
    currentPositionMs: Long,
    dominantColor: Color = BgBase,
    onSeek: (Long) -> Unit,
    onClose: (() -> Unit)? = null
) {
    val listState = rememberLazyListState()

    // 1. Calculate Active Index Mathematically
    val activeIndex = remember(currentPositionMs, lyrics) {
        val idx = lyrics.indexOfLast { it.timeMs <= currentPositionMs }
        if (idx >= 0) idx else 0
    }

    // 2. 120fps Mathematical Focal Auto-Scroll Engine (35% from top)
    LaunchedEffect(activeIndex) {
        if (lyrics.isNotEmpty() && activeIndex in lyrics.indices && !listState.isScrollInProgress) {
            val viewportHeight = listState.layoutInfo.viewportSize.height
            if (viewportHeight > 0) {
                val focalOffset = viewportHeight * 0.35f
                val itemInfo = listState.layoutInfo.visibleItemsInfo.find { it.index == activeIndex }
                if (itemInfo != null) {
                    val targetDelta = (itemInfo.offset - focalOffset)
                    listState.animateScrollBy(
                        value = targetDelta,
                        animationSpec = tween(durationMillis = 350)
                    )
                } else {
                    listState.animateScrollToItem(
                        index = activeIndex,
                        scrollOffset = (-focalOffset).toInt()
                    )
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgBase)
            .statusBarsPadding()
    ) {
        // Subtle Ambient Glow Canvas
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(
                        dominantColor.copy(alpha = 0.22f),
                        BgBase.copy(alpha = 0.85f),
                        BgBase
                    ),
                    center = Offset(size.width / 2, size.height * 0.25f),
                    radius = size.width * 1.1f
                )
            )
        }

        Column(modifier = Modifier.fillMaxSize()) {
            // Top Bar
            YtLyricsHeader(
                source = "Musixmatch / LRCLIB",
                onClose = onClose
            )

            if (lyrics.isEmpty()) {
                // Empty / Searching Lyrics State
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 120.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Filled.MusicNote,
                            contentDescription = null,
                            tint = TextTertiary,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Looking for synchronized lyrics...",
                            style = LocalAppTypography.current.songTitle,
                            color = TextSecondary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Lyrics will appear when available",
                            style = LocalAppTypography.current.songArtist,
                            color = TextTertiary
                        )
                    }
                }
            } else {
                // Lyrics Scrollable Column
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 40.dp, bottom = 160.dp)
                ) {
                    itemsIndexed(
                        items = lyrics,
                        key = { index, line -> "${index}_${line.timeMs}" }
                    ) { index, line ->
                        YtLyricLineItem(
                            text = line.text,
                            isActive = index == activeIndex,
                            onClick = { onSeek(line.timeMs) }
                        )
                    }

                    // Attribution Footer
                    item(key = "attribution_footer") {
                        Spacer(modifier = Modifier.height(32.dp))
                        Text(
                            text = "Lyrics provided by Musixmatch / LRCLIB • May not be 100% accurate",
                            style = LocalAppTypography.current.songArtist.copy(fontSize = 11.sp),
                            color = TextTertiary,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
                        )
                    }
                }
            }
        }
    }
}
