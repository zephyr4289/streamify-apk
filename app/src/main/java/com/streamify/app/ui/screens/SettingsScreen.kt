package com.streamify.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
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
    onBack: () -> Unit,
    onNavigateToEq: () -> Unit = {}
) {
    val playerState by playerViewModel.playerState.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val audioPrefs = remember { context.getSharedPreferences("audio_settings", android.content.Context.MODE_PRIVATE) }
    var selectedQuality by remember { mutableStateOf(audioPrefs.getString("download_quality", "320") ?: "320") }
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
                Text("Audio Quality", style = StreamifyType.TitleMedium, color = StreamifyColors.Primary)
                Spacer(modifier = Modifier.height(StreamifyDimens.SpaceMD))
                
                Card(
                    colors = CardDefaults.cardColors(containerColor = StreamifyColors.BgCard),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Streaming & Download Bitrate",
                            style = StreamifyType.BodyMedium,
                            color = StreamifyColors.TextMain
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Higher quality produces richer sound clarity but uses more storage space.",
                            style = StreamifyType.Caption,
                            color = StreamifyColors.TextSub
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        val qualityOptions = listOf(
                            Pair("128", "Low (128 kbps) - Fast download, low data"),
                            Pair("192", "Normal (192 kbps) - Standard quality"),
                            Pair("256", "High (256 kbps) - High fidelity audio"),
                            Pair("320", "Extreme (320 kbps) - Maximum audio clarity (Default)")
                        )

                        qualityOptions.forEach { (kbps, label) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedQuality = kbps
                                        audioPrefs.edit().putString("download_quality", kbps).apply()
                                    }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = label,
                                    style = StreamifyType.BodyMedium,
                                    color = if (selectedQuality == kbps) StreamifyColors.Primary else StreamifyColors.TextMain,
                                    modifier = Modifier.weight(1f)
                                )
                                RadioButton(
                                    selected = (selectedQuality == kbps),
                                    onClick = {
                                        selectedQuality = kbps
                                        audioPrefs.edit().putString("download_quality", kbps).apply()
                                    },
                                    colors = RadioButtonDefaults.colors(
                                        selectedColor = StreamifyColors.Primary,
                                        unselectedColor = StreamifyColors.TextSub
                                    )
                                )
                            }
                        }
                    }
                }
            }

            item {
                Text("Audio Effects", style = StreamifyType.TitleMedium, color = StreamifyColors.Primary)
                Spacer(modifier = Modifier.height(StreamifyDimens.SpaceMD))
                
                Card(
                    colors = CardDefaults.cardColors(containerColor = StreamifyColors.BgCard),
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onNavigateToEq
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Equalizer", style = StreamifyType.BodyMedium, color = StreamifyColors.TextMain)
                            Text("Tune bass, treble, and surround sound", style = StreamifyType.Caption, color = StreamifyColors.TextSub)
                        }
                        Icon(androidx.compose.material.icons.Icons.Filled.ArrowForward, contentDescription = null, tint = StreamifyColors.TextSub)
                    }
                }
            }

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
