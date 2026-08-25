package com.streamify.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streamify.app.data.remote.SupabaseClient
import com.streamify.app.util.SLog
import kotlinx.coroutines.launch

private val TermBg = Color(0xFF0A0E0A)
private val TermBar = Color(0xFF101510)
private val TermGreen = Color(0xFF4AF626)

private fun levelColor(level: Char): Color = when (level) {
    'V' -> Color(0xFF8A8F8A)
    'D' -> Color(0xFF58C4DD)
    'I' -> Color(0xFF4AF626)
    'W' -> Color(0xFFFFB74D)
    'E' -> Color(0xFFFF5370)
    'F' -> Color(0xFFFF2E88)
    else -> Color.White
}

/**
 * Admin-only live terminal mirroring every SLog line (which is all of them —
 * the whole app logs through SLog). Supports level/tag/text filtering,
 * auto-scroll with drag-pause, and one-tap copy of the ENTIRE buffer in
 * logcat format for pasting into bug reports.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminTerminalScreen(onBack: () -> Unit) {
    // Defense in depth: nav gating is checked at the call site too.
    if (!SupabaseClient.isAdmin) {
        Box(Modifier.fillMaxSize().background(TermBg), contentAlignment = Alignment.Center) {
            Text("ACCESS DENIED", color = Color(0xFFFF5370), fontFamily = FontFamily.Monospace)
        }
        return
    }

    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    // Render window: newest entries, capped so Compose never lays out 8k rows.
    var rendered by remember { mutableStateOf(SLog.snapshot().takeLast(1000)) }
    var paused by remember { mutableStateOf(false) }

    var filterLevels by remember { mutableStateOf(setOf('V', 'D', 'I', 'W', 'E', 'F')) }
    var query by remember { mutableStateOf("") }

    fun applyFilter(list: List<SLog.Entry>) = list.filter { e ->
        e.level in filterLevels &&
            (query.isBlank() || e.message.contains(query, true) || e.tag.contains(query, true))
    }

    LaunchedEffect(Unit) {
        SLog.tail.collect { e ->
            if (!paused) {
                rendered = (rendered + e).takeLast(1000)
            }
        }
    }

    val visible = remember(rendered, filterLevels, query) { applyFilter(rendered) }
    val listState = rememberLazyListState()
    LaunchedEffect(visible.size) {
        if (!paused && visible.isNotEmpty()) listState.animateScrollToItem(visible.size - 1)
    }

    Scaffold(
        containerColor = TermBg,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Surface(color = TermBar, shadowElevation = 4.dp) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onBack) { Text("< back", color = TermGreen, fontFamily = FontFamily.Monospace) }
                    Spacer(Modifier.width(6.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "streamify://terminal",
                            color = TermGreen,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 14.sp
                        )
                        Text(
                            "${SLog.snapshot().size} buffered · showing ${visible.size}",
                            color = Color(0xFF6B7A6B),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp
                        )
                    }
                    IconButton(onClick = {
                        val text = SLog.snapshotFormatted()
                        clipboard.setText(AnnotatedString(text))
                        scope.launch { snackbar.showSnackbar("Copied ${text.length} chars (${SLog.snapshot().size} lines)") }
                    }) {
                        Icon(Icons.Filled.ContentCopy, "Copy entire log", tint = TermGreen)
                    }
                    IconButton(onClick = {
                        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(android.content.Intent.EXTRA_TEXT, SLog.snapshotFormatted())
                            putExtra(android.content.Intent.EXTRA_SUBJECT, "Streamify log ${System.currentTimeMillis()}")
                        }
                        context.startActivity(android.content.Intent.createChooser(send, "Share log"))
                    }) {
                        Icon(Icons.Filled.Share, "Share log", tint = TermGreen)
                    }
                    IconButton(onClick = {
                        scope.launch {
                            val result = saveLogToDownloads(context, SLog.snapshotFormatted())
                            snackbar.showSnackbar(result)
                        }
                    }) {
                        Icon(Icons.Filled.Download, "Save to Downloads", tint = TermGreen)
                    }
                    IconButton(onClick = {
                        SLog.clearMemoryBuffer()
                        rendered = emptyList()
                        scope.launch { snackbar.showSnackbar("Buffer cleared (disk spool kept)") }
                    }) {
                        Icon(Icons.Filled.Delete, "Clear buffer", tint = Color(0xFF9AA89A))
                    }
                }
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().background(TermBg)) {
            // ---- filter row ----
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf('V', 'D', 'I', 'W', 'E', 'F').forEach { lvl ->
                    val active = lvl in filterLevels
                    FilterChip(
                        selected = active,
                        onClick = {
                            filterLevels = if (active) filterLevels - lvl else filterLevels + lvl
                        },
                        label = { Text("$lvl", fontFamily = FontFamily.Monospace, fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = TermBar,
                            selectedContainerColor = levelColor(lvl).copy(alpha = 0.25f),
                            labelColor = levelColor(lvl)
                        )
                    )
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("grep…", color = Color(0xFF51604F), fontSize = 12.sp, fontFamily = FontFamily.Monospace) },
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(color = TermGreen, fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(Icons.Filled.Clear, null, tint = Color(0xFF51604F), modifier = Modifier.height(16.dp))
                            }
                        }
                    },
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = TermGreen.copy(alpha = 0.5f),
                        unfocusedBorderColor = Color(0xFF22301F)
                    )
                )
            }

            // ---- pause banner ----
            if (paused) {
                Text(
                    "// autoscroll paused — scroll to bottom edge to resume",
                    color = Color(0xFFFFB74D),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(horizontal = 10.dp)
                )
            }

            // ---- log stream ----
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().background(TermBg).padding(horizontal = 8.dp)
            ) {
                items(visible) { e ->
                    Text(
                        text = buildAnnotatedString {
                            withStyle(SpanStyle(color = Color(0xFF51604F))) {
                                append("${formatTs(e.timeMs)} ")
                            }
                            withStyle(SpanStyle(color = levelColor(e.level), fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)) {
                                append("${e.level}/")
                            }
                            withStyle(SpanStyle(color = Color(0xFF7FBF7F))) { append(e.tag) }
                            withStyle(SpanStyle(color = Color(0xFFD7E8D7))) { append(": ${e.message}") }
                        },
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(vertical = 1.dp)
                    )
                }
                item {
                    // Pause autoscroll whenever the user drags away from the bottom.
                    LaunchedEffect(listState.isScrollInProgress) {
                        if (listState.isScrollInProgress) {
                            val last = visible.size - 1
                            val atBottom = listState.layoutInfo.visibleItemsInfo.any { it.index >= last }
                            if (!atBottom && !paused) paused = true
                        } else if (paused) {
                            val info = listState.layoutInfo.visibleItemsInfo
                            val last = visible.size - 1
                            if (info.any { it.index >= last }) paused = false
                        }
                    }
                    Text("", Modifier.height(8.dp))
                }
            }
        }
    }
}

private fun formatTs(timeMs: Long): String =
    java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date(timeMs))

/**
 * Writes the full log buffer into the phone's OFFICIAL Downloads folder.
 *  - API 29+: MediaStore.Downloads (no storage permission required)
 *  - API 26-28: direct public Downloads path (works on legacy-storage devices;
 *    surfaces a clear error otherwise)
 */
private fun saveLogToDownloads(context: android.content.Context, text: String): String {
    val fileName = "Streamify-log-${
        java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
    }.log"
    return try {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return "❌ Could not create Downloads entry"
            resolver.openOutputStream(uri)?.use { out -> out.write(text.toByteArray()) }
                ?: return "❌ Failed to open output stream"
            values.clear()
            values.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            "✅ Saved to Download/$fileName (${text.length} chars)"
        } else {
            @Suppress("DEPRECATION")
            val dir = android.os.Environment
                .getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
            dir.mkdirs()
            java.io.File(dir, fileName).writeText(text)
            "✅ Saved to Download/$fileName"
        }
    } catch (t: Throwable) {
        "❌ Save failed: ${t.message ?: t.javaClass.simpleName}"
    }
}
