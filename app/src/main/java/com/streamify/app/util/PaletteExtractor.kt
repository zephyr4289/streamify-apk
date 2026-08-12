package com.streamify.app.util

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import com.streamify.app.ui.theme.StreamifyColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object PaletteExtractor {

    /**
     * Extracts the dominant color from a bitmap and enforces luminance safety
     * so that white text (#FFFFFF) will always be readable against it.
     */
    suspend fun getDominantColor(bitmap: Bitmap): Color = withContext(Dispatchers.Default) {
        // Downscale bitmap for faster palette extraction
        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, 100, 100, true)
        
        val palette = Palette.from(scaledBitmap).generate()
        
        // Prefer vibrant, then dominant, fallback to Surface color
        val swatch = palette.vibrantSwatch ?: palette.dominantSwatch
        var color = swatch?.rgb?.let { Color(it) } ?: StreamifyColors.BgSurface

        // Dynamic Palette Luminance Safety
        // If the color is too bright, blend it toward a dark color to maintain contrast for white text
        if (color.luminance() > 0.35f) {
            val blendedArgb = ColorUtils.blendARGB(
                color.toArgb(),
                StreamifyColors.BgSurface.toArgb(),
                0.6f // Blend heavily toward dark background
            )
            color = Color(blendedArgb)
        }

        color
    }
}
