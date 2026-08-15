package com.streamify.app.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.streamify.app.data.remote.SupabaseClient
import com.streamify.app.data.remote.UserProfile
import com.streamify.app.ui.theme.StreamifyColors
import com.streamify.app.ui.theme.StreamifyDimens
import com.streamify.app.ui.theme.StreamifyType
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserProfileScreen(
    onBack: () -> Unit,
    onNavigateToWrapped: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val user by SupabaseClient.currentUser.collectAsState()

    var showEditDialog by remember { mutableStateOf(false) }
    var editName by remember(user) { mutableStateOf(user?.displayName ?: "") }
    var editBio by remember(user) { mutableStateOf(user?.bio ?: "") }
    var editGenre by remember(user) { mutableStateOf(user?.favoriteGenre ?: "All") }
    var isSaving by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Profile & Cloud Sync", style = StreamifyType.HeadlineMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = StreamifyColors.TextMain)
                    }
                },
                actions = {
                    IconButton(onClick = { showEditDialog = true }) {
                        Icon(Icons.Default.Edit, contentDescription = "Edit Profile", tint = StreamifyColors.Primary)
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
            Spacer(modifier = Modifier.height(16.dp))

            // Large User Avatar
            val currentUser = user
            if (currentUser != null && currentUser.avatarUrl.isNotBlank()) {
                AsyncImage(
                    model = currentUser.avatarUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .size(96.dp)
                        .clip(CircleShape)
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(96.dp)
                        .clip(CircleShape)
                        .background(StreamifyColors.PrimaryDark),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        currentUser?.displayName?.take(1)?.uppercase() ?: "U",
                        style = StreamifyType.HeadlineLarge,
                        color = StreamifyColors.TextMain,
                        fontSize = 36.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                currentUser?.displayName ?: "Streamify Listener",
                style = StreamifyType.HeadlineMedium,
                color = StreamifyColors.TextMain
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                currentUser?.email ?: "Offline Mode",
                style = StreamifyType.Caption,
                color = StreamifyColors.TextSub
            )

            Spacer(modifier = Modifier.height(6.dp))

            // Bio
            Text(
                currentUser?.bio ?: "Music lover on Streamify 🎧",
                style = StreamifyType.BodyMedium,
                color = StreamifyColors.TextSub
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Cloud Sync Status Pill
            Surface(
                color = StreamifyColors.Primary.copy(alpha = 0.15f),
                shape = RoundedCornerShape(20.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.CloudDone,
                        contentDescription = null,
                        tint = StreamifyColors.Primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        "Cloud Sync Enabled (Supabase Postgres)",
                        style = StreamifyType.CaptionBold,
                        color = StreamifyColors.Primary
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Listening Telemetry & Stats Cards
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    color = StreamifyColors.BgCard,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("FAVORITE GENRE", style = StreamifyType.Caption, color = StreamifyColors.TextSub)
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(currentUser?.favoriteGenre ?: "All", style = StreamifyType.HeadlineSmall, color = StreamifyColors.TextMain)
                    }
                }

                Surface(
                    color = StreamifyColors.BgCard,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("TOTAL PLAYS", style = StreamifyType.Caption, color = StreamifyColors.TextSub)
                        Spacer(modifier = Modifier.height(6.dp))
                        Text("${currentUser?.totalPlays ?: 142}", style = StreamifyType.HeadlineSmall, color = StreamifyColors.Primary)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Streamify Wrapped Banner Button
            Surface(
                color = StreamifyColors.Primary,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onNavigateToWrapped() }
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("Streamify Wrapped 2026 🎉", style = StreamifyType.BodyLargeBold, color = StreamifyColors.BgBase)
                        Text("View your yearly & monthly listening stats", style = StreamifyType.Caption, color = StreamifyColors.BgBase.copy(alpha = 0.8f))
                    }
                    Icon(Icons.Default.ArrowForward, contentDescription = null, tint = StreamifyColors.BgBase)
                }
            }
        }
    }

    // Edit Profile Modal Dialog
    if (showEditDialog) {
        AlertDialog(
            onDismissRequest = { showEditDialog = false },
            title = { Text("Edit Profile", style = StreamifyType.HeadlineSmall) },
            text = {
                Column {
                    OutlinedTextField(
                        value = editName,
                        onValueChange = { editName = it },
                        label = { Text("Display Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = editBio,
                        onValueChange = { editBio = it },
                        label = { Text("Bio") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = editGenre,
                        onValueChange = { editGenre = it },
                        label = { Text("Favorite Genre") },
                        singleLine = true,
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
                    colors = ButtonDefaults.buttonColors(containerColor = StreamifyColors.Primary, contentColor = StreamifyColors.BgBase)
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditDialog = false }) {
                    Text("Cancel", color = StreamifyColors.TextSub)
                }
            },
            containerColor = StreamifyColors.BgElevated
        )
    }
}
