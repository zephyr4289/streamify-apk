package com.streamify.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streamify.app.data.models.LyricsLine
import com.streamify.app.ui.theme.StreamifyColors
import kotlinx.coroutines.launch

@Composable
fun LyricsScreen(
    lyrics: List<LyricsLine>,
    currentPositionMs: Long,
    onSeek: (Long) -> Unit
) {
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    
    // Find active line
    var activeIndex by remember { mutableStateOf(-1) }
    
    LaunchedEffect(currentPositionMs, lyrics) {
        val index = lyrics.indexOfLast { it.timeMs <= currentPositionMs }
        if (index != activeIndex && index >= 0) {
            activeIndex = index
            // Auto-scroll to center the active line
            coroutineScope.launch {
                listState.animateScrollToItem(index, scrollOffset = -200)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(StreamifyColors.BgBase)
            .padding(top = 48.dp, start = 16.dp, end = 16.dp)
    ) {
        if (lyrics.isEmpty()) {
            Text(
                text = "No lyrics available",
                color = StreamifyColors.TextSub,
                modifier = Modifier.padding(top = 32.dp)
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 32.dp, bottom = 120.dp) // Padding for player bar
            ) {
                itemsIndexed(lyrics) { index, line ->
                    val isActive = index == activeIndex
                    val isPast = index < activeIndex
                    
                    val color = when {
                        isActive -> StreamifyColors.TextMain
                        isPast -> StreamifyColors.TextSub
                        else -> StreamifyColors.TextSub.copy(alpha = 0.5f)
                    }
                    
                    val weight = if (isActive) FontWeight.Bold else FontWeight.Normal
                    val size = if (isActive) 24.sp else 20.sp

                    Text(
                        text = line.text,
                        color = color,
                        fontWeight = weight,
                        fontSize = size,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp)
                            .clickable { onSeek(line.timeMs) }
                    )
                }
            }
        }
    }
}
