package com.streamify.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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
    val bands by EqualizerManager.bands.collectAsState()
    val bassStrength by EqualizerManager.bassStrength.collectAsState()
    val virtualizerStrength by EqualizerManager.virtualizerStrength.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(StreamifyColors.BgBase)
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
            Text("Equalizer & Effects", style = StreamifyType.TitleLarge, color = StreamifyColors.TextMain)
        }
        
        Spacer(modifier = Modifier.height(StreamifyDimens.SpaceLG))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = StreamifyDimens.SpaceXL),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Enable Master EQ", style = StreamifyType.TitleMedium, color = StreamifyColors.TextMain)
            Switch(
                checked = isEqEnabled,
                onCheckedChange = { EqualizerManager.setEqEnabled(it) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = StreamifyColors.BgBase,
                    checkedTrackColor = StreamifyColors.Primary
                )
            )
        }

        Spacer(modifier = Modifier.height(StreamifyDimens.SpaceGiant))

        if (bands.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text("Equalizer not initialized (Start playing a track first)", color = StreamifyColors.TextSub)
            }
        } else {
            // Equalizer Sliders
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = StreamifyDimens.SpaceLG)
                    .height(250.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                bands.forEachIndexed { index, band ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("${band.centerFreqHz}", color = StreamifyColors.TextSub, fontSize = 12.sp)
                        
                        // Vertical slider (Custom implementation using Slider with rotation)
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
                                inactiveTrackColor = StreamifyColors.BgCard
                            ),
                            enabled = isEqEnabled
                        )
                        
                        val db = (band.level / 100f).roundToInt()
                        Text(if (db > 0) "+$db dB" else "$db dB", color = StreamifyColors.TextMain, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(StreamifyDimens.SpaceGiant))
            
            // Bass Boost
            Column(modifier = Modifier.padding(horizontal = StreamifyDimens.SpaceXL)) {
                Text("Bass Boost", style = StreamifyType.TitleMedium, color = StreamifyColors.TextMain)
                Slider(
                    value = bassStrength.toFloat(),
                    onValueChange = { EqualizerManager.setBassStrength(it.toInt().toShort()) },
                    valueRange = 0f..1000f,
                    colors = SliderDefaults.colors(thumbColor = StreamifyColors.Primary, activeTrackColor = StreamifyColors.Primary),
                    enabled = isEqEnabled
                )
            }

            Spacer(modifier = Modifier.height(StreamifyDimens.SpaceLG))

            // Virtualizer
            Column(modifier = Modifier.padding(horizontal = StreamifyDimens.SpaceXL)) {
                Text("Virtualizer (3D Surround)", style = StreamifyType.TitleMedium, color = StreamifyColors.TextMain)
                Slider(
                    value = virtualizerStrength.toFloat(),
                    onValueChange = { EqualizerManager.setVirtualizerStrength(it.toInt().toShort()) },
                    valueRange = 0f..1000f,
                    colors = SliderDefaults.colors(thumbColor = StreamifyColors.Primary, activeTrackColor = StreamifyColors.Primary),
                    enabled = isEqEnabled
                )
            }
        }
    }
}
