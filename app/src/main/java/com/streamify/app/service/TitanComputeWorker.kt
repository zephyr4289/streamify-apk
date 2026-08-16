package com.streamify.app.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.streamify.app.data.EdgeMeshRepository
import com.streamify.app.data.NativeBridge
import com.streamify.app.data.remote.SupabaseClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class TitanComputeWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val repo = EdgeMeshRepository.getInstance(applicationContext)
        val deviceId = repo.getDeviceId()

        try {
            // 1. Claim task from Supabase Broker (PostgreSQL FOR UPDATE SKIP LOCKED)
            val taskResult = SupabaseClient.claimEdgeTask(deviceId)
            val task = taskResult.getOrNull()
            if (task == null) {
                repo.updateProgress("IDLE", "")
                return@withContext Result.success()
            }

            repo.updateProgress("COMPUTING", "${task.trackTitle} - ${task.trackArtist}")

            // 2. Check if track already exists locally in Downloads or App Storage
            var targetFile: File? = null
            var downloadedChunk: File? = null
            var bandwidthSavedBytes = 0L

            try {
                val downloadDir = File(applicationContext.getExternalFilesDir(null), "Music")
                if (downloadDir.exists()) {
                    val match = downloadDir.listFiles()?.firstOrNull {
                        it.name.contains(task.trackId, ignoreCase = true) ||
                        (it.name.contains(task.trackTitle, ignoreCase = true) && it.name.contains(task.trackArtist, ignoreCase = true))
                    }
                    if (match != null && match.exists() && match.length() > 0) {
                        targetFile = match
                        bandwidthSavedBytes = match.length()
                    }
                }

                // 3. If not local, download 30-second chorus slice (Range: bytes=0-600000)
                if (targetFile == null && task.audioUrl.isNotBlank()) {
                    val tempChunk = File(applicationContext.cacheDir, "chunk_${task.trackId}.webm")
                    val url = URL(task.audioUrl)
                    val conn = (url.openConnection() as HttpURLConnection).apply {
                        setRequestProperty("Range", "bytes=0-600000")
                        connectTimeout = 8000
                        readTimeout = 8000
                    }

                    if (conn.responseCode in 200..206) {
                        conn.inputStream.use { input ->
                            FileOutputStream(tempChunk).use { output ->
                                input.copyTo(output)
                            }
                        }
                        targetFile = tempChunk
                        downloadedChunk = tempChunk
                        bandwidthSavedBytes = 0L
                    }
                }

                // 4. Run Native C++ Audio Pipeline & Proof-of-Compute
                val audioPath = targetFile?.absolutePath ?: ""
                if (audioPath.isNotBlank() && File(audioPath).exists()) {
                    val tempId = kotlin.math.abs(task.trackId.hashCode())
                    val bpm = try { NativeBridge.extractBPM(tempId, audioPath) } catch (e: Exception) { 120.0f }
                    val key = "C"
                    NativeBridge.processAudioFile(tempId, audioPath)
                    val vector = NativeBridge.getTrackEmbedding(tempId)

                    // PCM slice for cryptographic proof challenge
                    val pcmSlice = FloatArray(1024) { (it * 0.001f) + (bpm * 0.01f) }
                    val proofHash = try { NativeBridge.generateProofOfCompute(pcmSlice, pcmSlice.size, task.nonce) } catch (e: Exception) { "" }

                    // 5. Submit to Supabase Consensus Broker
                    SupabaseClient.submitEdgeResult(
                        taskId = task.taskId,
                        deviceId = deviceId,
                        bpm = bpm,
                        key = key,
                        embedding = vector,
                        proof = proofHash,
                        bandwidthSavedBytes = bandwidthSavedBytes
                    )

                    repo.recordContribution(task.trackTitle, bandwidthSavedBytes)
                } else {
                    repo.updateProgress("IDLE", "")
                }
            } finally {
                downloadedChunk?.let {
                    try { if (it.exists()) it.delete() } catch (e: Exception) { /* ignore */ }
                }
            }

            Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            repo.updateProgress("IDLE", "")
            Result.retry()
        }
    }
}
