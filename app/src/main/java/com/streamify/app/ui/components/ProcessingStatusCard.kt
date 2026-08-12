package com.streamify.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streamify.app.ui.theme.StreamifyColors
import com.streamify.app.viewmodel.IngestionState

@Composable
fun ProcessingStatusCard(state: IngestionState) {
    if (!state.isActive) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(StreamifyColors.BgCard)
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Processing your music library...",
                color = StreamifyColors.TextMain,
                fontSize = 14.sp
            )
            Text(
                text = "${state.processedFiles}/${state.totalFiles}",
                color = StreamifyColors.TextSub,
                fontSize = 12.sp
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = state.currentStep,
            color = StreamifyColors.Primary,
            fontSize = 12.sp
        )
        Spacer(modifier = Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = state.progress,
            modifier = Modifier.fillMaxWidth(),
            color = StreamifyColors.Primary,
            trackColor = StreamifyColors.BgSurface
        )
    }
}
