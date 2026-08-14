package com.streamify.app.ui.components

import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun <T> ReorderableList(
    items: List<T>,
    onMove: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    itemContent: @Composable LazyItemScope.(index: Int, item: T, isDragging: Boolean) -> Unit
) {
    var localList by remember(items) { mutableStateOf(items) }
    val listState = rememberLazyListState()
    
    var draggingIndex by remember { mutableStateOf<Int?>(null) }
    var initialIndex by remember { mutableStateOf<Int?>(null) }
    var cumulativeDragOffset by remember { mutableFloatStateOf(0f) }
    var initialItemCenterY by remember { mutableFloatStateOf(0f) }

    LazyColumn(
        state = listState,
        modifier = modifier.pointerInput(localList) {
            detectDragGesturesAfterLongPress(
                onDragStart = { offset ->
                    val visibleItems = listState.layoutInfo.visibleItemsInfo
                    val hitItem = visibleItems.firstOrNull { item ->
                        offset.y.toInt() in item.offset..(item.offset + item.size)
                    }
                    if (hitItem != null && hitItem.index in localList.indices) {
                        draggingIndex = hitItem.index
                        initialIndex = hitItem.index
                        cumulativeDragOffset = 0f
                        initialItemCenterY = hitItem.offset + (hitItem.size / 2f)
                    }
                },
                onDrag = { change, dragAmount ->
                    change.consume()
                    val currentIdx = draggingIndex ?: return@detectDragGesturesAfterLongPress
                    cumulativeDragOffset += dragAmount.y

                    val currentPointerCenterY = initialItemCenterY + cumulativeDragOffset
                    val visibleItems = listState.layoutInfo.visibleItemsInfo

                    // Find which slot the pointer is currently hovering over
                    val targetItem = visibleItems.firstOrNull { item ->
                        currentPointerCenterY >= item.offset && currentPointerCenterY <= (item.offset + item.size)
                    }

                    if (targetItem != null && targetItem.index in localList.indices && targetItem.index != currentIdx) {
                        val targetIdx = targetItem.index
                        val updated = localList.toMutableList()
                        val item = updated.removeAt(currentIdx)
                        updated.add(targetIdx, item)
                        localList = updated
                        
                        // Adjust initialItemCenterY for the new slot position
                        initialItemCenterY = targetItem.offset + (targetItem.size / 2f)
                        cumulativeDragOffset = 0f
                        draggingIndex = targetIdx
                    }
                },
                onDragEnd = {
                    val from = initialIndex
                    val to = draggingIndex
                    if (from != null && to != null && from != to) {
                        onMove(from, to)
                    }
                    draggingIndex = null
                    initialIndex = null
                    cumulativeDragOffset = 0f
                },
                onDragCancel = {
                    localList = items
                    draggingIndex = null
                    initialIndex = null
                    cumulativeDragOffset = 0f
                }
            )
        },
        contentPadding = contentPadding
    ) {
        itemsIndexed(localList, key = { _, item -> item.hashCode() }) { index, item ->
            val isDragging = index == draggingIndex
            val animatedElevation by animateFloatAsState(
                targetValue = if (isDragging) 12f else 0f,
                label = "elevation"
            )

            Box(
                modifier = Modifier
                    .zIndex(if (isDragging) 2f else 0f)
                    .graphicsLayer {
                        shadowElevation = animatedElevation
                        translationY = if (isDragging) cumulativeDragOffset else 0f
                        scaleX = if (isDragging) 1.03f else 1f
                        scaleY = if (isDragging) 1.03f else 1f
                    }
                    .animateItemPlacement(tween(250, easing = FastOutLinearInEasing))
            ) {
                itemContent(index, item, isDragging)
            }
        }
    }
}
