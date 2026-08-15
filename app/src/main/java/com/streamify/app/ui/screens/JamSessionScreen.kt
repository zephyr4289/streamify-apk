package com.streamify.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.streamify.app.data.models.Track
import com.streamify.app.data.remote.SupabaseClient
import com.streamify.app.ui.components.NowPlayingIndicator
import com.streamify.app.ui.components.TrackCoverArt
import com.streamify.app.ui.theme.StreamifyColors
import com.streamify.app.ui.theme.StreamifyDimens
import com.streamify.app.ui.theme.StreamifyType
import com.streamify.app.viewmodel.JamUiState
import com.streamify.app.viewmodel.JamViewModel
import com.streamify.app.viewmodel.PlayerViewModel

@OptIn(ExperimentalMaterial3Api::class)
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Streamify Jam", style = StreamifyType.HeadlineMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = StreamifyColors.TextMain)
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
                .padding(horizontal = StreamifyDimens.SpaceLG),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when (val state = jamState) {
                is JamUiState.Idle -> {
                    Spacer(modifier = Modifier.height(20.dp))
                    Text(
                        "🎧 Listen Together in Real-Time",
                        style = StreamifyType.HeadlineLarge,
                        color = StreamifyColors.TextMain,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Synchronize playback with friends anywhere in the world. Start a room or enter a 6-digit PIN code.",
                        style = StreamifyType.BodyMedium,
                        color = StreamifyColors.TextSub,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(32.dp))

                    // Host a Jam Button
                    Button(
                        onClick = {
                            jamViewModel.startJam(playerState.currentTrack, playerState.currentPosition)
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = StreamifyColors.Primary,
                            contentColor = StreamifyColors.BgBase
                        ),
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                    ) {
                        Icon(Icons.Default.Podcasts, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Start a New Jam Room", style = StreamifyType.BodyLargeBold)
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        HorizontalDivider(modifier = Modifier.weight(1f), color = StreamifyColors.Border)
                        Text(" OR JOIN ROOM ", style = StreamifyType.CaptionBold, color = StreamifyColors.TextDimmed, modifier = Modifier.padding(horizontal = 8.dp))
                        HorizontalDivider(modifier = Modifier.weight(1f), color = StreamifyColors.Border)
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    OutlinedTextField(
                        value = inputRoomCode,
                        onValueChange = { if (it.length <= 6) inputRoomCode = it.uppercase() },
                        placeholder = { Text("Enter 6-char PIN (e.g. STRM9X)", color = StreamifyColors.TextDimmed) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = StreamifyColors.Primary,
                            unfocusedBorderColor = StreamifyColors.Border,
                            focusedTextColor = StreamifyColors.TextMain,
                            unfocusedTextColor = StreamifyColors.TextMain
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    OutlinedButton(
                        onClick = {
                            jamViewModel.joinJam(inputRoomCode, playerViewModel)
                        },
                        enabled = inputRoomCode.length == 6,
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = StreamifyColors.Primary
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                    ) {
                        Text("Join with PIN", style = StreamifyType.BodyLargeBold)
                    }
                }

                is JamUiState.Loading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = StreamifyColors.Primary)
                    }
                }

                is JamUiState.Active -> {
                    val session = state.session

                    // Active Room Banner
                    Surface(
                        color = StreamifyColors.BgCard,
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Surface(
                                        color = StreamifyColors.Primary.copy(alpha = 0.2f),
                                        shape = CircleShape
                                    ) {
                                        Icon(Icons.Default.Podcasts, contentDescription = null, tint = StreamifyColors.Primary, modifier = Modifier.padding(6.dp).size(18.dp))
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        if (state.isHost) "HOSTING JAM" else "CONNECTED TO JAM",
                                        style = StreamifyType.CaptionBold,
                                        color = StreamifyColors.Primary
                                    )
                                }

                                Surface(
                                    color = StreamifyColors.BgElevated,
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            session.sessionCode,
                                            style = StreamifyType.HeadlineMedium,
                                            color = StreamifyColors.TextMain
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        IconButton(
                                            onClick = {
                                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                clipboard.setPrimaryClip(ClipData.newPlainText("Jam Room PIN", session.sessionCode))
                                                Toast.makeText(context, "Room PIN copied!", Toast.LENGTH_SHORT).show()
                                            },
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy", tint = StreamifyColors.TextSub, modifier = Modifier.size(16.dp))
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            // Current Track Card
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TrackCoverArt(
                                    coverArtPath = playerState.currentTrack?.coverArtPath,
                                    size = 56.dp
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        playerState.currentTrack?.title ?: "No track playing",
                                        style = StreamifyType.BodyLargeBold,
                                        color = StreamifyColors.TextMain,
                                        maxLines = 1
                                    )
                                    Text(
                                        playerState.currentTrack?.artist ?: "",
                                        style = StreamifyType.BodySmall,
                                        color = StreamifyColors.TextSub,
                                        maxLines = 1
                                    )
                                }
                                NowPlayingIndicator(isPlaying = playerState.isPlaying)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    // Leave Room Action
                    OutlinedButton(
                        onClick = { jamViewModel.leaveJam() },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = StreamifyColors.ErrorRed),
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.ExitToApp, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Leave Jam Session", style = StreamifyType.BodyMediumBold)
                    }
                }

                is JamUiState.Error -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("⚠️", fontSize = 40.sp)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(state.message, style = StreamifyType.BodyMedium, color = StreamifyColors.ErrorRed)
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(onClick = { jamViewModel.leaveJam() }) {
                                Text("Try Again")
                            }
                        }
                    }
                }
            }
        }
    }
}
