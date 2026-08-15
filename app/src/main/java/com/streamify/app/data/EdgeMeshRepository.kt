package com.streamify.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.work.*
import com.streamify.app.data.remote.SupabaseClient
import com.streamify.app.service.TitanComputeWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.concurrent.TimeUnit

data class LocalEdgeMeshState(
    val deviceId: String = "",
    val totalContributions: Int = 0,
    val bandwidthSavedBytes: Long = 0L,
    val currentStatus: String = "IDLE", // "IDLE", "COMPUTING", "SYNCED"
    val currentTrackTitle: String = "",
    val recentProcessedTracks: List<String> = emptyList()
) {
    val bandwidthSavedMb: Double
        get() = (bandwidthSavedBytes.toDouble() / (1024.0 * 1024.0))
}

class EdgeMeshRepository private constructor(private val context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("edge_mesh_prefs", Context.MODE_PRIVATE)
    
    private val _meshState = MutableStateFlow(loadInitialState())
    val meshState: StateFlow<LocalEdgeMeshState> = _meshState.asStateFlow()

    init {
        schedulePeriodicCompute(context)
    }

    private fun loadInitialState(): LocalEdgeMeshState {
        var devId = prefs.getString("edge_device_id", null)
        if (devId.isNullOrBlank()) {
            devId = "device_${UUID.randomUUID().toString().substring(0, 8)}"
            prefs.edit().putString("edge_device_id", devId).apply()
        }

        return LocalEdgeMeshState(
            deviceId = devId,
            totalContributions = prefs.getInt("total_contributions", 0),
            bandwidthSavedBytes = prefs.getLong("bandwidth_saved_bytes", 0L),
            currentStatus = prefs.getString("current_status", "IDLE") ?: "IDLE",
            currentTrackTitle = prefs.getString("current_track_title", "") ?: "",
            recentProcessedTracks = prefs.getStringSet("recent_tracks", emptySet())?.toList() ?: emptyList()
        )
    }

    fun getDeviceId(): String = _meshState.value.deviceId

    fun updateProgress(status: String, trackTitle: String) {
        val current = _meshState.value
        val updated = current.copy(
            currentStatus = status,
            currentTrackTitle = trackTitle
        )
        _meshState.value = updated
        prefs.edit()
            .putString("current_status", status)
            .putString("current_track_title", trackTitle)
            .apply()
    }

    fun recordContribution(trackTitle: String, bandwidthSavedBytes: Long) {
        val current = _meshState.value
        val newCount = current.totalContributions + 1
        val newBandwidth = current.bandwidthSavedBytes + bandwidthSavedBytes
        val updatedRecent = (listOf(trackTitle) + current.recentProcessedTracks).take(10)

        val updated = current.copy(
            totalContributions = newCount,
            bandwidthSavedBytes = newBandwidth,
            currentStatus = "SYNCED",
            currentTrackTitle = "",
            recentProcessedTracks = updatedRecent
        )
        _meshState.value = updated

        prefs.edit()
            .putInt("total_contributions", newCount)
            .putLong("bandwidth_saved_bytes", newBandwidth)
            .putString("current_status", "SYNCED")
            .putString("current_track_title", "")
            .putStringSet("recent_tracks", updatedRecent.toSet())
            .apply()
    }

    fun scheduleImmediateCompute(context: Context) {
        val request = OneTimeWorkRequestBuilder<TitanComputeWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "titan_edge_compute_immediate",
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    fun schedulePeriodicCompute(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiresCharging(true)
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .setRequiresDeviceIdle(true)
            .build()

        val periodicRequest = PeriodicWorkRequestBuilder<TitanComputeWorker>(
            1, TimeUnit.HOURS,
            15, TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "titan_edge_compute_periodic",
            ExistingPeriodicWorkPolicy.KEEP,
            periodicRequest
        )
    }

    companion object {
        @Volatile
        private var INSTANCE: EdgeMeshRepository? = null

        fun getInstance(context: Context): EdgeMeshRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: EdgeMeshRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
