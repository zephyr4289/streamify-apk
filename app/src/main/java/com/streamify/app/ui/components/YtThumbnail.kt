package com.streamify.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import com.streamify.app.data.network.YouTubeStreamResolver
import com.streamify.app.ui.theme.*

@Composable
fun YtThumbnail(
    url: String?,
    size: Dp,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 4.dp,
    title: String = "",
    artist: String = "",
    videoId: String? = null
) {
    val shape = RoundedCornerShape(cornerRadius)
    val descriptor = remember(url, videoId, title, artist) {
        val vid = videoId ?: YouTubeStreamResolver.extractVideoId(url ?: "")
        YouTubeStreamResolver.buildThumbnailPipeline(
            rawUrl = url,
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

    Surface(
        color = BgCard,
        shape = shape,
        modifier = modifier
            .size(size)
            .clip(shape)
    ) {
        if (!descriptor.primary.isNullOrBlank()) {
            SubcomposeAsyncImage(
                model = descriptor.primary,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(shape),
                error = {
                    if (!descriptor.secondary.isNullOrBlank() && descriptor.secondary != descriptor.primary) {
                        SubcomposeAsyncImage(
                            model = descriptor.secondary,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(shape),
                            error = {
                                GenerativeThumbnailFallback(size, fallbackColors)
                            },
                            loading = {
                                GenerativeThumbnailFallback(size, fallbackColors)
                            }
                        )
                    } else {
                        GenerativeThumbnailFallback(size, fallbackColors)
                    }
                },
                loading = {
                    GenerativeThumbnailFallback(size, fallbackColors)
                }
            )
        } else if (!descriptor.secondary.isNullOrBlank()) {
            SubcomposeAsyncImage(
                model = descriptor.secondary,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(shape),
                error = {
                    GenerativeThumbnailFallback(size, fallbackColors)
                },
                loading = {
                    GenerativeThumbnailFallback(size, fallbackColors)
                }
            )
        } else {
            GenerativeThumbnailFallback(size, fallbackColors)
        }
    }
}

@Composable
private fun GenerativeThumbnailFallback(size: Dp, colors: List<Color>) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.linearGradient(colors)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Filled.MusicNote,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.85f),
            modifier = Modifier.size(size * 0.45f)
        )
    }
}
