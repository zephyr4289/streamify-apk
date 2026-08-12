package com.streamify.app.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

object StreamifyShapes {
    val CardShape      = RoundedCornerShape(8.dp)
    val MiniPlayerShape= RoundedCornerShape(8.dp)
    val ChipShape      = RoundedCornerShape(16.dp)
    val SearchBarShape = RoundedCornerShape(50.dp)
    val CategoryShape  = RoundedCornerShape(8.dp)
    val BottomSheet    = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
    val PlayButton     = CircleShape
}

val AppShapes = Shapes(
    small = StreamifyShapes.ChipShape,
    medium = StreamifyShapes.CardShape,
    large = StreamifyShapes.BottomSheet
)
