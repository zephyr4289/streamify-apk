package com.streamify.app.ui.components

import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.streamify.app.data.remote.SpotifyAuthManager
import com.streamify.app.data.remote.YtSessionExtractor
import com.streamify.app.ui.theme.StreamifyColors

@Composable
fun YtLoginDialog(
    isOpen: Boolean,
    onDismiss: () -> Unit,
    onAuthSuccess: (authHeader: String, rawCookies: String) -> Unit,
    onError: (String) -> Unit
) {
    if (!isOpen) return

    val context = LocalContext.current
    val extractor = remember { YtSessionExtractor(context) }
    val authManager = remember { SpotifyAuthManager(context) }
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        BackHandler {
            if (webViewInstance?.canGoBack() == true) {
                webViewInstance?.goBack()
            } else {
                onDismiss()
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        webViewInstance = this
                        extractor.launchAuthSession(
                            webView = this,
                            onSuccess = { header, cookies ->
                                authManager.saveYtSession(header, cookies)
                                onAuthSuccess(header, cookies)
                                onDismiss()
                            },
                            onError = { error ->
                                onError(error)
                                onDismiss()
                            }
                        )
                    }
                },
                update = {
                    webViewInstance = it
                }
            )

            // Dismiss / Close Button
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(16.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close Login",
                    tint = Color.White
                )
            }
        }
    }
}
