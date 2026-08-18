package com.streamify.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    val scrollState = rememberScrollState()

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
                            jamViewModel.startJam(playerState.currentTrack, playerState.currentPosition)
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
                        Text(
                            text = "Collaborative",
                            style = LocalAppTypography.current.songArtist.copy(fontSize = 11.sp),
                            color = Primary
                        )
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

                    val participants = session.participantIds.ifEmpty { listOf("Host") }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(participants) { participant ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(CircleShape)
                                        .background(BgSurfaceElevated)
                                        .border(1.5.dp, Primary, CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = participant.take(1).uppercase(),
                                        color = TextMain,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 16.sp
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = if (participant == session.hostUserId) "Host" else "Listener",
                                    style = LocalAppTypography.current.songArtist.copy(fontSize = 10.sp),
                                    color = TextSecondary
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(32.dp))

                    // Leave Session Button
                    OutlinedButton(
                        onClick = { jamViewModel.leaveJam() },
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
    }
}
