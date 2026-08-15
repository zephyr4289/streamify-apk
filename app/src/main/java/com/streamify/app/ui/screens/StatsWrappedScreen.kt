package com.streamify.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streamify.app.ui.theme.StreamifyColors
import com.streamify.app.ui.theme.StreamifyDimens
import com.streamify.app.ui.theme.StreamifyType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsWrappedScreen(
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Streamify Wrapped", style = StreamifyType.HeadlineMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = StreamifyColors.TextMain)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = StreamifyColors.BgBase)
            )
        },
        containerColor = StreamifyColors.BgBase
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = StreamifyDimens.SpaceLG)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(12.dp))

            // Hero Card
            Card(
                colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0xFF1DB954), Color(0xFF191414))
                        ),
                        shape = RoundedCornerShape(24.dp)
                    )
            ) {
                Column(
                    modifier = Modifier.padding(24.dp)
                ) {
                    Text("YOUR 2026 SOUNDTRACK", style = StreamifyType.CaptionBold, color = StreamifyColors.BgBase)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("14,820", style = StreamifyType.HeadlineLarge, color = StreamifyColors.TextMain, fontSize = 42.sp)
                    Text("Minutes Listened", style = StreamifyType.BodyMediumBold, color = StreamifyColors.TextMain)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "You explored more acoustic signal depth than 88% of Streamify listeners worldwide.",
                        style = StreamifyType.BodySmall,
                        color = StreamifyColors.TextMain.copy(alpha = 0.9f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Music Personality Card
            Surface(
                color = StreamifyColors.BgCard,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text("YOUR AI AUDIO PERSONA", style = StreamifyType.Caption, color = StreamifyColors.TextSub)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("The Harmonic Wanderer 🌌", style = StreamifyType.HeadlineMedium, color = StreamifyColors.Primary)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Your vector embedding cluster shows a high affinity for dynamic harmonic transitions, 120-128 BPM energy, and deep bass frequencies.",
                        style = StreamifyType.BodyMedium,
                        color = StreamifyColors.TextSub
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Top Genres Breakdown
            Surface(
                color = StreamifyColors.BgCard,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text("TOP GENRES", style = StreamifyType.Caption, color = StreamifyColors.TextSub)
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    GenreProgressBar("1. Synthwave & Electronic", 0.42f, Color(0xFF1DB954))
                    Spacer(modifier = Modifier.height(10.dp))
                    GenreProgressBar("2. Hip-Hop & Lo-Fi", 0.28f, Color(0xFFFF9800))
                    Spacer(modifier = Modifier.height(10.dp))
                    GenreProgressBar("3. Indie & Alternative", 0.18f, Color(0xFF03A9F4))
                    Spacer(modifier = Modifier.height(10.dp))
                    GenreProgressBar("4. Ambient & Focus", 0.12f, Color(0xFFE91E63))
                }
            }

            Spacer(modifier = Modifier.height(30.dp))
        }
    }
}

@Composable
private fun GenreProgressBar(title: String, fraction: Float, color: Color) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(title, style = StreamifyType.BodySmallBold, color = StreamifyColors.TextMain)
            Text("${(fraction * 100).toInt()}%", style = StreamifyType.CaptionBold, color = color)
        }
        Spacer(modifier = Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = fraction,
            color = color,
            trackColor = StreamifyColors.BgElevated,
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp),
        )
    }
}
