package com.streamify.app.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.chaquo.python.Python
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class DownloadService : Service() {
    private val scope = CoroutineScope(Dispatchers.IO)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val url = intent?.getStringExtra("URL") ?: return START_NOT_STICKY
        val outputDir = getExternalFilesDir(null)?.absolutePath + "/downloads"
        
        scope.launch {
            try {
                val py = Python.getInstance()
                val downloaderModule = py.getModule("download_engine.downloader")
                downloaderModule.callAttr("download_track", url, outputDir)
                // Post result to ViewModel or DB via repository here
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
