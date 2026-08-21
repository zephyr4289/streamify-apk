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
    videoId: String? = null,
    isLoading: Boolean = false
) {
    val shape = RoundedCornerShape(cornerRadius)

    val resolvedVideoId = remember(url, videoId) {
        videoId ?: YouTubeStreamResolver.extractVideoId(url ?: "")
    }

    val displayUrl = remember(url, resolvedVideoId) {
        if (!url.isNullOrBlank()) {
            YouTubeStreamResolver.sanitizeCoverUrl(url, resolvedVideoId)
        } else if (!resolvedVideoId.isNullOrBlank()) {
            "https://i.ytimg.com/vi/$resolvedVideoId/sddefault.jpg"
        } else {
            null
        }
    }

    val context = androidx.compose.ui.platform.LocalContext.current

    val imageRequest = remember(displayUrl, resolvedVideoId) {
        if (displayUrl.isNullOrBlank()) null
        else {
            coil.request.ImageRequest.Builder(context)
                .data(displayUrl)
                .crossfade(true)
                .diskCachePolicy(coil.request.CachePolicy.ENABLED)
                .memoryCachePolicy(coil.request.CachePolicy.ENABLED)
                .build()
        }
    }

    Surface(
        color = BgCard,
        shape = shape,
        modifier = modifier
            .size(size)
            .clip(shape)
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (imageRequest != null) {
                SubcomposeAsyncImage(
                    model = imageRequest,
                    contentDescription = title.ifBlank { null },
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(shape),
                    error = {
                        val fallback = resolvedVideoId?.let { "https://i.ytimg.com/vi/$it/sddefault.jpg" }
                            ?: resolvedVideoId?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" }
                        if (!fallback.isNullOrBlank() && fallback != displayUrl) {
                            SubcomposeAsyncImage(
                                model = fallback,
                                contentDescription = title.ifBlank { null },
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                                error = {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(BgCard),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.MusicNote,
                                            contentDescription = null,
                                            tint = TextTertiary,
                                            modifier = Modifier.size(size * 0.45f)
                                        )
                                    }
                                }
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(BgCard),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.MusicNote,
                                    contentDescription = null,
                                    tint = TextTertiary,
                                    modifier = Modifier.size(size * 0.45f)
                                )
                            }
                        }
                    }
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(BgCard),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.MusicNote,
                        contentDescription = null,
                        tint = TextTertiary,
                        modifier = Modifier.size(size * 0.45f)
                    )
                }
            }


            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.52f)),
                    contentAlignment = Alignment.Center
                ) {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size((size * 0.38f).coerceIn(18.dp, 32.dp)),
                        strokeWidth = 2.5.dp,
                        color = Primary,
                        trackColor = Primary.copy(alpha = 0.2f)
                    )
                }
            }
        }
    }
}
