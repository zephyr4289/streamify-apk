package com.streamify.app.ui.screens

import android.widget.Toast
import androidx.compose.animation.*
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.streamify.app.data.remote.AdminCommentItem
import com.streamify.app.data.remote.AdminJamSession
import com.streamify.app.data.remote.AdminTelemetry
import com.streamify.app.data.remote.SupabaseClient
import com.streamify.app.data.remote.UserProfile
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
    val clipboardManager = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val user by SupabaseClient.currentUser.collectAsState()

    var selectedTab by remember { mutableStateOf(0) }
    var telemetry by remember { mutableStateOf<AdminTelemetry?>(null) }
    var jamSessions by remember { mutableStateOf<List<AdminJamSession>>(emptyList()) }
    var recentComments by remember { mutableStateOf<List<AdminCommentItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var searchQuery by remember { mutableStateOf("") }
    
    var broadcastMsg by remember { mutableStateOf("") }
    var showBroadcastDialog by remember { mutableStateOf(false) }

    fun refreshAll() {
        scope.launch {
            isLoading = true
            val telemRes = SupabaseClient.getAdminTelemetry()
            telemetry = telemRes.getOrNull()

            val jamRes = SupabaseClient.getAdminJamSessions()
            jamSessions = jamRes.getOrDefault(emptyList())

            val commentRes = SupabaseClient.getAdminRecentComments(50)
            recentComments = commentRes.getOrDefault(emptyList())

            isLoading = false
        }
    }

    LaunchedEffect(Unit) {
        refreshAll()
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

            IconButton(onClick = { refreshAll() }) {
                Icon(Icons.Filled.Refresh, contentDescription = "Refresh", tint = StreamifyColors.Primary)
            }
        }

        Spacer(modifier = Modifier.height(StreamifyDimens.SpaceSM))

        // Navigation Tabs
        ScrollableTabRow(
            selectedTabIndex = selectedTab,
            containerColor = StreamifyColors.BgBase,
            contentColor = StreamifyColors.Primary,
            edgePadding = StreamifyDimens.SpaceLG,
            divider = {}
        ) {
            val tabs = listOf("Telemetry", "Users", "Jam Rooms", "Comments", "Broadcasts")
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = {
                        Text(
                            title,
                            style = StreamifyType.TitleSmall,
                            color = if (selectedTab == index) StreamifyColors.Primary else StreamifyColors.TextSub
                        )
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(StreamifyDimens.SpaceSM))

        when (selectedTab) {
            // TAB 0: TELEMETRY & SYSTEM HEALTH
            0 -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = StreamifyDimens.SpaceLG),
                    verticalArrangement = Arrangement.spacedBy(StreamifyDimens.SpaceLG),
                    contentPadding = PaddingValues(bottom = 80.dp)
                ) {
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
                                        Text("Database & Vector Engine", style = StreamifyType.TitleSmall, color = StreamifyColors.TextMain)
                                        Text(telemetry?.engineMode ?: "PostgreSQL 15 + pgvector", style = StreamifyType.Caption, color = StreamifyColors.TextSub)
                                    }
                                }
                                Text("${telemetry?.latencyMs ?: 24} ms", style = StreamifyType.TitleMedium, color = StreamifyColors.Primary)
                            }
                        }
                    }

                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(StreamifyDimens.SpaceMD)
                        ) {
                            MetricCard(
                                title = "Users",
                                value = "${telemetry?.totalUsers ?: 0}",
                                subtitle = "${telemetry?.dau24h ?: 0} Active 24h",
                                icon = Icons.Filled.People,
                                accentColor = Color(0xFF1DB954),
                                modifier = Modifier.weight(1f)
                            )
                            MetricCard(
                                title = "Jam Rooms",
                                value = "${telemetry?.activeJamSessions ?: 0}",
                                subtitle = "Live Rooms",
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
                                title = "Cloud Songs",
                                value = "${telemetry?.totalTracks ?: 0}",
                                subtitle = "Indexed Vectors",
                                icon = Icons.Filled.MusicNote,
                                accentColor = Color(0xFFE8115B),
                                modifier = Modifier.weight(1f)
                            )
                            MetricCard(
                                title = "Playlists",
                                value = "${telemetry?.totalPlaylists ?: 0}",
                                subtitle = "Public & Collab",
                                icon = Icons.Filled.QueueMusic,
                                accentColor = Color(0xFFBC5900),
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
                                title = "Comments",
                                value = "${telemetry?.totalComments ?: 0}",
                                subtitle = "Reactions",
                                icon = Icons.Filled.Comment,
                                accentColor = Color(0xFF2E77D0),
                                modifier = Modifier.weight(1f)
                            )
                            MetricCard(
                                title = "Total Plays",
                                value = "${telemetry?.totalPlays ?: 0}",
                                subtitle = "Streams",
                                icon = Icons.Filled.PlayArrow,
                                accentColor = Color(0xFFE91E63),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    // Admin Quick Actions
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
                                        Toast.makeText(context, "Local Vector store & DB re-indexed", Toast.LENGTH_SHORT).show()
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
                }
            }

            // TAB 1: USERS EXPLORER & ROLES
            1 -> {
                val filteredUsers = remember(searchQuery, telemetry?.userList) {
                    val list = telemetry?.userList ?: emptyList()
                    if (searchQuery.isBlank()) list
                    else list.filter {
                        it.displayName.contains(searchQuery, ignoreCase = true) ||
                        it.email.contains(searchQuery, ignoreCase = true)
                    }
                }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = StreamifyDimens.SpaceLG),
                    verticalArrangement = Arrangement.spacedBy(StreamifyDimens.SpaceMD),
                    contentPadding = PaddingValues(bottom = 80.dp)
                ) {
                    item {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Search users by name or email...") },
                            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = StreamifyColors.TextSub) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = StreamifyColors.BgElevated,
                                unfocusedContainerColor = StreamifyColors.BgElevated,
                                focusedBorderColor = StreamifyColors.Primary
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    item {
                        Text("Registered Profiles (${filteredUsers.size})", style = StreamifyType.TitleMedium, color = StreamifyColors.TextMain)
                    }

                    items(filteredUsers) { profile ->
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
                                        if (profile.isAdmin || profile.email.equals("sireenyadav@gmail.com", ignoreCase = true)) {
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
                                    Text("${profile.totalPlays} plays • ${(profile.listeningSeconds / 60)} mins", style = StreamifyType.Caption, color = StreamifyColors.TextDimmed)
                                }

                                if (!profile.email.equals("sireenyadav@gmail.com", ignoreCase = true)) {
                                    IconButton(onClick = {
                                        scope.launch {
                                            val newRole = !profile.isAdmin
                                            val res = SupabaseClient.setUserAdminRole(profile.id, newRole)
                                            if (res.isSuccess) {
                                                Toast.makeText(context, "Updated role for ${profile.displayName}", Toast.LENGTH_SHORT).show()
                                                refreshAll()
                                            } else {
                                                Toast.makeText(context, "Failed to update role", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }) {
                                        Icon(
                                            imageVector = if (profile.isAdmin) Icons.Filled.AdminPanelSettings else Icons.Filled.PersonAddAlt1,
                                            contentDescription = "Toggle Admin",
                                            tint = if (profile.isAdmin) StreamifyColors.Primary else StreamifyColors.TextSub
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // TAB 2: JAM ROOMS MANAGEMENT
            2 -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = StreamifyDimens.SpaceLG),
                    verticalArrangement = Arrangement.spacedBy(StreamifyDimens.SpaceMD),
                    contentPadding = PaddingValues(bottom = 80.dp)
                ) {
                    item {
                        Text("Active Listening Rooms (${jamSessions.size})", style = StreamifyType.TitleMedium, color = StreamifyColors.TextMain)
                    }

                    if (jamSessions.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(Icons.Filled.Podcasts, contentDescription = null, tint = StreamifyColors.TextDimmed, modifier = Modifier.size(48.dp))
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text("No active Jam rooms currently open", style = StreamifyType.BodyMedium, color = StreamifyColors.TextSub)
                                }
                            }
                        }
                    }

                    items(jamSessions) { session ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = StreamifyColors.BgCard),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Surface(
                                            color = Color(0xFF7358FF).copy(alpha = 0.2f),
                                            shape = RoundedCornerShape(6.dp),
                                            modifier = Modifier.clickable {
                                                clipboardManager.setText(AnnotatedString(session.sessionCode))
                                                Toast.makeText(context, "PIN ${session.sessionCode} copied!", Toast.LENGTH_SHORT).show()
                                            }
                                        ) {
                                            Text(
                                                text = session.sessionCode,
                                                style = StreamifyType.TitleSmall,
                                                color = Color(0xFF7358FF),
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(session.hostName, style = StreamifyType.TitleSmall, color = StreamifyColors.TextMain)
                                    }

                                    IconButton(onClick = {
                                        scope.launch {
                                            val res = SupabaseClient.terminateJamSessionAdmin(session.id)
                                            if (res.isSuccess) {
                                                Toast.makeText(context, "Jam room closed", Toast.LENGTH_SHORT).show()
                                                refreshAll()
                                            } else {
                                                Toast.makeText(context, "Failed to terminate room", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }) {
                                        Icon(Icons.Filled.Delete, contentDescription = "Terminate", tint = StreamifyColors.Error)
                                    }
                                }

                                Spacer(modifier = Modifier.height(8.dp))
                                Text("🎵 ${session.currentTrackTitle} • ${session.currentTrackArtist}", style = StreamifyType.BodyMedium, color = StreamifyColors.TextMain)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text("👥 ${session.participantCount} connected listener(s) • ${if (session.isPlaying) "▶ Playing" else "⏸ Paused"}", style = StreamifyType.Caption, color = StreamifyColors.TextSub)
                            }
                        }
                    }
                }
            }

            // TAB 3: COMMENTS MODERATION FEED
            3 -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = StreamifyDimens.SpaceLG),
                    verticalArrangement = Arrangement.spacedBy(StreamifyDimens.SpaceMD),
                    contentPadding = PaddingValues(bottom = 80.dp)
                ) {
                    item {
                        Text("Live Song Comments (${recentComments.size})", style = StreamifyType.TitleMedium, color = StreamifyColors.TextMain)
                    }

                    if (recentComments.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("No recent comments to moderate", style = StreamifyType.BodyMedium, color = StreamifyColors.TextSub)
                            }
                        }
                    }

                    items(recentComments) { comment ->
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
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(comment.userName, style = StreamifyType.TitleSmall, color = StreamifyColors.Primary)
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("on ${comment.trackTitle}", style = StreamifyType.Caption, color = StreamifyColors.TextSub)
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("@ ${com.streamify.app.ui.components.formatTimestamp(comment.timestampMs)}", style = StreamifyType.Caption, color = StreamifyColors.TextDimmed)
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(comment.commentText, style = StreamifyType.BodyMedium, color = StreamifyColors.TextMain)
                                }

                                IconButton(onClick = {
                                    scope.launch {
                                        val res = SupabaseClient.deleteCommentAdmin(comment.id)
                                        if (res.isSuccess) {
                                            Toast.makeText(context, "Comment removed", Toast.LENGTH_SHORT).show()
                                            refreshAll()
                                        } else {
                                            Toast.makeText(context, "Failed to delete comment", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }) {
                                    Icon(Icons.Filled.DeleteOutline, contentDescription = "Delete", tint = StreamifyColors.Error)
                                }
                            }
                        }
                    }
                }
            }

            // TAB 4: BROADCASTS
            4 -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = StreamifyDimens.SpaceLG),
                    verticalArrangement = Arrangement.spacedBy(StreamifyDimens.SpaceLG),
                    contentPadding = PaddingValues(bottom = 80.dp)
                ) {
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = StreamifyColors.BgCard),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text("New Global Announcement", style = StreamifyType.TitleMedium, color = StreamifyColors.TextMain)
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("Broadcast an instant announcement banner to all connected Streamify apps worldwide.", style = StreamifyType.Caption, color = StreamifyColors.TextSub)
                                Spacer(modifier = Modifier.height(14.dp))

                                OutlinedTextField(
                                    value = broadcastMsg,
                                    onValueChange = { broadcastMsg = it },
                                    placeholder = { Text("Enter announcement message...") },
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedContainerColor = StreamifyColors.BgElevated,
                                        unfocusedContainerColor = StreamifyColors.BgElevated,
                                        focusedBorderColor = StreamifyColors.Primary
                                    ),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                )

                                Spacer(modifier = Modifier.height(14.dp))

                                Button(
                                    onClick = {
                                        if (broadcastMsg.isNotBlank()) {
                                            scope.launch {
                                                val res = SupabaseClient.postAdminBroadcast(broadcastMsg)
                                                if (res.isSuccess) {
                                                    Toast.makeText(context, "Broadcast sent successfully!", Toast.LENGTH_SHORT).show()
                                                    broadcastMsg = ""
                                                    refreshAll()
                                                } else {
                                                    Toast.makeText(context, "Failed to post broadcast", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = StreamifyColors.Primary),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Filled.Campaign, contentDescription = null, tint = Color.Black)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Publish Global Banner", style = StreamifyType.TitleSmall, color = Color.Black)
                                }
                            }
                        }
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
