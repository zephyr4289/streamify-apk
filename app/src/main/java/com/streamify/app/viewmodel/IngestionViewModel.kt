package com.streamify.app.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

data class DownloadTask(
    val id: UUID,
    val title: String,
    val progress: String = "0%",
    val speed: String = "",
    val state: String = "Queued"
)

class IngestionViewModel : ViewModel() {
    private val _downloadTasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val downloadTasks: StateFlow<List<DownloadTask>> = _downloadTasks.asStateFlow()

    fun enqueueDownload(context: Context, url: String, title: String, artist: String, album: String) {
        val workManager = WorkManager.getInstance(context)
        
        val inputData = Data.Builder()
            .putString("url", url)
            .putString("title", title)
            .putString("artist", artist)
            .putString("album", album)
            .build()
            
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
            
        val downloadRequest = OneTimeWorkRequestBuilder<com.streamify.app.worker.DownloadWorker>()
            .setInputData(inputData)
            .setConstraints(constraints)
            .build()
            
        workManager.enqueue(downloadRequest)
        
        // Add to local state
        val newTask = DownloadTask(id = downloadRequest.id, title = title)
        _downloadTasks.value = _downloadTasks.value + newTask
        
        // Note: In a real app we'd observe WorkManager LiveData here and update the list.
    }
}
