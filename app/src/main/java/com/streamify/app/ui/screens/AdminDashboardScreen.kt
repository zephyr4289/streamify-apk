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
    var edgeStats by remember { mutableStateOf<com.streamify.app.data.remote.AdminEdgeMeshStats?>(null) }
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

            val edgeRes = SupabaseClient.getAdminEdgeComputeStats()
            edgeStats = edgeRes.getOrNull()

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
            val tabs = listOf("Telemetry", "Edge Mesh", "Users", "Jam Rooms", "Comments", "Broadcasts")
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
                                Icon(Icons.Filled.Campaign, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("New Broadcast", style = StreamifyType.BodyMedium)
                            }

                            OutlinedButton(
                                onClick = { selectedTab = 1 },
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF00E5FF)),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Filled.Memory, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Edge Mesh", style = StreamifyType.BodyMedium)
                            }
                        }
                    }
                }
            }

            // TAB 1: EDGE COMPUTE MESH
            1 -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = StreamifyDimens.SpaceLG),
                    verticalArrangement = Arrangement.spacedBy(StreamifyDimens.SpaceLG),
                    contentPadding = PaddingValues(bottom = 80.dp)
                ) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(StreamifyDimens.SpaceMD)
                        ) {
                            MetricCard(
                                title = "Active Nodes",
                                value = "${edgeStats?.activeNodesCount ?: 0}",
                                subtitle = "Computing Right Now",
                                icon = Icons.Filled.Memory,
                                accentColor = Color(0xFF00E5FF),
                                modifier = Modifier.weight(1f)
                            )
                            MetricCard(
                                title = "Mesh Pipeline",
                                value = "${edgeStats?.completedTasksCount ?: 0}",
                                subtitle = "of ${edgeStats?.totalTasksCount ?: 0} Verified",
                                icon = Icons.Filled.CheckCircle,
                                accentColor = StreamifyColors.Primary,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    item {
                        MetricCard(
                            title = "Bandwidth Inverted",
                            value = "${String.format("%.1f", edgeStats?.totalBandwidthSavedMb ?: 0.0)} MB",
                            subtitle = "Saved via Local-First Edge Caches ($0 Cloud Bill)",
                            icon = Icons.Filled.Speed,
                            accentColor = Color(0xFFFF9800),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    // Section 1: Active Nodes Right Now
                    item {
                        Text("Live Edge Nodes (${edgeStats?.activeNodes?.size ?: 0})", style = StreamifyType.TitleMedium, color = StreamifyColors.TextMain)
                    }

                    if (edgeStats?.activeNodes.isNullOrEmpty()) {
                        item {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = StreamifyColors.BgCard),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Box(modifier = Modifier.padding(24.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                    Text("No nodes computing right now (Workers run overnight when charging)", style = StreamifyType.BodyMedium, color = StreamifyColors.TextSub)
                                }
                            }
                        }
                    } else {
                        items(edgeStats!!.activeNodes) { node ->
                            Card(
                                colors = CardDefaults.cardColors(containerColor = StreamifyColors.BgCard),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .clip(CircleShape)
                                            .background(if (node.status == "COMPUTING") Color(0xFF00E5FF) else StreamifyColors.Primary)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(node.displayName, style = StreamifyType.TitleSmall, color = StreamifyColors.TextMain)
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("(${node.deviceId})", style = StreamifyType.Caption, color = StreamifyColors.TextDimmed)
                                        }
                                        if (node.currentTrackTitle.isNotBlank()) {
                                            Text("Active: ${node.currentTrackTitle}", style = StreamifyType.Caption, color = Color(0xFF00E5FF), maxLines = 1)
                                        } else {
                                            Text("Status: ${node.status}", style = StreamifyType.Caption, color = StreamifyColors.TextSub)
                                        }
                                    }

                                    Column(horizontalAlignment = Alignment.End) {
                                        Text("${node.totalContributions} Solved", style = StreamifyType.TitleSmall, color = StreamifyColors.Primary)
                                        Text("${String.format("%.1f", node.bandwidthSavedMb)} MB saved", style = StreamifyType.Caption, color = StreamifyColors.TextDimmed)
                                    }
                                }
                            }
                        }
                    }

                    // Section 2: Top Contributors Leaderboard
                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Top Mesh Contributors 🏆", style = StreamifyType.TitleMedium, color = StreamifyColors.TextMain)
                    }

                    if (edgeStats?.topContributors != null) {
                        items(edgeStats!!.topContributors.take(10)) { contributor ->
                            Card(
                                colors = CardDefaults.cardColors(containerColor = StreamifyColors.BgElevated),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Surface(
                                        color = Color(0xFF00E5FF).copy(alpha = 0.15f),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text(
                                            contributor.userEmail.take(2).uppercase().ifBlank { "ME" },
                                            style = StreamifyType.TitleSmall,
                                            color = Color(0xFF00E5FF),
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(contributor.displayName.ifBlank { contributor.userEmail.ifBlank { "Sovereign Node" } }, style = StreamifyType.TitleSmall, color = StreamifyColors.TextMain)
                                        Text(contributor.userEmail, style = StreamifyType.Caption, color = StreamifyColors.TextSub)
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text("${contributor.totalContributions} Tracks", style = StreamifyType.TitleSmall, color = StreamifyColors.Primary)
                                        Text("${String.format("%.1f", contributor.bandwidthSavedMb)} MB", style = StreamifyType.Caption, color = StreamifyColors.TextDimmed)
                                    }
                                }
                            }
                        }
                    }

                    // Section 3: Database Tables & Storage Telemetry
                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Database Table Telemetry & Row Counts", style = StreamifyType.TitleMedium, color = StreamifyColors.TextMain)
                    }

                    if (edgeStats?.tableStats != null) {
                        items(edgeStats!!.tableStats) { tbl ->
                            Card(
                                colors = CardDefaults.cardColors(containerColor = StreamifyColors.BgCard),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("public.${tbl.tableName}", style = StreamifyType.BodyMedium, color = StreamifyColors.TextMain)
                                    Text("${tbl.rowCount} rows", style = StreamifyType.Caption.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold), color = StreamifyColors.Primary)
                                }
                            }
                        }
                    }
                }
            }

            // TAB 2: USERS EXPLORER & ROLES
            2 -> {
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

            // TAB 3: JAM ROOMS MANAGEMENT
            3 -> {
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
                                        Icon(Icons.Filled.Delete, contentDescription = "Terminate", tint = StreamifyColors.ErrorRed)
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

            // TAB 4: COMMENTS MODERATION FEED
            4 -> {
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
                                        Text("@ ${(comment.timestampMs / 1000)}s", style = StreamifyType.Caption, color = StreamifyColors.TextDimmed)
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
                                    Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = StreamifyColors.ErrorRed)
                                }
                            }
                        }
                    }
                }
            }

            // TAB 5: BROADCASTS
            5 -> {
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
