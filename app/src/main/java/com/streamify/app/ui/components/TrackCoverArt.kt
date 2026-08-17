package com.streamify.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import com.streamify.app.ui.theme.StreamifyShapes

@Composable
fun TrackCoverArt(
    coverArtPath: String?,
    title: String,
    artist: String,
    modifier: Modifier = Modifier,
    shape: Shape = StreamifyShapes.CardShape
) {
    val descriptor = remember(coverArtPath, title, artist) {
        val vid = com.streamify.app.data.network.YouTubeStreamResolver.extractVideoId(coverArtPath ?: "")
        com.streamify.app.data.network.YouTubeStreamResolver.buildThumbnailPipeline(
            rawUrl = coverArtPath,
            videoId = vid,
            title = title,
            artist = artist
        )
    }

    val fallbackColors = remember(descriptor.fallbackColorSeed) {
        val seed = descriptor.fallbackColorSeed
        val hue1 = kotlin.math.abs(seed % 360).toFloat()
        val hue2 = kotlin.math.abs((seed * 31) % 360).toFloat()
        listOf(
            Color.hsl(hue1, 0.65f, 0.35f),
            Color.hsl(hue2, 0.70f, 0.15f)
        )
    }

    Box(
        modifier = modifier
            .clip(shape)
            .background(Brush.linearGradient(fallbackColors))
    ) {
        if (!descriptor.primary.isNullOrBlank()) {
            SubcomposeAsyncImage(
                model = descriptor.primary,
                contentDescription = "$title cover",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                error = {
                    if (!descriptor.secondary.isNullOrBlank() && descriptor.secondary != descriptor.primary) {
                        SubcomposeAsyncImage(
                            model = descriptor.secondary,
                            contentDescription = "$title cover",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                            error = {
                                GenerativeCoverContent(title = title, artist = artist)
                            },
                            loading = {
                                GenerativeCoverContent(title = title, artist = artist)
                            }
                        )
                    } else {
                        GenerativeCoverContent(title = title, artist = artist)
                    }
                },
                loading = {
                    GenerativeCoverContent(title = title, artist = artist)
                }
            )
        } else if (!descriptor.secondary.isNullOrBlank()) {
            SubcomposeAsyncImage(
                model = descriptor.secondary,
                contentDescription = "$title cover",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                error = {
                    GenerativeCoverContent(title = title, artist = artist)
                },
                loading = {
                    GenerativeCoverContent(title = title, artist = artist)
                }
            )
        } else {
            GenerativeCoverContent(title = title, artist = artist)
        }
    }
}

@Composable
private fun GenerativeCoverContent(
    title: String,
    artist: String
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(4.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.MusicNote,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.weight(1f, fill = false)
            )
            Text(
                text = title,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
            if (artist.isNotBlank() && artist != "Unknown") {
                Text(
                    text = artist,
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 9.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
