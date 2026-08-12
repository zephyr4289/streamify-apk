package com.streamify.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.streamify.app.ui.theme.StreamifyColors
import com.streamify.app.ui.theme.StreamifyDimens
import com.streamify.app.ui.theme.StreamifyType
import com.streamify.app.viewmodel.PlayerViewModel
import com.streamify.app.service.CrossfadeAudioProcessor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    playerViewModel: PlayerViewModel,
    onBack: () -> Unit
) {
    val playerState by playerViewModel.playerState.collectAsState()
    var crossfadeValue by remember { mutableStateOf(CrossfadeAudioProcessor.crossfadeDurationMs / 1000f) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(StreamifyColors.BgBase)
    ) {
        Spacer(modifier = Modifier.height(StreamifyDimens.SpaceGiant))

        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = StreamifyDimens.SpaceLG),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = StreamifyColors.TextMain)
            }
            Spacer(modifier = Modifier.width(StreamifyDimens.SpaceSM))
            Text(
                text = "Settings",
                style = StreamifyType.HeadlineLarge,
                color = StreamifyColors.TextMain
            )
        }

        Spacer(modifier = Modifier.height(StreamifyDimens.SpaceXL))

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = StreamifyDimens.SpaceLG),
            verticalArrangement = Arrangement.spacedBy(StreamifyDimens.SpaceXL)
        ) {
            item {
                Text("Playback", style = StreamifyType.TitleMedium, color = StreamifyColors.Primary)
                Spacer(modifier = Modifier.height(StreamifyDimens.SpaceMD))
                
                Card(
                    colors = CardDefaults.cardColors(containerColor = StreamifyColors.BgCard),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Crossfade", style = StreamifyType.BodyMedium, color = StreamifyColors.TextMain)
                            Text("${crossfadeValue.toInt()}s", style = StreamifyType.BodyMedium, color = StreamifyColors.TextSub)
                        }
                        Slider(
                            value = crossfadeValue,
                            onValueChange = { 
                                crossfadeValue = it
                                CrossfadeAudioProcessor.crossfadeDurationMs = (it * 1000).toLong()
                            },
                            valueRange = 0f..12f,
                            steps = 11,
                            colors = SliderDefaults.colors(
                                thumbColor = StreamifyColors.Primary,
                                activeTrackColor = StreamifyColors.Primary,
                                inactiveTrackColor = StreamifyColors.TextSub.copy(alpha = 0.3f)
                            )
                        )
                        Text("Allows a smooth transition between songs.", style = StreamifyType.Caption, color = StreamifyColors.TextSub)
                    }
                }
            }

            item {
                Text("Sleep Timer", style = StreamifyType.TitleMedium, color = StreamifyColors.Primary)
                Spacer(modifier = Modifier.height(StreamifyDimens.SpaceMD))
                
                Card(
                    colors = CardDefaults.cardColors(containerColor = StreamifyColors.BgCard),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        if (playerState.sleepTimerMinutesLeft != null || playerState.sleepTimerEndTrack) {
                            val status = if (playerState.sleepTimerEndTrack) "End of track" else "${playerState.sleepTimerMinutesLeft} min"
                            Text("Timer active: $status", style = StreamifyType.BodyMedium, color = StreamifyColors.Primary)
                            Spacer(modifier = Modifier.height(16.dp))
                        }

                        val options = listOf("Off", "15 minutes", "30 minutes", "45 minutes", "1 hour", "End of track")
                        options.forEach { option ->
                            TextButton(
                                onClick = {
                                    when (option) {
                                        "Off" -> playerViewModel.setSleepTimer(null, false)
                                        "15 minutes" -> playerViewModel.setSleepTimer(15)
                                        "30 minutes" -> playerViewModel.setSleepTimer(30)
                                        "45 minutes" -> playerViewModel.setSleepTimer(45)
                                        "1 hour" -> playerViewModel.setSleepTimer(60)
                                        "End of track" -> playerViewModel.setSleepTimer(null, true)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = option,
                                    modifier = Modifier.fillMaxWidth(),
                                    color = StreamifyColors.TextMain,
                                    style = StreamifyType.BodyMedium
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
