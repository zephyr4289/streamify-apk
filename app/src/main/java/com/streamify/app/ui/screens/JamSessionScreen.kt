package com.streamify.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.streamify.app.jam.JamEngine
import com.streamify.app.ui.components.YtActiveEqualizer
import com.streamify.app.ui.components.YtThumbnail
import com.streamify.app.ui.theme.*
import com.streamify.app.viewmodel.JamUiState
import com.streamify.app.viewmodel.JamViewModel
import com.streamify.app.viewmodel.PlayerViewModel

@Composable
fun JamSessionScreen(
    jamViewModel: JamViewModel,
    playerViewModel: PlayerViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val jamState by jamViewModel.uiState.collectAsState()
    val playerState by playerViewModel.playerState.collectAsState()
    var inputRoomCode by remember { mutableStateOf("") }
    var showAddSongSheet by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()

    // Invite deep link: streamify://jam/CODE — auto-populate and auto-join.
    LaunchedEffect(Unit) {
        JamEngine.pendingInviteCode?.let { code ->
            inputRoomCode = code
        }
    }
    LaunchedEffect(jamState) {
        val pending = JamEngine.pendingInviteCode ?: return@LaunchedEffect
        if (jamState is JamUiState.Idle && pending.isNotBlank()) {
            JamEngine.pendingInviteCode = null
            jamViewModel.joinJam(pending, playerViewModel)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgBase)
            .statusBarsPadding()
            .verticalScroll(scrollState)
            .padding(bottom = 120.dp)
    ) {
        // Top App Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
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
                text = "Streamify Jam",
                style = LocalAppTypography.current.headlineMedium.copy(fontSize = 18.sp),
                color = TextMain
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        when (val state = jamState) {
            is JamUiState.Idle -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "🎧 Listen Together in Real-Time",
                        style = LocalAppTypography.current.headlineLarge.copy(fontSize = 22.sp),
                        color = TextMain,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Synchronize playback with friends anywhere in the world. Start a room or enter a 6-digit PIN code.",
                        style = LocalAppTypography.current.songArtist,
                        color = TextSecondary,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(32.dp))

                    // Start Jam Room Button
                    Button(
                        onClick = {
                            jamViewModel.startJam(playerState.currentTrack, playerViewModel.currentPositionMs())
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = ActiveControl),
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Radio,
                            contentDescription = null,
                            tint = TextOnActiveChip,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Start a New Jam Room",
                            style = LocalAppTypography.current.chipText.copy(fontSize = 14.sp),
                            color = TextOnActiveChip
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        HorizontalDivider(modifier = Modifier.weight(1f), color = Divider)
                        Text(
                            text = " OR JOIN ROOM ",
                            style = LocalAppTypography.current.songArtist.copy(
                                fontSize = 11.sp,
                                letterSpacing = 1.sp
                            ),
                            color = TextTertiary,
                            modifier = Modifier.padding(horizontal = 12.dp)
                        )
                        HorizontalDivider(modifier = Modifier.weight(1f), color = Divider)
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    OutlinedTextField(
                        value = inputRoomCode,
                        onValueChange = { if (it.length <= 6) inputRoomCode = it.uppercase() },
                        placeholder = { Text("Enter 6-char PIN (e.g. STRM9X)", color = TextTertiary) },
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

                    Spacer(modifier = Modifier.height(16.dp))

                    OutlinedButton(
                        onClick = { jamViewModel.joinJam(inputRoomCode, playerViewModel) },
                        enabled = inputRoomCode.length == 6,
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = ActiveControl),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    ) {
                        Text(
                            text = "Join with PIN",
                            style = LocalAppTypography.current.chipText.copy(fontSize = 14.sp),
                            color = if (inputRoomCode.length == 6) ActiveControl else TextTertiary
                        )
                    }
                }
            }

            is JamUiState.Loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = Primary, strokeWidth = 3.dp)
                }
            }

            is JamUiState.Active -> {
                val session = state.session
                val jamQueue by jamViewModel.jamQueue.collectAsState()
                val roomMembers by jamViewModel.members.collectAsState()
                val connStatus by jamViewModel.connStatus.collectAsState()
                val controlPolicy by jamViewModel.policy.collectAsState()

                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    // Active Room Banner Card
                    Surface(
                        color = BgSurfaceElevated,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = if (state.isHost) "HOSTING JAM SESSION" else "CONNECTED TO JAM SESSION",
                                style = LocalAppTypography.current.songArtist.copy(
                                    fontSize = 11.sp,
                                    letterSpacing = 0.5.sp
                                ),
                                color = Primary
                            )

                            Spacer(modifier = Modifier.height(6.dp))
                            // Lockstep connection telemetry
                            Surface(
                                color = when (connStatus) {
                                    JamEngine.ConnStatus.LIVE -> Color(0xFF10B981).copy(alpha = 0.15f)
                                    JamEngine.ConnStatus.DEGRADED -> Color(0xFFF59E0B).copy(alpha = 0.18f)
                                    JamEngine.ConnStatus.OFFLINE -> Color(0xFFEF4444).copy(alpha = 0.16f)
                                },
                                shape = RoundedCornerShape(20.dp)
                            ) {
                                Text(
                                    text = when (connStatus) {
                                        JamEngine.ConnStatus.LIVE -> "● LOCKSTEP LIVE"
                                        JamEngine.ConnStatus.DEGRADED -> "● RECOVERING CLOCK…"
                                        JamEngine.ConnStatus.OFFLINE -> "○ OFFLINE"
                                    },
                                    style = LocalAppTypography.current.songArtist.copy(
                                        fontSize = 10.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold
                                    ),
                                    color = when (connStatus) {
                                        JamEngine.ConnStatus.LIVE -> Color(0xFF10B981)
                                        JamEngine.ConnStatus.DEGRADED -> Color(0xFFF59E0B)
                                        JamEngine.ConnStatus.OFFLINE -> Color(0xFFEF4444)
                                    },
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                                )
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            Text(
                                text = session.sessionCode,
                                style = LocalAppTypography.current.headlineLarge.copy(
                                    fontSize = 38.sp,
                                    fontWeight = FontWeight.Black,
                                    letterSpacing = 2.sp
                                ),
                                color = ActiveControl
                            )

                            Spacer(modifier = Modifier.height(6.dp))

                            TextButton(onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("Jam PIN", session.sessionCode))
                                Toast.makeText(context, "PIN Copied!", Toast.LENGTH_SHORT).show()
                            }) {
                                Icon(
                                    imageVector = Icons.Filled.ContentCopy,
                                    contentDescription = "Copy",
                                    tint = TextSecondary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Copy PIN to Share", color = TextSecondary, style = LocalAppTypography.current.chipText)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Universal Synchronized Playback Control Card
                    Surface(
                        color = BgSurfaceElevated,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(52.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                ) {
                                    YtThumbnail(
                                        url = playerState.currentTrack?.coverArtPath,
                                        size = 52.dp,
                                        cornerRadius = 6.dp
                                    )
                                    if (playerState.isPlaying) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .background(Color.Black.copy(alpha = 0.55f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            YtActiveEqualizer()
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = playerState.currentTrack?.title ?: "No track playing",
                                        style = LocalAppTypography.current.songTitle.copy(fontSize = 15.sp),
                                        color = TextMain,
                                        maxLines = 1
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = playerState.currentTrack?.artist ?: "Streamify Radio",
                                        style = LocalAppTypography.current.songArtist.copy(fontSize = 12.sp),
                                        color = TextSecondary,
                                        maxLines = 1
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            // Universal Control Buttons (Universal for all connected friends)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(
                                    onClick = { playerViewModel.skipPrevious() },
                                    modifier = Modifier.size(40.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.SkipPrevious,
                                        contentDescription = "Previous",
                                        tint = TextMain,
                                        modifier = Modifier.size(26.dp)
                                    )
                                }

                                Spacer(modifier = Modifier.width(20.dp))

                                IconButton(
                                    onClick = { playerViewModel.togglePlayPause() },
                                    modifier = Modifier
                                        .size(48.dp)
                                        .background(ActiveControl, CircleShape)
                                ) {
                                    Icon(
                                        imageVector = if (playerState.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                        contentDescription = "Play/Pause",
                                        tint = TextOnActiveChip,
                                        modifier = Modifier.size(28.dp)
                                    )
                                }

                                Spacer(modifier = Modifier.width(20.dp))

                                IconButton(
                                    onClick = {
                                        val nextInJamQ = jamQueue.firstOrNull()
                                        if (nextInJamQ != null) {
                                            jamViewModel.removeFromJamQueue(nextInJamQ)
                                            playerViewModel.playTrack(nextInJamQ)
                                        } else {
                                            playerViewModel.skipNext()
                                        }
                                    },
                                    modifier = Modifier.size(40.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.SkipNext,
                                        contentDescription = "Next",
                                        tint = TextMain,
                                        modifier = Modifier.size(26.dp)
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // Shared Collaborative Jam Queue
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "SHARED JAM QUEUE (${jamQueue.size})",
                            style = LocalAppTypography.current.songArtist.copy(
                                fontSize = 11.sp,
                                letterSpacing = 0.5.sp,
                                fontWeight = FontWeight.Bold
                            ),
                            color = TextSecondary
                        )
                        Button(
                            onClick = { showAddSongSheet = true },
                            colors = ButtonDefaults.buttonColors(containerColor = ActiveControl),
                            shape = RoundedCornerShape(16.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Add,
                                contentDescription = "Add Songs",
                                tint = TextOnActiveChip,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Add Songs",
                                style = LocalAppTypography.current.chipText.copy(fontSize = 12.sp),
                                color = TextOnActiveChip
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Surface(
                        color = BgSurfaceElevated,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (jamQueue.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(20.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "Jam Queue is empty\nFriends can add tracks from Search or Library",
                                    style = LocalAppTypography.current.songArtist.copy(fontSize = 12.sp),
                                    color = TextTertiary,
                                    textAlign = TextAlign.Center
                                )
                            }
                        } else {
                            Column(modifier = Modifier.padding(vertical = 6.dp)) {
                                jamQueue.forEachIndexed { index, queueTrack ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "${index + 1}",
                                            style = LocalAppTypography.current.songArtist.copy(fontSize = 12.sp),
                                            color = TextTertiary,
                                            modifier = Modifier.width(20.dp)
                                        )

                                        YtThumbnail(
                                            url = queueTrack.coverArtPath,
                                            size = 38.dp,
                                            cornerRadius = 4.dp
                                        )

                                        Spacer(modifier = Modifier.width(10.dp))

                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = queueTrack.title,
                                                style = LocalAppTypography.current.songTitle.copy(fontSize = 13.sp),
                                                color = TextMain,
                                                maxLines = 1
                                            )
                                            Text(
                                                text = queueTrack.artist,
                                                style = LocalAppTypography.current.songArtist.copy(fontSize = 11.sp),
                                                color = TextSecondary,
                                                maxLines = 1
                                            )
                                        }

                                        IconButton(
                                            onClick = {
                                                jamViewModel.removeFromJamQueue(queueTrack)
                                                playerViewModel.playTrack(queueTrack)
                                            },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.PlayArrow,
                                                contentDescription = "Play Next",
                                                tint = ActiveControl,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }

                                        IconButton(
                                            onClick = { jamViewModel.removeFromJamQueue(queueTrack) },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.Close,
                                                contentDescription = "Remove",
                                                tint = TextTertiary,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // Active Listeners
                    Text(
                        text = "LISTENERS IN ROOM",
                        style = LocalAppTypography.current.songArtist.copy(
                            fontSize = 11.sp,
                            letterSpacing = 0.5.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        color = TextSecondary
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // Live presence roster (Lockstep Engine): avatars, host crown, self tag
                    val roster = if (roomMembers.isEmpty())
                        listOf(JamEngine.Member(session.hostUserId, "Host", null, true, 0L))
                    else roomMembers.sortedByDescending { it.isHost }

                    LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        items(roster, key = { it.userId }) { m ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Box {
                                    if (!m.avatarUrl.isNullOrBlank()) {
                                        AsyncImage(
                                            model = m.avatarUrl,
                                            contentDescription = m.name,
                                            modifier = Modifier
                                                .size(52.dp)
                                                .clip(CircleShape)
                                                .border(
                                                    if (m.isHost) 2.dp else 1.dp,
                                                    if (m.isHost) Primary else TextTertiary,
                                                    CircleShape
                                                )
                                        )
                                    } else {
                                        Box(
                                            modifier = Modifier
                                                .size(52.dp)
                                                .clip(CircleShape)
                                                .background(BgSurfaceElevated)
                                                .border(
                                                    if (m.isHost) 2.dp else 1.dp,
                                                    if (m.isHost) Primary else TextTertiary,
                                                    CircleShape
                                                ),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = m.name.take(1).uppercase(),
                                                color = TextMain,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 18.sp
                                            )
                                        }
                                    }
                                    if (m.isHost) {
                                        Box(
                                            modifier = Modifier
                                                .align(Alignment.TopEnd)
                                                .size(20.dp)
                                                .clip(CircleShape)
                                                .background(Primary),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(text = "★", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Black)
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = m.name.ifBlank { "Listener" },
                                    style = LocalAppTypography.current.songArtist.copy(fontSize = 11.sp),
                                    fontWeight = if (m.userId == session.hostUserId) FontWeight.Bold else FontWeight.Normal,
                                    color = TextMain,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(horizontal = 4.dp)
                                )
                                Text(
                                    text = when {
                                        m.isHost -> "HOST"
                                        m.userId == com.streamify.app.data.remote.SupabaseClient.currentUser.value?.id -> "YOU"
                                        else -> "LISTENER"
                                    },
                                    style = LocalAppTypography.current.songArtist.copy(fontSize = 9.sp, letterSpacing = 0.8.sp),
                                    color = if (m.isHost) Primary else TextSecondary
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    // INVITE + CONTROL POLICY row (Spotify-grade room management)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = {
                                val text = jamViewModel.inviteShareText()
                                val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(android.content.Intent.EXTRA_TEXT, text)
                                }
                                context.startActivity(
                                    android.content.Intent.createChooser(send, "Invite to Jam")
                                )
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Primary, contentColor = Color.White),
                            shape = RoundedCornerShape(24.dp),
                            modifier = Modifier.weight(1f).height(48.dp)
                        ) {
                            Icon(Icons.Filled.PersonAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("INVITE", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }

                        if (state.isHost) {
                            Surface(
                                onClick = { jamViewModel.cycleControlPolicy() },
                                color = if (controlPolicy == JamEngine.ControlPolicy.EVERYONE)
                                    Color(0xFF10B981).copy(alpha = 0.15f)
                                else Primary.copy(alpha = 0.16f),
                                shape = RoundedCornerShape(24.dp),
                                modifier = Modifier.weight(1f).height(48.dp)
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center,
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    Text(
                                        text = if (controlPolicy == JamEngine.ControlPolicy.EVERYONE) "EVERYONE CONTROLS" else "HOST CONTROLS",
                                        fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 0.6.sp,
                                        color = if (controlPolicy == JamEngine.ControlPolicy.EVERYONE) Color(0xFF10B981) else Primary
                                    )
                                    Text(
                                        text = "tap to switch",
                                        fontSize = 9.sp, color = TextSecondary
                                    )
                                }
                            }
                        } else {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // Leave Session Button
                    OutlinedButton(
                        onClick = { jamViewModel.leaveJam(endForEveryone = state.isHost) },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Primary),
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.ExitToApp,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Leave Jam Session",
                            style = LocalAppTypography.current.chipText.copy(fontSize = 14.sp),
                            color = Primary
                        )
                    }
                }
            }

            is JamUiState.Error -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 80.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = state.message,
                            style = LocalAppTypography.current.songArtist,
                            color = Primary
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = { jamViewModel.leaveJam() },
                            colors = ButtonDefaults.buttonColors(containerColor = BgSurfaceElevated)
                        ) {
                            Text("Try Again", color = TextMain)
                        }
                    }
                }
            }
        }

        if (showAddSongSheet) {
            JamAddSongModalBottomSheet(
                onDismiss = { showAddSongSheet = false },
                onAddTrack = { track -> jamViewModel.addToJamQueue(track) }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun JamAddSongModalBottomSheet(
    onDismiss: () -> Unit,
    onAddTrack: (com.streamify.app.data.models.Track) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    val allLocalTracks by com.streamify.app.data.TrackRepository.allTracks.collectAsState()
    var onlineResults by remember { mutableStateOf<List<com.streamify.app.data.models.Track>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    val context = LocalContext.current

    LaunchedEffect(searchQuery) {
        val q = searchQuery.trim()
        if (q.length >= 2) {
            isSearching = true
            try {
                val res = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.streamify.app.data.network.YouTubeMusicSearchApi.search(q, maxResults = 12)
                }
                onlineResults = res.map { r ->
                    com.streamify.app.data.models.Track(
                        id = -(r.title + r.uploader).hashCode(),
                        title = r.title,
                        artist = r.uploader,
                        album = "Jam Queue",
                        durationSec = r.duration,
                        filepath = r.url,
                        coverArtPath = r.thumbnail,
                        bpm = 120f,
                        key = "C",
                        lyricsPath = null,
                        source = "online_stream"
                    )
                }
            } catch (e: Exception) {
                onlineResults = emptyList()
            } finally {
                isSearching = false
            }
        } else {
            onlineResults = emptyList()
            isSearching = false
        }
    }

    val displayTracks = remember(searchQuery, onlineResults, allLocalTracks) {
        if (searchQuery.isNotBlank()) {
            if (onlineResults.isNotEmpty()) onlineResults else allLocalTracks.filter {
                it.title.contains(searchQuery, ignoreCase = true) || it.artist.contains(searchQuery, ignoreCase = true)
            }
        } else {
            allLocalTracks.take(20)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = BgSurfaceElevated,
        dragHandle = { BottomSheetDefaults.DragHandle(color = TextTertiary) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .padding(horizontal = 16.dp)
        ) {
            Text(
                text = "Add Songs to Jam",
                style = LocalAppTypography.current.headlineLarge.copy(fontSize = 18.sp),
                color = TextMain
            )
            Spacer(modifier = Modifier.height(12.dp))

            // Search Box
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search songs or artists...", color = TextTertiary, fontSize = 14.sp) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = TextSecondary) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear", tint = TextSecondary)
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Primary,
                    unfocusedBorderColor = Divider,
                    focusedTextColor = TextMain,
                    unfocusedTextColor = TextMain,
                    focusedContainerColor = BgCard,
                    unfocusedContainerColor = BgCard
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(12.dp))

            if (isSearching) {
                Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Primary, strokeWidth = 2.5.dp, modifier = Modifier.size(24.dp))
                }
            } else {
                androidx.compose.foundation.lazy.LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    items(displayTracks) { track ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(BgCard)
                                .clickable {
                                    onAddTrack(track)
                                    Toast.makeText(context, "Added ${track.title} to Jam", Toast.LENGTH_SHORT).show()
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            YtThumbnail(
                                url = track.coverArtPath,
                                size = 42.dp,
                                cornerRadius = 6.dp,
                                title = track.title,
                                artist = track.artist
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = track.title,
                                    style = LocalAppTypography.current.songTitle.copy(fontSize = 14.sp),
                                    color = TextMain,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = track.artist,
                                    style = LocalAppTypography.current.songArtist.copy(fontSize = 12.sp),
                                    color = TextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            IconButton(
                                onClick = {
                                    onAddTrack(track)
                                    Toast.makeText(context, "Added ${track.title} to Jam", Toast.LENGTH_SHORT).show()
                                }
                            ) {
                                Icon(Icons.Filled.AddCircle, contentDescription = "Add", tint = ActiveControl)
                            }
                        }
                    }
                }
            }
        }
    }
}
