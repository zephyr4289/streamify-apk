package com.streamify.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.streamify.app.ui.components.EmptyStateView
import com.streamify.app.ui.theme.StreamifyColors
import com.streamify.app.ui.theme.StreamifyDimens
import com.streamify.app.ui.theme.StreamifyShapes
import com.streamify.app.ui.theme.StreamifyType
import com.streamify.app.viewmodel.IngestionViewModel

@Composable
fun DownloadScreen(
    viewModel: IngestionViewModel = viewModel()
) {
    val tasks by viewModel.downloadTasks.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(StreamifyColors.BgBase)
            .padding(top = StreamifyDimens.SpaceGiant)
    ) {
        Text(
            text = "Downloads",
            style = StreamifyType.HeadlineLarge,
            color = StreamifyColors.TextMain,
            modifier = Modifier.padding(horizontal = StreamifyDimens.SpaceLG, vertical = StreamifyDimens.SpaceMD)
        )

        if (tasks.isEmpty()) {
            EmptyStateView(
                title = "No active downloads",
                subtitle = "Songs you download will appear here"
            )
        } else {
            LazyColumn(
                contentPadding = PaddingValues(bottom = StreamifyDimens.PlayerBarHeight + StreamifyDimens.SpaceXL)
            ) {
                items(tasks) { task ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = StreamifyDimens.SpaceLG, vertical = StreamifyDimens.SpaceMD),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = task.title,
                                style = StreamifyType.TitleMedium,
                                color = StreamifyColors.TextMain
                            )
                            Spacer(modifier = Modifier.height(StreamifyDimens.SpaceXXS))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = task.progress,
                                    style = StreamifyType.Caption,
                                    color = StreamifyColors.Primary
                                )
                                Spacer(modifier = Modifier.width(StreamifyDimens.SpaceSM))
                                Text(
                                    text = task.speed,
                                    style = StreamifyType.Caption,
                                    color = StreamifyColors.TextSub
                                )
                            }
                        }

                        IconButton(onClick = { /* cancel worker */ }) {
                            Icon(
                                imageVector = Icons.Filled.Cancel,
                                contentDescription = "Cancel",
                                tint = StreamifyColors.TextSub
                            )
                        }
                    }
                    Divider(color = StreamifyColors.Divider, thickness = StreamifyDimens.DividerThickness)
                }
            }
        }
    }
}
