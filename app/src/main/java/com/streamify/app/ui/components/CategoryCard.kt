package com.streamify.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import com.streamify.app.ui.animations.cardPressEffect
import com.streamify.app.ui.theme.StreamifyColors
import com.streamify.app.ui.theme.StreamifyDimens
import com.streamify.app.ui.theme.StreamifyShapes
import com.streamify.app.ui.theme.StreamifyType

@Composable
fun CategoryCard(
    title: String,
    backgroundColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(StreamifyDimens.CategoryCardH)
            .clip(StreamifyShapes.CategoryShape)
            .background(backgroundColor)
            .cardPressEffect(onClick = onClick)
            .padding(StreamifyDimens.SpaceSM)
    ) {
        Text(
            text = title,
            style = StreamifyType.TitleLarge,
            color = StreamifyColors.TextMain
        )
    }
}
