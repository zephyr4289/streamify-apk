package com.streamify.app.data.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.Channels
import java.util.concurrent.atomic.AtomicLong

data class DownloadChunk(
    val url: String,
    val start: Long,
    val end: Long
)

class ParallelStreamDownloader {

    suspend fun download(
        url: String,
        outputFile: File,
        onProgress: (percent: String, speed: String, eta: String) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val client = NetworkEngine.client

        // 1. Inquire Content-Length
        val headRequest = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0")
            .head()
            .build()

        var totalBytes = 0L
        try {
            client.newCall(headRequest).execute().use { resp ->
                totalBytes = resp.header("Content-Length")?.toLongOrNull() ?: 0L
            }
        } catch (e: Exception) {
            // Ignore HEAD failure and attempt GET
        }

        if (totalBytes <= 0) {
            // Fallback to direct single stream download
            return@withContext downloadSingleStream(url, outputFile, onProgress)
        }

        // 2. Partition file into 1MB chunks
        val chunkSize = 1024 * 1024L // 1MB chunk
        val chunks = mutableListOf<DownloadChunk>()
        var offset = 0L
        while (offset < totalBytes) {
            val end = minOf(offset + chunkSize - 1, totalBytes - 1)
            chunks.add(DownloadChunk(url, offset, end))
            offset += chunkSize
        }

        // 3. Pre-allocate disk file
        if (outputFile.exists()) outputFile.delete()
        val raf = RandomAccessFile(outputFile, "rw")
        raf.setLength(totalBytes)

        val downloadedBytes = AtomicLong(0)
        val startTime = System.currentTimeMillis()

        // 4. Download chunks concurrently in batches of 4
        chunks.chunked(4).forEach { batch ->
            coroutineScope {
                batch.map { chunk ->
                    async {
                        val req = Request.Builder()
                            .url(chunk.url)
                            .header("User-Agent", "Mozilla/5.0")
                            .header("Range", "bytes=${chunk.start}-${chunk.end}")
                            .build()

                        client.newCall(req).execute().use { response ->
                            if (!response.isSuccessful) return@async
                            val body = response.body?.byteStream() ?: return@async
                            val buffer = ByteArray(32 * 1024)
                            var read: Int
                            var chunkOffset = chunk.start

                            while (body.read(buffer).also { read = it } != -1) {
                                synchronized(raf) {
                                    raf.seek(chunkOffset)
                                    raf.write(buffer, 0, read)
                                }
                                chunkOffset += read
                                val currentTotal = downloadedBytes.addAndGet(read.toLong())

                                val elapsedSec = ((System.currentTimeMillis() - startTime) / 1000.0).coerceAtLeast(0.1)
                                val speedBps = currentTotal / elapsedSec
                                val speedMbps = speedBps / (1024.0 * 1024.0)
                                val percent = ((currentTotal.toDouble() / totalBytes) * 100).toInt().coerceIn(0, 100)
                                val remainingBytes = (totalBytes - currentTotal).coerceAtLeast(0)
                                val etaSec = if (speedBps > 0) (remainingBytes / speedBps).toInt() else 0

                                onProgress(
                                    "$percent%",
                                    "%.1f MB/s".format(speedMbps),
                                    "%02d:%02d".format(etaSec / 60, etaSec % 60)
                                )
                            }
                        }
                    }
                }.awaitAll()
            }
        }

        raf.close()
        return@withContext outputFile.exists() && outputFile.length() > 0
    }

    private fun downloadSingleStream(
        url: String,
        outputFile: File,
        onProgress: (percent: String, speed: String, eta: String) -> Unit
    ): Boolean {
        try {
            val req = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").get().build()
            NetworkEngine.client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return false
                val body = resp.body?.byteStream() ?: return false
                val totalLength = resp.body?.contentLength() ?: 0L
                val startTime = System.currentTimeMillis()
                var downloaded = 0L

                outputFile.outputStream().use { out ->
                    val buffer = ByteArray(32 * 1024)
                    var read: Int
                    while (body.read(buffer).also { read = it } != -1) {
                        out.write(buffer, 0, read)
                        downloaded += read
                        val elapsedSec = ((System.currentTimeMillis() - startTime) / 1000.0).coerceAtLeast(0.1)
                        val speedBps = downloaded / elapsedSec
                        val speedMbps = speedBps / (1024.0 * 1024.0)
                        val percent = if (totalLength > 0) ((downloaded.toDouble() / totalLength) * 100).toInt().coerceIn(0, 100) else 50
                        onProgress("$percent%", "%.1f MB/s".format(speedMbps), "00:05")
                    }
                }
                return true
            }
        } catch (e: Exception) {
            return false
        }
    }
}
