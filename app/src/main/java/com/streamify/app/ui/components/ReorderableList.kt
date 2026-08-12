package com.streamify.app.ui.components

import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
    var list by remember(items) { mutableStateOf(items) }
    val listState = rememberLazyListState()
    var draggingIndex by remember { mutableStateOf<Int?>(null) }

    LazyColumn(
        state = listState,
        modifier = modifier.pointerInput(Unit) {
            detectDragGesturesAfterLongPress(
                onDragStart = { offset ->
                    val itemInfo = listState.layoutInfo.visibleItemsInfo.firstOrNull { 
                        offset.y.toInt() in it.offset..(it.offset + it.size) 
                    }
                    if (itemInfo != null) {
                        draggingIndex = itemInfo.index
                    }
                },
                onDrag = { change, dragAmount ->
                    change.consume()
                    val dragIdx = draggingIndex ?: return@detectDragGesturesAfterLongPress
                    
                    val visibleItems = listState.layoutInfo.visibleItemsInfo
                    val dragItemInfo = visibleItems.firstOrNull { it.index == dragIdx }
                    
                    if (dragItemInfo != null) {
                        // Find the target index
                        val targetItem = visibleItems.firstOrNull {
                            dragItemInfo.offset + dragAmount.y > it.offset &&
                            dragItemInfo.offset + dragAmount.y < (it.offset + it.size)
                        }
                        
                        if (targetItem != null && targetItem.index != dragIdx) {
                            val newList = list.toMutableList()
                            val item = newList.removeAt(dragIdx)
                            newList.add(targetItem.index, item)
                            list = newList
                            draggingIndex = targetItem.index
                            onMove(dragIdx, targetItem.index)
                        }
                    }
                },
                onDragEnd = { draggingIndex = null },
                onDragCancel = { draggingIndex = null }
            )
        },
        contentPadding = contentPadding
    ) {
        itemsIndexed(list, key = { idx, item -> item.hashCode() }) { index, item ->
            val isDragging = index == draggingIndex
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .zIndex(if (isDragging) 1f else 0f)
                    .animateItemPlacement(tween(200, easing = FastOutLinearInEasing))
            ) {
                itemContent(index, item, isDragging)
            }
        }
    }
}
