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
import androidx.compose.ui.unit.sp
import com.streamify.app.ui.theme.StreamifyColors
import com.streamify.app.ui.theme.StreamifyDimens
import com.streamify.app.ui.theme.StreamifyType
import com.streamify.app.viewmodel.PlayerViewModel
import com.streamify.app.service.CrossfadeAudioProcessor

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title.uppercase(),
        style = StreamifyType.LabelSmall.copy(letterSpacing = 1.2.sp),
        color = StreamifyColors.Primary,
        modifier = Modifier.padding(bottom = StreamifyDimens.SpaceSM)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    playerViewModel: PlayerViewModel,
    onBack: () -> Unit,
    onNavigateToEq: () -> Unit = {},
    onNavigateToAdmin: () -> Unit = {}
) {
    val playerState by playerViewModel.playerState.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val audioPrefs = remember { context.getSharedPreferences("audio_settings", android.content.Context.MODE_PRIVATE) }
    var selectedQuality by remember { mutableStateOf(audioPrefs.getString("download_quality", "320") ?: "320") }
    var crossfadeValue by remember { mutableStateOf(CrossfadeAudioProcessor.crossfadeDurationMs / 1000f) }

    val user by com.streamify.app.data.remote.SupabaseClient.currentUser.collectAsState()
    val scope = rememberCoroutineScope()

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
            // Profile & Supabase Cloud Section
            item {
                SectionHeader("Account & Cloud Sync")
                Spacer(modifier = Modifier.height(StreamifyDimens.SpaceMD))

                Card(
                    colors = CardDefaults.cardColors(containerColor = StreamifyColors.BgCard),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        if (user == null) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Sign In with Google", style = StreamifyType.TitleMedium, color = StreamifyColors.TextMain)
                                    Text("Sync likes, playlists & Jam rooms across devices", style = StreamifyType.Caption, color = StreamifyColors.TextSub)
                                }
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(
                                onClick = {
                                    scope.launch {
                                        val res = com.streamify.app.data.remote.AuthManager.signInWithGoogle(context)
                                        if (res.isSuccess) {
                                            android.widget.Toast.makeText(context, "Welcome, ${res.getOrNull()?.displayName}!", android.widget.Toast.LENGTH_SHORT).show()
                                        } else {
                                            android.widget.Toast.makeText(context, "Sign-in note: ${res.exceptionOrNull()?.message}", android.widget.Toast.LENGTH_LONG).show()
                                        }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = StreamifyColors.Primary),
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Filled.AccountCircle, contentDescription = null, tint = androidx.compose.ui.graphics.Color.Black)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Continue with Google (1-Tap)", style = StreamifyType.TitleSmall, color = androidx.compose.ui.graphics.Color.Black)
                            }
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (user?.avatarUrl.isNullOrBlank()) {
                                        Box(
                                            modifier = Modifier
                                                .size(44.dp)
                                                .androidx.compose.ui.draw.clip(androidx.compose.foundation.shape.CircleShape)
                                                .background(StreamifyColors.Primary.copy(alpha = 0.2f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(user?.displayName?.take(1)?.uppercase() ?: "U", style = StreamifyType.TitleMedium, color = StreamifyColors.Primary)
                                        }
                                    } else {
                                        coil.compose.AsyncImage(
                                            model = user?.avatarUrl,
                                            contentDescription = null,
                                            modifier = Modifier
                                                .size(44.dp)
                                                .androidx.compose.ui.draw.clip(androidx.compose.foundation.shape.CircleShape)
                                        )
                                    }

                                    Spacer(modifier = Modifier.width(12.dp))

                                    Column {
                                        Text(user?.displayName ?: "User", style = StreamifyType.TitleMedium, color = StreamifyColors.TextMain)
                                        Text(user?.email ?: "", style = StreamifyType.Caption, color = StreamifyColors.TextSub)
                                    }
                                }

                                TextButton(onClick = { com.streamify.app.data.remote.SupabaseClient.signOut() }) {
                                    Text("Sign Out", color = StreamifyColors.ErrorRed)
                                }
                            }

                            // Special Admin Command Center Card for sireenyadav@gmail.com
                            if (user?.isAdmin == true || user?.email.equals("sireenyadav@gmail.com", ignoreCase = true)) {
                                Spacer(modifier = Modifier.height(16.dp))
                                Button(
                                    onClick = onNavigateToAdmin,
                                    colors = ButtonDefaults.buttonColors(containerColor = StreamifyColors.Primary),
                                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Filled.AdminPanelSettings, contentDescription = null, tint = androidx.compose.ui.graphics.Color.Black)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Open Admin Command Center", style = StreamifyType.TitleSmall, color = androidx.compose.ui.graphics.Color.Black)
                                }
                            }
                        }
                    }
                }
            }

            item {
                SectionHeader("Audio Quality")
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
                SectionHeader("Audio Effects")
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
                SectionHeader("Playback")
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
                                audioPrefs.edit().putFloat("crossfade_val", it).apply()
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
                SectionHeader("Sleep Timer")
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
            item {
                SectionHeader("Playback & Acoustic Dynamics")
                Spacer(modifier = Modifier.height(StreamifyDimens.SpaceMD))

                Card(
                    colors = CardDefaults.cardColors(containerColor = StreamifyColors.BgCard),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        val isLoudnessOn by com.streamify.app.service.EqualizerManager.isLoudnessNormalizationEnabled.collectAsState()
                        var autoDownloadLiked by remember {
                            mutableStateOf(audioPrefs.getBoolean("auto_download_liked", false))
                        }

                        // Loudness Normalization
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Loudness Normalization", style = StreamifyType.BodyMedium, color = StreamifyColors.TextMain)
                                Text("Normalizes volume across tracks (-14 LUFS standard)", style = StreamifyType.Caption, color = StreamifyColors.TextSub)
                            }
                            Switch(
                                checked = isLoudnessOn,
                                onCheckedChange = { com.streamify.app.service.EqualizerManager.setLoudnessNormalization(it) },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = StreamifyColors.Primary,
                                    checkedTrackColor = StreamifyColors.Primary.copy(alpha = 0.5f)
                                )
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Auto-Download Liked Songs
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Auto-Download Liked Songs", style = StreamifyType.BodyMedium, color = StreamifyColors.TextMain)
                                Text("Automatically saves liked online songs for offline listening", style = StreamifyType.Caption, color = StreamifyColors.TextSub)
                            }
                            Switch(
                                checked = autoDownloadLiked,
                                onCheckedChange = {
                                    autoDownloadLiked = it
                                    audioPrefs.edit().putBoolean("auto_download_liked", it).apply()
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = StreamifyColors.Primary,
                                    checkedTrackColor = StreamifyColors.Primary.copy(alpha = 0.5f)
                                )
                            )
                        }
                    }
                }
            }

            item {
                SectionHeader("Storage & 5-Tier Cache")
                Spacer(modifier = Modifier.height(StreamifyDimens.SpaceMD))

                var storageInfo by remember { mutableStateOf<com.streamify.app.data.StorageBreakdown?>(null) }
                val scope = rememberCoroutineScope()

                LaunchedEffect(Unit) {
                    storageInfo = com.streamify.app.data.StorageManager.getStorageBreakdown(context)
                }

                Card(
                    colors = CardDefaults.cardColors(containerColor = StreamifyColors.BgCard),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("Audio Stream Cache", style = StreamifyType.BodyMedium, color = StreamifyColors.TextMain)
                                Text("250 MB LRU progressive buffer", style = StreamifyType.Caption, color = StreamifyColors.TextSub)
                            }
                            Text(
                                text = com.streamify.app.data.StorageManager.formatBytes(storageInfo?.audioCacheBytes ?: 0L),
                                style = StreamifyType.BodyMedium,
                                color = StreamifyColors.TextMain
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("Cover Art Disk Cache", style = StreamifyType.BodyMedium, color = StreamifyColors.TextMain)
                                Text("100 MB HD image cache", style = StreamifyType.Caption, color = StreamifyColors.TextSub)
                            }
                            Text(
                                text = com.streamify.app.data.StorageManager.formatBytes(storageInfo?.imageCacheBytes ?: 0L),
                                style = StreamifyType.BodyMedium,
                                color = StreamifyColors.TextMain
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Button(
                            onClick = {
                                scope.launch {
                                    com.streamify.app.data.StorageManager.clearAllCache(context)
                                    storageInfo = com.streamify.app.data.StorageManager.getStorageBreakdown(context)
                                    android.widget.Toast.makeText(context, "Cache cleared successfully", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = StreamifyColors.BgSurfaceElevated),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Clear All Cache (${com.streamify.app.data.StorageManager.formatBytes(storageInfo?.totalCacheBytes ?: 0L)})", color = StreamifyColors.Primary)
                        }
                    }
                }
            }

            item {
                SectionHeader("Backup & Data Migration")
                Spacer(modifier = Modifier.height(StreamifyDimens.SpaceMD))

                val scope = rememberCoroutineScope()
                val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                    contract = androidx.activity.result.contract.ActivityResultContracts.GetContent()
                ) { uri ->
                    uri?.let {
                        scope.launch {
                            try {
                                val jsonStr = context.contentResolver.openInputStream(it)?.bufferedReader().use { r -> r?.readText() } ?: ""
                                val result = com.streamify.app.data.BackupManager.importLibraryBackup(context, jsonStr)
                                if (result.isSuccess) {
                                    android.widget.Toast.makeText(context, "Restored ${result.getOrNull()} items successfully", android.widget.Toast.LENGTH_LONG).show()
                                } else {
                                    android.widget.Toast.makeText(context, "Failed to restore backup", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                    }
                }

                Card(
                    colors = CardDefaults.cardColors(containerColor = StreamifyColors.BgCard),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    scope.launch {
                                        val res = com.streamify.app.data.BackupManager.exportLibraryBackup(context)
                                        if (res.isSuccess) {
                                            android.widget.Toast.makeText(context, "Backup exported to Documents/Streamify", android.widget.Toast.LENGTH_LONG).show()
                                        } else {
                                            android.widget.Toast.makeText(context, "Export failed", android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("Export Library Backup", style = StreamifyType.BodyMedium, color = StreamifyColors.TextMain)
                                Text("Save liked songs & playlists to JSON file", style = StreamifyType.Caption, color = StreamifyColors.TextSub)
                            }
                            Icon(Icons.Filled.ArrowForward, contentDescription = null, tint = StreamifyColors.TextSub)
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { importLauncher.launch("application/json") }
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("Restore Library from JSON", style = StreamifyType.BodyMedium, color = StreamifyColors.TextMain)
                                Text("Import tracks and playlists from backup", style = StreamifyType.Caption, color = StreamifyColors.TextSub)
                            }
                            Icon(Icons.Filled.ArrowForward, contentDescription = null, tint = StreamifyColors.TextSub)
                        }
                    }
                }
            }

            item {
                SectionHeader("About")
                Spacer(modifier = Modifier.height(StreamifyDimens.SpaceMD))
                
                Card(
                    colors = CardDefaults.cardColors(containerColor = StreamifyColors.BgCard),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Version", style = StreamifyType.BodyMedium, color = StreamifyColors.TextMain)
                            Text("4.2.0 (Flagship Edition)", style = StreamifyType.Caption, color = StreamifyColors.TextSub)
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Open Source High-Performance Architecture", style = StreamifyType.BodyMedium, color = StreamifyColors.TextMain)
                    }
                }
            }
        }
    }
}
