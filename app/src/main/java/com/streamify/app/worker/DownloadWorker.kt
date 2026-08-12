package com.streamify.app.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.streamify.app.data.NativeBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class DownloadWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val url = inputData.getString("url") ?: return@withContext Result.failure()
        val title = inputData.getString("title") ?: "Unknown"
        val artist = inputData.getString("artist") ?: "Unknown"
        val album = inputData.getString("album") ?: "Unknown"
        
        val outputDir = File(applicationContext.getExternalFilesDir(null), "music")
        if (!outputDir.exists()) outputDir.mkdirs()

        try {
            val py = Python.getInstance()
            val coreModule = py.getModule("download_engine.core")
            val metadataModule = py.getModule("download_engine.metadata")
            
            // Define Java callback for python progress hooks
            val callback = object : DownloadCallback {
                override fun onProgress(percent: String, speed: String, eta: String) {
                    setProgressAsync(workDataOf(
                        "progress" to percent,
                        "speed" to speed,
                        "eta" to eta
                    ))
                }

                override fun onFinished(filepath: String) {
                    // Inject metadata and extract cover art path
                    val metadataResult = metadataModule.callAttr("inject_metadata", filepath, title, artist, album, null)
                    
                    var durationSec = 0
                    var bpm = 120.0f
                    var coverArtPath = ""
                    try {
                        val list = metadataResult.asList()
                        if (list.size >= 2) {
                            durationSec = list[0].toInt()
                            bpm = list[1].toFloat()
                        }
                        if (list.size >= 3) {
                            coverArtPath = list[2].toString()
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    
                    // Insert to database using JNI
                    val trackId = NativeBridge.insertTrack(
                        filepath = filepath,
                        title = title,
                        artist = artist,
                        album = album,
                        durationSec = durationSec,
                        bpm = bpm
                    ).toInt()

                    if (trackId > 0) {
                        if (coverArtPath.isNotBlank()) {
                            NativeBridge.updateTrackCoverArt(trackId, coverArtPath)
                        }
                        // Run ONNX feature extraction & VectorStore embedding
                        NativeBridge.processAudioFile(trackId, filepath)
                    }
                }

                override fun onError(error: String) {
                    // Log error
                }
            }

            val success = coreModule.callAttr("download_audio", url, outputDir.absolutePath, callback).toBoolean()
            
            if (success) {
                Result.success()
            } else {
                Result.failure()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure()
        }
    }
}

interface DownloadCallback {
    fun onProgress(percent: String, speed: String, eta: String)
    fun onFinished(filepath: String)
    fun onError(error: String)
}
