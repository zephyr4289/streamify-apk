package com.streamify.app

import android.app.Application
import com.streamify.app.data.NativeBridge

class StreamifyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val dbPath = getDatabasePath("streamify.db").absolutePath
        NativeBridge.initDatabase(dbPath)
    }
}
