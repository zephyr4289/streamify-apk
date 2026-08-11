package com.streamify.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import com.streamify.app.data.NativeBridge
import com.streamify.app.ui.theme.StreamifyTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            StreamifyTheme {
                Text(text = NativeBridge.stringFromJNI())
            }
        }
    }
}
