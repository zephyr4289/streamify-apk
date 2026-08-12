package com.streamify.app.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.streamify.app.data.NativeBridge
import com.streamify.app.util.MediaStoreScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class IngestionWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val localFiles = MediaStoreScanner.scanLocalMusic(applicationContext)
            
            localFiles.forEachIndexed { index, file ->
                val trackId = NativeBridge.insertTrack(
                    filepath = file.dataPath,
                    title = file.title,
                    artist = file.artist,
                    album = "Local Storage",
                    durationSec = (file.durationMs / 1000).toInt(),
                    bpm = 120.0f
                ).toInt()

                if (trackId > 0) {
                    NativeBridge.processAudioFile(trackId, file.dataPath)
                }

                val progress = (index + 1).toFloat() / localFiles.size.toFloat()
                setProgress(androidx.work.workDataOf("PROGRESS" to progress))
            }

            Result.success()
        } catch (e: Exception) {
            Result.failure()
        }
    }
}
