package com.streamify.app.ui.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streamify.app.data.YtStatsTelemetryEngine
import com.streamify.app.ui.components.*
import com.streamify.app.ui.theme.*

@Composable
fun StatsWrappedScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val stats by YtStatsTelemetryEngine.computeWrappedStats().collectAsState(initial = null)
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgBase)
            .statusBarsPadding()
            .verticalScroll(scrollState)
            .padding(bottom = 120.dp) // Protective padding for docked player
    ) {
        // Top App Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = TextMain,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Streamify Wrapped 2026",
                    style = LocalAppTypography.current.headlineMedium.copy(fontSize = 18.sp),
                    color = TextMain
                )
            }

            stats?.let { currentStats ->
                IconButton(onClick = {
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(
                            Intent.EXTRA_TEXT,
                            "🔥 My 2026 Streamify Wrapped:\n" +
                                    "🎧 ${currentStats.totalMinutes} Minutes Listened\n" +
                                    "✨ Persona: ${currentStats.personaName} ${currentStats.personaEmoji}\n" +
                                    "🎶 Top Genre: ${currentStats.topGenres.firstOrNull()?.first ?: "Music"}\n" +
                                    "Listen on Streamify!"
                        )
                    }
                    context.startActivity(Intent.createChooser(shareIntent, "Share Streamify Wrapped"))
                }) {
                    Icon(
                        imageVector = Icons.Filled.Share,
                        contentDescription = "Share",
                        tint = ActiveControl,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        val currentStats = stats
        if (currentStats == null) {
            // Loading Telemetry DNA state
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Primary, strokeWidth = 3.dp)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Computing your audio DNA...",
                        style = LocalAppTypography.current.songArtist,
                        color = TextSecondary
                    )
                }
            }
        } else {
            Spacer(modifier = Modifier.height(8.dp))

            // 1. Hero Telemetry Card
            YtWrappedHeroCard(
                totalMinutes = currentStats.totalMinutes,
                totalTracks = currentStats.totalTracks,
                likedSongs = currentStats.likedSongs,
                topPlayedCount = currentStats.topPlayedCount
            )

            // 2. AI Audio Persona Card (BPM-Reactive)
            YtPersonaCard(
                personaName = currentStats.personaName,
                personaEmoji = currentStats.personaEmoji,
                description = currentStats.personaDescription,
                bpm = currentStats.averageBpm
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 3. Top Genres Breakdown
            Text(
                text = "TOP GENRES",
                style = LocalAppTypography.current.songArtist.copy(
                    fontSize = 11.sp,
                    letterSpacing = 0.5.sp
                ),
                color = TextSecondary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
            )

            Surface(
                color = BgSurfaceElevated,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                Column(modifier = Modifier.padding(vertical = 12.dp)) {
                    currentStats.topGenres.forEachIndexed { index, (genre, percentage) ->
                        YtGenreDistributionBar(
                            rank = index + 1,
                            genre = genre,
                            percentage = percentage
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Share CTA Button
            Button(
                onClick = {
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(
                            Intent.EXTRA_TEXT,
                            "🔥 My 2026 Streamify Wrapped:\n" +
                                    "🎧 ${currentStats.totalMinutes} Minutes Listened\n" +
                                    "✨ Persona: ${currentStats.personaName} ${currentStats.personaEmoji}\n" +
                                    "🎶 Top Genre: ${currentStats.topGenres.firstOrNull()?.first ?: "Music"}\n" +
                                    "Listen on Streamify!"
                        )
                    }
                    context.startActivity(Intent.createChooser(shareIntent, "Share Streamify Wrapped"))
                },
                colors = ButtonDefaults.buttonColors(containerColor = ActiveControl),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .height(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Share,
                    contentDescription = null,
                    tint = TextOnActiveChip,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Share Your Wrapped",
                    style = LocalAppTypography.current.chipText.copy(fontSize = 14.sp),
                    color = TextOnActiveChip
                )
            }
        }
    }
}
