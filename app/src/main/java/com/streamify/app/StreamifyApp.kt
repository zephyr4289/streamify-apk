package com.streamify.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.streamify.app.data.NativeBridge

import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform

class StreamifyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }
        
        val dbPath = getDatabasePath("streamify.db").absolutePath
        NativeBridge.initDatabase(dbPath)
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            
            val playbackChannel = NotificationChannel(
                "streamify_playback",
                "Playback Settings",
                NotificationManager.IMPORTANCE_LOW
            )
            playbackChannel.description = "Controls for the current playing track"
            
            val downloadChannel = NotificationChannel(
                "streamify_download",
                "Downloads",
                NotificationManager.IMPORTANCE_LOW
            )
            downloadChannel.description = "Background download progress"
            
            manager.createNotificationChannel(playbackChannel)
            manager.createNotificationChannel(downloadChannel)
        }
    }
}
