package com.streamify.app.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.streamify.app.data.YtStatsTelemetryEngine
import com.streamify.app.data.remote.AuthManager
import com.streamify.app.data.remote.AuthState
import com.streamify.app.data.remote.SupabaseClient
import com.streamify.app.ui.components.YtWrappedHeroCard
import com.streamify.app.ui.theme.*
import kotlinx.coroutines.launch
import java.util.Calendar

@Composable
fun UserProfileScreen(
    onBack: () -> Unit,
    onNavigateToWrapped: () -> Unit,
    onNavigateToAdmin: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val user by SupabaseClient.currentUser.collectAsState()
    val authState by AuthManager.authState.collectAsState()
    val stats by YtStatsTelemetryEngine.computeWrappedStats().collectAsState(initial = null)

    var showEditDialog by remember { mutableStateOf(false) }
    var editName by remember(user) { mutableStateOf(user?.displayName ?: "") }
    var editBio by remember(user) { mutableStateOf(user?.bio ?: "") }
    var editGenre by remember(user) { mutableStateOf(user?.favoriteGenre ?: "All") }
    var isSaving by remember { mutableStateOf(false) }
    var isSigningIn by remember { mutableStateOf(false) }

    // Dynamic Musical Chronotype Calculation based on local device time & real library BPM
    val chronotype = remember(stats) {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val bpm = stats?.averageBpm ?: 124
        when (hour) {
            in 21..23, in 0..4 -> Triple(
                "The Night Explorer 🦉",
                "Peak nocturnal listening • $bpm BPM acoustic signature",
                Color(0xFF8B5CF6)
            )
            in 5..11 -> Triple(
                "The Morning Motor 🌅",
                "High-energy daybreak kickstart • $bpm BPM acoustic signature",
                Color(0xFFF59E0B)
            )
            in 12..16 -> Triple(
                "The Afternoon Groover ☀️",
                "Steady focus & rhythm cadence • $bpm BPM acoustic signature",
                Color(0xFF3B82F6)
            )
            else -> Triple(
                "The Evening Unwinder 🌆",
                "Mellow dusk transitions • $bpm BPM acoustic signature",
                Color(0xFFEC4899)
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgBase)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 120.dp)
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
                    text = "Profile & Cloud Account",
                    style = LocalAppTypography.current.headlineMedium.copy(fontSize = 18.sp),
                    color = TextMain
                )
            }

            if (user != null) {
                IconButton(onClick = { showEditDialog = true }) {
                    Icon(
                        imageVector = Icons.Filled.Edit,
                        contentDescription = "Edit Profile",
                        tint = TextMain,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Avatar & Identity
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val currentUser = user
            if (currentUser != null && currentUser.avatarUrl.isNotBlank()) {
                AsyncImage(
                    model = currentUser.avatarUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .size(88.dp)
                        .clip(CircleShape)
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(88.dp)
                        .clip(CircleShape)
                        .background(BgSurfaceElevated),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = currentUser?.displayName?.take(1)?.uppercase() ?: "U",
                        style = LocalAppTypography.current.headlineLarge.copy(fontSize = 32.sp),
                        color = TextMain
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = currentUser?.displayName ?: "Streamify Listener",
                style = LocalAppTypography.current.headlineLarge.copy(fontSize = 20.sp, fontWeight = FontWeight.Bold),
                color = TextMain
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = currentUser?.email ?: "",
                style = LocalAppTypography.current.songArtist.copy(fontSize = 12.sp),
                color = TextSecondary
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = currentUser?.bio ?: "Music lover exploring acoustic soundscapes on Streamify 🎧",
                style = LocalAppTypography.current.songArtist.copy(fontSize = 13.sp),
                color = TextSecondary
            )

            Spacer(modifier = Modifier.height(18.dp))

            // Cloud Sync Status Pill
            Surface(
                color = if (currentUser != null) Primary.copy(alpha = 0.15f) else BgSurfaceElevated,
                shape = RoundedCornerShape(20.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (currentUser != null) Icons.Filled.CloudDone else Icons.Filled.CloudOff,
                        contentDescription = null,
                        tint = if (currentUser != null) Primary else TextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (currentUser != null) "Cloud Sync Active (PostgreSQL + pgvector)" else "Local Only • Sign in to sync across devices",
                        style = LocalAppTypography.current.songArtist.copy(fontSize = 11.sp),
                        color = if (currentUser != null) Primary else TextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Auth Action Button (Sign In / Sign Out)
            if (isSigningIn) {
                CircularProgressIndicator(color = Primary, strokeWidth = 3.dp, modifier = Modifier.size(28.dp))
            } else {
                Button(
                    onClick = {
                        if (currentUser != null) {
                            AuthManager.signOut(context)
                            Toast.makeText(context, "Signed out", Toast.LENGTH_SHORT).show()
                        } else {
                            isSigningIn = true
                            scope.launch {
                                val res = AuthManager.signInWithGoogle(context)
                                isSigningIn = false
                                if (res.isSuccess) {
                                    Toast.makeText(context, "Signed in as ${res.getOrNull()?.displayName}!", Toast.LENGTH_SHORT).show()
                                } else {
                                    val err = res.exceptionOrNull()?.message ?: "Sign in failed"
                                    if (!err.contains("cancelled", ignoreCase = true)) {
                                        Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (currentUser != null) BgSurfaceElevated else ActiveControl
                    ),
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                ) {
                    Text(
                        text = if (currentUser != null) "Sign Out" else "Sign In with Google",
                        style = LocalAppTypography.current.chipText.copy(fontSize = 13.sp),
                        color = if (currentUser != null) Primary else TextOnActiveChip,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Real Telemetry Numbers Hero Card
        val currentStats = stats
        if (currentStats != null) {
            YtWrappedHeroCard(
                totalMinutes = currentStats.totalMinutes,
                totalTracks = currentStats.totalTracks,
                likedSongs = currentStats.likedSongs,
                topPlayedCount = currentStats.topPlayedCount
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Dynamic Musical Chronotype Card
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = BgSurfaceElevated,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(chronotype.third.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Bedtime,
                        contentDescription = null,
                        tint = chronotype.third,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "MUSICAL CHRONOTYPE",
                        style = LocalAppTypography.current.songArtist.copy(
                            fontSize = 11.sp,
                            letterSpacing = 0.5.sp
                        ),
                        color = chronotype.third
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = chronotype.first,
                        style = LocalAppTypography.current.headlineMedium.copy(fontSize = 16.sp),
                        color = TextMain
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = chronotype.second,
                        style = LocalAppTypography.current.songArtist.copy(fontSize = 12.sp),
                        color = TextSecondary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Streamify Wrapped Banner Button
        Surface(
            color = BgSurfaceElevated,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .clickable { onNavigateToWrapped() }
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Stars,
                        contentDescription = null,
                        tint = Primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Streamify Wrapped 2026 🎉",
                            style = LocalAppTypography.current.songTitle.copy(fontSize = 14.sp),
                            color = TextMain
                        )
                        Text(
                            text = "Explore your full acoustic audio persona & top genres",
                            style = LocalAppTypography.current.songArtist.copy(fontSize = 12.sp),
                            color = TextSecondary
                        )
                    }
                }
                Icon(
                    imageVector = Icons.Filled.ArrowForward,
                    contentDescription = null,
                    tint = TextSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        // App Settings & Preferences Card (Audio Quality, Equalizer, Database Reset)
        Surface(
            color = BgSurfaceElevated,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .clickable { onNavigateToSettings() }
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Primary.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = "Settings",
                            tint = Primary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Settings & Playback",
                            style = LocalAppTypography.current.songTitle.copy(
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            ),
                            color = TextMain
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Audio quality, Equalizer & Database Reset",
                            style = LocalAppTypography.current.songArtist.copy(fontSize = 12.sp),
                            color = TextSecondary
                        )
                    }
                }
                Icon(
                    imageVector = Icons.Filled.ArrowForward,
                    contentDescription = null,
                    tint = TextSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        // Special Admin Command Center Card (Always visible for Sireen / Admin)
        val isAdminUser = user?.isAdmin == true ||
                user?.email?.contains("sireenyadav", ignoreCase = true) == true ||
                user?.email.equals(com.streamify.app.BuildConfig.ADMIN_EMAIL, ignoreCase = true) ||
                user?.displayName?.contains("sireen", ignoreCase = true) == true

        if (isAdminUser) {
            Spacer(modifier = Modifier.height(8.dp))
            Surface(
                color = BgSurfaceElevated,
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Primary.copy(alpha = 0.6f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .clickable { onNavigateToAdmin() }
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(Primary.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.AdminPanelSettings,
                                contentDescription = null,
                                tint = Primary,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "Admin Command Center",
                                    style = LocalAppTypography.current.songTitle.copy(
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = TextMain
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = Primary.copy(alpha = 0.2f)
                                ) {
                                    Text(
                                        text = "OWNER",
                                        style = LocalAppTypography.current.chipText.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold),
                                        color = Primary,
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Telemetry, Edge Mesh cluster & DB control",
                                style = LocalAppTypography.current.songArtist.copy(fontSize = 12.sp),
                                color = TextSecondary
                            )
                        }
                    }
                    Icon(
                        imageVector = Icons.Filled.ArrowForward,
                        contentDescription = null,
                        tint = Primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }

    // Edit Profile Modal Dialog
    if (showEditDialog) {
        AlertDialog(
            onDismissRequest = { showEditDialog = false },
            title = { Text("Edit Profile", style = LocalAppTypography.current.headlineMedium, color = TextMain) },
            text = {
                Column {
                    OutlinedTextField(
                        value = editName,
                        onValueChange = { editName = it },
                        label = { Text("Display Name", color = TextSecondary) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = ActiveControl,
                            unfocusedBorderColor = Divider,
                            focusedTextColor = TextMain,
                            unfocusedTextColor = TextMain
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = editBio,
                        onValueChange = { editBio = it },
                        label = { Text("Bio", color = TextSecondary) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = ActiveControl,
                            unfocusedBorderColor = Divider,
                            focusedTextColor = TextMain,
                            unfocusedTextColor = TextMain
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = editGenre,
                        onValueChange = { editGenre = it },
                        label = { Text("Favorite Genre", color = TextSecondary) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = ActiveControl,
                            unfocusedBorderColor = Divider,
                            focusedTextColor = TextMain,
                            unfocusedTextColor = TextMain
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        isSaving = true
                        scope.launch {
                            val res = SupabaseClient.updateProfile(
                                displayName = editName,
                                avatarUrl = user?.avatarUrl ?: "",
                                bio = editBio,
                                favGenre = editGenre
                            )
                            isSaving = false
                            showEditDialog = false
                            if (res.isSuccess) {
                                Toast.makeText(context, "Profile updated!", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ActiveControl),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Text("Save", color = TextOnActiveChip, style = LocalAppTypography.current.chipText)
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditDialog = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            containerColor = BgSurfaceElevated
        )
    }
}
