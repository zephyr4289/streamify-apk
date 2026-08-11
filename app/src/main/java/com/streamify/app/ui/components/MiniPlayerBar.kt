package com.streamify.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.streamify.app.ui.theme.StreamifyColors
import com.streamify.app.ui.theme.StreamifyDimens
import com.streamify.app.data.models.Track

@Composable
fun MiniPlayerBar(track: Track?, onExpand: () -> Unit) {
    if (track == null) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(StreamifyDimens.PlayerBarHeight)
            .background(StreamifyColors.BgPlayer),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text("Playing: ${track.title}", color = StreamifyColors.TextMain, modifier = Modifier.padding(16.dp))
        // Mini player controls would go here
    }
}
