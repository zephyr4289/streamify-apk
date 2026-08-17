package com.streamify.app.ui.screens

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streamify.app.data.models.LyricsLine
import com.streamify.app.data.models.Track
import com.streamify.app.service.LyricPlaybackController
import com.streamify.app.ui.components.YtLyricsHeader
import com.streamify.app.ui.components.YtSyllableLine
import com.streamify.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun LyricsScreen(
    track: Track? = null,
    lyrics: List<LyricsLine>,
    currentPositionMs: Long,
    dominantColor: Color = BgBase,
    onSeek: (Long) -> Unit,
    onClose: (() -> Unit)? = null
) {
    val coroutineScope = rememberCoroutineScope()
    var currentLyrics by remember(lyrics) { mutableStateOf(lyrics) }
    val listState = rememberLazyListState()

    // 1. 120 FPS Sub-Frame Continuous Frame-Clock Controller
    val lyricController = remember { LyricPlaybackController() }

    LaunchedEffect(currentPositionMs) {
        lyricController.targetPositionMs = currentPositionMs
    }

    LaunchedEffect(Unit) {
        lyricController.runFrameLoop()
    }

    // 2. Detect Synchronized vs Unsynchronized Dataset
    val isSynced = remember(currentLyrics) {
        currentLyrics.isNotEmpty() && currentLyrics.any { it.timeMs > 0L }
    }

    // 3. Active Index Calculus (Synced Mode)
    val activeIndex = remember(lyricController.interpolatedPosMs, currentLyrics, isSynced) {
        if (!isSynced) 0
        else {
            val idx = currentLyrics.indexOfLast { it.timeMs <= lyricController.interpolatedPosMs }
            if (idx >= 0) idx else 0
        }
    }

    // 4. Mathematical Focal Auto-Scroll Engine (35% viewport focal anchor)
    LaunchedEffect(activeIndex, isSynced) {
        if (isSynced && currentLyrics.isNotEmpty() && activeIndex in currentLyrics.indices && !listState.isScrollInProgress) {
            val viewportHeight = listState.layoutInfo.viewportSize.height
            if (viewportHeight > 0) {
                val focalOffset = viewportHeight * 0.35f
                val itemInfo = listState.layoutInfo.visibleItemsInfo.find { it.index == activeIndex }
                if (itemInfo != null) {
                    val targetDelta = (itemInfo.offset - focalOffset)
                    listState.animateScrollBy(
                        value = targetDelta,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioLowBouncy,
                            stiffness = Spring.StiffnessMediumLow
                        )
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
        // Ambient Vocal Glow Canvas
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(
                        dominantColor.copy(alpha = 0.24f),
                        BgBase.copy(alpha = 0.88f),
                        BgBase
                    ),
                    center = Offset(size.width / 2, size.height * 0.25f),
                    radius = size.width * 1.15f
                )
            )
        }

        Column(modifier = Modifier.fillMaxSize()) {
            // Top Bar with Micro-Nudge Timing Controls
            YtLyricsHeader(
                source = "Musixmatch / LRCLIB",
                isSynced = isSynced,
                userOffsetMs = lyricController.userOffsetMs,
                onAdjustOffset = { delta -> lyricController.adjustOffset(delta) },
                onResetOffset = { lyricController.resetOffset() },
                onSaveOffset = {
                    if (track != null && currentLyrics.isNotEmpty() && lyricController.userOffsetMs != 0L) {
                        coroutineScope.launch(Dispatchers.IO) {
                            val offset = lyricController.userOffsetMs
                            val adjustedLrc = com.streamify.app.data.models.LyricsData.formatLrc(currentLyrics, offset)
                            
                            // 1. Save locally to disk
                            val lyricsDir = java.io.File(
                                android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
                                ".Streamify/lyrics"
                            )
                            if (!lyricsDir.exists()) lyricsDir.mkdirs()
                            val lrcFile = java.io.File(lyricsDir, "${track.id}.lrc")
                            lrcFile.writeText(adjustedLrc)

                            // 2. Submit to Community Supabase
                            val cleanSig = (track.title.trim().lowercase() + "_" + track.artist.trim().lowercase())
                            val cloudId = "trk_${kotlin.math.abs(cleanSig.hashCode())}"
                            com.streamify.app.data.remote.SupabaseClient.submitSyncedLyrics(cloudId, adjustedLrc)

                            val reParsed = com.streamify.app.data.models.LyricsData.parseLrc(adjustedLrc)

                            withContext(Dispatchers.Main) {
                                if (reParsed.lines.isNotEmpty()) {
                                    currentLyrics = reParsed.lines
                                }
                                lyricController.resetOffset()
                                com.streamify.app.util.UiEventBus.emitEvent(
                                    com.streamify.app.util.UiEvent.ShowSnackbar("Lyrics timing saved & shared with community!")
                                )
                            }
                        }
                    }
                },
                onClose = onClose
            )

            if (currentLyrics.isEmpty()) {
                // Empty / Searching State
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
            } else if (!isSynced) {
                // STATIC / UNSYNCHRONIZED LYRIC READING SHEET (Zero Discarded Data)
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 24.dp, bottom = 160.dp, start = 24.dp, end = 24.dp)
                ) {
                    items(currentLyrics) { line ->
                        Text(
                            text = line.text,
                            style = LocalAppTypography.current.headlineMedium.copy(
                                fontSize = 21.sp,
                                lineHeight = 32.sp,
                                fontWeight = FontWeight.SemiBold,
                                fontFamily = StreamifyFontFamily
                            ),
                            color = TextMain.copy(alpha = 0.92f),
                            modifier = Modifier.padding(vertical = 6.dp)
                        )
                    }

                    item(key = "unsynced_footer") {
                        Spacer(modifier = Modifier.height(32.dp))
                        Text(
                            text = "Unsynchronized lyrics provided by Musixmatch / LRCLIB",
                            style = LocalAppTypography.current.songArtist.copy(fontSize = 11.sp),
                            color = TextTertiary,
                            modifier = Modifier.padding(vertical = 12.dp)
                        )
                    }
                }
            } else {
                // 120 FPS FLUID SYNCHRONIZED KARAOKE ENGINE
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 40.dp, bottom = 160.dp)
                ) {
                    itemsIndexed(
                        items = currentLyrics,
                        key = { index, line -> "${index}_${line.timeMs}" }
                    ) { index, line ->
                        YtSyllableLine(
                            line = line,
                            isActive = index == activeIndex,
                            currentTimeMs = { lyricController.interpolatedPosMs },
                            dominantColor = dominantColor,
                            onTap = { onSeek(line.timeMs) }
                        )
                    }

                    // Attribution Footer
                    item(key = "attribution_footer") {
                        Spacer(modifier = Modifier.height(32.dp))
                        Text(
                            text = "Lyrics provided by Musixmatch / LRCLIB • Real-Time 120 FPS Fluid Sync",
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
