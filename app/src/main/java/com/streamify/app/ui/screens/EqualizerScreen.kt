package com.streamify.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streamify.app.service.EqualizerManager
import com.streamify.app.ui.theme.StreamifyColors
import com.streamify.app.ui.theme.StreamifyDimens
import com.streamify.app.ui.theme.StreamifyType
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EqualizerScreen(onBack: () -> Unit) {
    val isEqEnabled by EqualizerManager.isEqEnabled.collectAsState()
    val activePresetName by EqualizerManager.activePresetName.collectAsState()
    val isLoudnessEnabled by EqualizerManager.isLoudnessNormalizationEnabled.collectAsState()
    val bands by EqualizerManager.bands.collectAsState()
    val bassStrength by EqualizerManager.bassStrength.collectAsState()
    val virtualizerStrength by EqualizerManager.virtualizerStrength.collectAsState()
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(StreamifyColors.BgBase)
            .verticalScroll(scrollState)
    ) {
        Spacer(modifier = Modifier.height(StreamifyDimens.SpaceGiant)) // Status bar
        
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = StreamifyDimens.SpaceLG),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = StreamifyColors.TextMain)
            }
            Text("Equalizer & DSP Studio", style = StreamifyType.TitleLarge, color = StreamifyColors.TextMain)
        }
        
        Spacer(modifier = Modifier.height(StreamifyDimens.SpaceLG))

        // Master EQ Toggle Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = StreamifyDimens.SpaceLG),
            colors = CardDefaults.cardColors(containerColor = StreamifyColors.BgCard)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("Master Equalizer", style = StreamifyType.TitleMedium, color = StreamifyColors.TextMain)
                        Text(
                            if (isEqEnabled) "Active Preset: $activePresetName" else "DSP Bypassed",
                            style = StreamifyType.BodySmall,
                            color = if (isEqEnabled) StreamifyColors.Primary else StreamifyColors.TextSub
                        )
                    }
                    Switch(
                        checked = isEqEnabled,
                        onCheckedChange = { EqualizerManager.setEqEnabled(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = StreamifyColors.BgBase,
                            checkedTrackColor = StreamifyColors.Primary
                        )
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Loudness Normalization", style = StreamifyType.BodyMedium, color = StreamifyColors.TextMain)
                    Switch(
                        checked = isLoudnessEnabled,
                        onCheckedChange = { EqualizerManager.setLoudnessNormalization(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = StreamifyColors.BgBase,
                            checkedTrackColor = StreamifyColors.Primary
                        )
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(StreamifyDimens.SpaceLG))

        // 1-Tap Preset Horizontal Chips
        Text(
            "Acoustic Presets",
            style = StreamifyType.TitleSmall,
            color = StreamifyColors.TextSub,
            modifier = Modifier.padding(horizontal = StreamifyDimens.SpaceLG)
        )
        Spacer(modifier = Modifier.height(8.dp))

        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = StreamifyDimens.SpaceLG),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(EqualizerManager.PRESET_PROFILES) { preset ->
                val isSelected = activePresetName.equals(preset.name, ignoreCase = true)
                FilterChip(
                    selected = isSelected,
                    onClick = { EqualizerManager.applyPresetByName(preset.name) },
                    label = { Text(preset.name, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = StreamifyColors.Primary,
                        selectedLabelColor = StreamifyColors.BgBase,
                        containerColor = StreamifyColors.BgCard,
                        labelColor = StreamifyColors.TextMain
                    ),
                    enabled = isEqEnabled
                )
            }
        }

        Spacer(modifier = Modifier.height(StreamifyDimens.SpaceLG))

        if (bands.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("Equalizer not initialized (Start playing audio to activate)", color = StreamifyColors.TextSub)
            }
        } else {
            // Equalizer Sliders
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = StreamifyDimens.SpaceLG),
                colors = CardDefaults.cardColors(containerColor = StreamifyColors.BgCard)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp, horizontal = 8.dp)
                        .height(220.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    bands.forEachIndexed { index, band ->
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.weight(1f)
                        ) {
                            val db = (band.level / 100f).roundToInt()
                            Text(
                                if (db > 0) "+$db" else "$db",
                                color = if (db != 0) StreamifyColors.Primary else StreamifyColors.TextSub,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                            
                            Slider(
                                value = band.level.toFloat(),
                                onValueChange = { EqualizerManager.setBandLevel(index.toShort(), it.toInt().toShort()) },
                                valueRange = EqualizerManager.minEqLevel.toFloat()..EqualizerManager.maxEqLevel.toFloat(),
                                modifier = Modifier
                                    .weight(1f)
                                    .graphicsLayer {
                                        this.rotationZ = 270f
                                        this.transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0.5f)
                                    },
                                colors = SliderDefaults.colors(
                                    thumbColor = StreamifyColors.Primary,
                                    activeTrackColor = StreamifyColors.Primary,
                                    inactiveTrackColor = StreamifyColors.BgBase
                                ),
                                enabled = isEqEnabled
                            )
                            
                            Text(
                                if (band.centerFreqHz >= 1000) "${band.centerFreqHz / 1000}k" else "${band.centerFreqHz}",
                                color = StreamifyColors.TextSub,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(StreamifyDimens.SpaceLG))
            
            // Sub-Bass Boost Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = StreamifyDimens.SpaceLG),
                colors = CardDefaults.cardColors(containerColor = StreamifyColors.BgCard)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Sub-Bass Boost", style = StreamifyType.TitleSmall, color = StreamifyColors.TextMain)
                        Text("${(bassStrength / 10)}%", color = StreamifyColors.Primary, fontWeight = FontWeight.Bold)
                    }
                    Slider(
                        value = bassStrength.toFloat(),
                        onValueChange = { EqualizerManager.setBassStrength(it.toInt().toShort()) },
                        valueRange = 0f..1000f,
                        colors = SliderDefaults.colors(thumbColor = StreamifyColors.Primary, activeTrackColor = StreamifyColors.Primary, inactiveTrackColor = StreamifyColors.BgBase),
                        enabled = isEqEnabled
                    )
                }
            }

            Spacer(modifier = Modifier.height(StreamifyDimens.SpaceLG))

            // 3D Spatial Virtualizer Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = StreamifyDimens.SpaceLG),
                colors = CardDefaults.cardColors(containerColor = StreamifyColors.BgCard)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("3D Spatial Virtualizer", style = StreamifyType.TitleSmall, color = StreamifyColors.TextMain)
                        Text("${(virtualizerStrength / 10)}%", color = StreamifyColors.Primary, fontWeight = FontWeight.Bold)
                    }
                    Slider(
                        value = virtualizerStrength.toFloat(),
                        onValueChange = { EqualizerManager.setVirtualizerStrength(it.toInt().toShort()) },
                        valueRange = 0f..1000f,
                        colors = SliderDefaults.colors(thumbColor = StreamifyColors.Primary, activeTrackColor = StreamifyColors.Primary, inactiveTrackColor = StreamifyColors.BgBase),
                        enabled = isEqEnabled
                    )
                }
            }

            Spacer(modifier = Modifier.height(StreamifyDimens.SpaceGiant))
        }
    }
}
