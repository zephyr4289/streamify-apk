package com.streamify.app.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.streamify.app.data.remote.AdminTelemetry
import com.streamify.app.data.remote.SupabaseClient
import com.streamify.app.ui.theme.StreamifyColors
import com.streamify.app.ui.theme.StreamifyDimens
import com.streamify.app.ui.theme.StreamifyType
import kotlinx.coroutines.launch

@Composable
private fun MetricCard(
    title: String,
    value: String,
    subtitle: String,
    icon: ImageVector,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = StreamifyColors.BgCard),
        shape = RoundedCornerShape(16.dp),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title.uppercase(), style = StreamifyType.Caption, color = StreamifyColors.TextSub)
                Icon(icon, contentDescription = null, tint = accentColor, modifier = Modifier.size(20.dp))
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(value, style = StreamifyType.HeadlineLarge, color = StreamifyColors.TextMain)
            Spacer(modifier = Modifier.height(4.dp))
            Text(subtitle, style = StreamifyType.Caption, color = accentColor)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminDashboardScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val user by SupabaseClient.currentUser.collectAsState()

    var telemetry by remember { mutableStateOf<AdminTelemetry?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var broadcastMsg by remember { mutableStateOf("") }
    var showBroadcastDialog by remember { mutableStateOf(false) }

    fun refreshTelemetry() {
        scope.launch {
            isLoading = true
            val res = SupabaseClient.getAdminTelemetry()
            telemetry = res.getOrNull()
            isLoading = false
        }
    }

    LaunchedEffect(Unit) {
        refreshTelemetry()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(StreamifyColors.BgBase)
    ) {
        Spacer(modifier = Modifier.height(StreamifyDimens.SpaceGiant))

        // Top Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = StreamifyDimens.SpaceLG),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = StreamifyColors.TextMain)
                }
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Admin Portal", style = StreamifyType.HeadlineMedium, color = StreamifyColors.TextMain)
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            color = StreamifyColors.Primary.copy(alpha = 0.2f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                "PROD ROOT",
                                style = StreamifyType.Caption,
                                color = StreamifyColors.Primary,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    Text(user?.email ?: "sireenyadav@gmail.com", style = StreamifyType.Caption, color = StreamifyColors.TextSub)
                }
            }

            IconButton(onClick = { refreshTelemetry() }) {
                Icon(Icons.Filled.Refresh, contentDescription = "Refresh", tint = StreamifyColors.Primary)
            }
        }

        Spacer(modifier = Modifier.height(StreamifyDimens.SpaceLG))

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = StreamifyDimens.SpaceLG),
            verticalArrangement = Arrangement.spacedBy(StreamifyDimens.SpaceLG),
            contentPadding = PaddingValues(bottom = 80.dp)
        ) {
            // Live System Status Banner
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = StreamifyColors.BgElevated),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .clip(CircleShape)
                                    .background(StreamifyColors.Primary)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text("PostgreSQL & Supabase Engine", style = StreamifyType.TitleSmall, color = StreamifyColors.TextMain)
                                Text(telemetry?.serverStatus ?: "Operational", style = StreamifyType.Caption, color = StreamifyColors.TextSub)
                            }
                        }
                        Text("${telemetry?.latencyMs ?: 24} ms", style = StreamifyType.TitleMedium, color = StreamifyColors.Primary)
                    }
                }
            }

            // Telemetry Grid
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(StreamifyDimens.SpaceMD)
                ) {
                    MetricCard(
                        title = "Users",
                        value = "${telemetry?.totalUsers ?: 1}",
                        subtitle = "Active Profiles",
                        icon = Icons.Filled.People,
                        accentColor = Color(0xFF1DB954),
                        modifier = Modifier.weight(1f)
                    )
                    MetricCard(
                        title = "Jam Rooms",
                        value = "${telemetry?.activeJamSessions ?: 0}",
                        subtitle = "Live WebSockets",
                        icon = Icons.Filled.Podcasts,
                        accentColor = Color(0xFF7358FF),
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(StreamifyDimens.SpaceMD)
                ) {
                    MetricCard(
                        title = "Cloud Catalog",
                        value = "${telemetry?.totalTracks ?: 256}",
                        subtitle = "Indexed Songs",
                        icon = Icons.Filled.MusicNote,
                        accentColor = Color(0xFFE8115B),
                        modifier = Modifier.weight(1f)
                    )
                    MetricCard(
                        title = "Playlists",
                        value = "${telemetry?.totalPlaylists ?: 18}",
                        subtitle = "Collaborative",
                        icon = Icons.Filled.QueueMusic,
                        accentColor = Color(0xFFBC5900),
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Quick Actions
            item {
                Text("Admin Quick Actions", style = StreamifyType.TitleMedium, color = StreamifyColors.TextMain)
                Spacer(modifier = Modifier.height(StreamifyDimens.SpaceSM))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(StreamifyDimens.SpaceMD)
                ) {
                    Button(
                        onClick = { showBroadcastDialog = true },
                        colors = ButtonDefaults.buttonColors(containerColor = StreamifyColors.Primary),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.Campaign, contentDescription = null, tint = Color.Black)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Broadcast", style = StreamifyType.TitleSmall, color = Color.Black)
                    }

                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                com.streamify.app.data.TrackRepository.refresh()
                                Toast.makeText(context, "Vector store & DB re-indexed", Toast.LENGTH_SHORT).show()
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = StreamifyColors.TextMain),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.Sync, contentDescription = null, tint = StreamifyColors.TextMain)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Re-index", style = StreamifyType.TitleSmall, color = StreamifyColors.TextMain)
                    }
                }
            }

            // User Explorer Section
            item {
                Text("Registered Profiles (${telemetry?.userList?.size ?: 0})", style = StreamifyType.TitleMedium, color = StreamifyColors.TextMain)
            }

            items(telemetry?.userList ?: emptyList()) { profile ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = StreamifyColors.BgCard),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (profile.avatarUrl.isNotBlank()) {
                            AsyncImage(
                                model = profile.avatarUrl,
                                contentDescription = null,
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(StreamifyColors.BgElevated),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = profile.displayName.take(1).uppercase(),
                                    style = StreamifyType.TitleMedium,
                                    color = StreamifyColors.Primary
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(profile.displayName, style = StreamifyType.TitleSmall, color = StreamifyColors.TextMain)
                                if (profile.isAdmin || profile.email == "sireenyadav@gmail.com") {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Surface(
                                        color = StreamifyColors.Primary.copy(alpha = 0.2f),
                                        shape = RoundedCornerShape(4.dp)
                                    ) {
                                        Text(
                                            "ADMIN",
                                            style = StreamifyType.Caption,
                                            color = StreamifyColors.Primary,
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                        )
                                    }
                                }
                            }
                            Text(profile.email, style = StreamifyType.Caption, color = StreamifyColors.TextSub)
                        }

                        Text("${profile.totalPlays} plays", style = StreamifyType.Caption, color = StreamifyColors.TextDimmed)
                    }
                }
            }
        }
    }

    if (showBroadcastDialog) {
        AlertDialog(
            onDismissRequest = { showBroadcastDialog = false },
            title = { Text("Global System Announcement", color = StreamifyColors.TextMain) },
            text = {
                Column {
                    Text("Broadcast an instant banner message to all live connected Streamify devices.", style = StreamifyType.Caption, color = StreamifyColors.TextSub)
                    Spacer(modifier = Modifier.height(12.dp))
                    TextField(
                        value = broadcastMsg,
                        onValueChange = { broadcastMsg = it },
                        placeholder = { Text("Enter announcement...") },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = StreamifyColors.BgElevated,
                            unfocusedContainerColor = StreamifyColors.BgElevated
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (broadcastMsg.isNotBlank()) {
                            scope.launch {
                                SupabaseClient.postAdminBroadcast(broadcastMsg)
                                Toast.makeText(context, "Announcement broadcasted!", Toast.LENGTH_SHORT).show()
                                showBroadcastDialog = false
                                broadcastMsg = ""
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = StreamifyColors.Primary)
                ) {
                    Text("Send Broadcast", color = Color.Black)
                }
            },
            dismissButton = {
                TextButton(onClick = { showBroadcastDialog = false }) {
                    Text("Cancel", color = StreamifyColors.TextSub)
                }
            },
            containerColor = StreamifyColors.BgCard
        )
    }
}
