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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.abs

data class LocalEdgeMeshState(
    val deviceId: String = "",
    val totalContributions: Int = 0,
    val bandwidthSavedBytes: Long = 0L,
    val currentStatus: String = "IDLE", // "IDLE", "COMPUTING", "SYNCED"
    val currentTrackTitle: String = "",
    val recentProcessedTracks: List<String> = emptyList(),
    val activePeersCount: Int = 1,
    val normalizedTracksCount: Int = 0
) {
    val bandwidthSavedMb: Double
        get() = (bandwidthSavedBytes.toDouble() / (1024.0 * 1024.0))
}

class EdgeMeshRepository private constructor(private val context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("edge_mesh_prefs", Context.MODE_PRIVATE)
    
    private val _meshState = MutableStateFlow(loadInitialState())
    val meshState: StateFlow<LocalEdgeMeshState> = _meshState.asStateFlow()

    // In-memory LUFS and Lyric Consensus Cache
    private val lufsCache = ConcurrentHashMap<String, Float>()
    private val lyricOffsetsMap = ConcurrentHashMap<String, MutableList<Long>>()

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
            recentProcessedTracks = prefs.getStringSet("recent_tracks", emptySet())?.toList() ?: emptyList(),
            normalizedTracksCount = prefs.getInt("normalized_tracks_count", 0)
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

    // --- SUPERPOWER 1: EBU R128 Auto-Gain Normalization ---
    fun recordTrackLufs(trackId: String, lufs: Float) {
        lufsCache[trackId] = lufs
        prefs.edit().putFloat("lufs_$trackId", lufs).apply()
    }

    fun getGainOffsetForTrack(trackId: String, targetLufs: Float = -14.0f): Float {
        val trackLufs = lufsCache[trackId] ?: prefs.getFloat("lufs_$trackId", -14.0f)
        if (trackLufs == 0.0f || trackLufs == targetLufs) return 1.0f
        val gainDb = (targetLufs - trackLufs).coerceIn(-12.0f, 12.0f)
        return Math.pow(10.0, (gainDb / 20.0)).toFloat()
    }

    // --- SUPERPOWER 2: Crowdsourced Lyric Timing Consensus (MAD Algorithm) ---
    fun recordLyricOffsetNudge(trackId: String, offsetDeltaMs: Long) {
        val list = lyricOffsetsMap.getOrPut(trackId) { mutableListOf() }
        synchronized(list) {
            list.add(offsetDeltaMs)
        }
        val consensus = calculateMadConsensus(list)
        prefs.edit().putLong("lyric_offset_$trackId", consensus).apply()
        recordContribution("Lyric Consensus ($trackId)", 1024L)
    }

    fun getConsensusLyricOffset(trackId: String): Long {
        return prefs.getLong("lyric_offset_$trackId", 0L)
    }

    private fun calculateMadConsensus(offsets: List<Long>): Long {
        if (offsets.isEmpty()) return 0L
        if (offsets.size < 3) return offsets.average().toLong()
        val sorted = offsets.sorted()
        val median = sorted[sorted.size / 2]
        val deviations = sorted.map { abs(it - median) }.sorted()
        val mad = deviations[deviations.size / 2].coerceAtLeast(150L)
        val inliers = sorted.filter { abs(it - median) <= (2 * mad) }
        return if (inliers.isNotEmpty()) inliers.average().toLong() else median
    }

    // --- SUPERPOWER 3 & 4: Contribution & Bandwidth Tracking ---
    fun recordContribution(trackTitle: String, bandwidthSavedBytes: Long) {
        val current = _meshState.value
        val newCount = current.totalContributions + 1
        val newBandwidth = current.bandwidthSavedBytes + bandwidthSavedBytes
        val newNorm = current.normalizedTracksCount + 1
        val updatedRecent = (listOf(trackTitle) + current.recentProcessedTracks).distinct().take(10)

        val updated = current.copy(
            totalContributions = newCount,
            bandwidthSavedBytes = newBandwidth,
            currentStatus = "SYNCED",
            currentTrackTitle = "",
            recentProcessedTracks = updatedRecent,
            normalizedTracksCount = newNorm
        )
        _meshState.value = updated

        prefs.edit()
            .putInt("total_contributions", newCount)
            .putLong("bandwidth_saved_bytes", newBandwidth)
            .putInt("normalized_tracks_count", newNorm)
            .putString("current_status", "SYNCED")
            .putString("current_track_title", "")
            .putStringSet("recent_tracks", updatedRecent.toSet())
            .apply()
    }

    fun scheduleOpportunisticCompute(
        context: Context,
        trackId: String,
        trackTitle: String,
        trackArtist: String,
        audioPath: String
    ) {
        val data = Data.Builder()
            .putString("track_id", trackId)
            .putString("track_title", trackTitle)
            .putString("track_artist", trackArtist)
            .putString("audio_path", audioPath)
            .build()

        val request = OneTimeWorkRequestBuilder<TitanComputeWorker>()
            .setInputData(data)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "titan_edge_opportunistic_$trackId",
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    fun scheduleImmediateCompute(context: Context) {
        val request = OneTimeWorkRequestBuilder<TitanComputeWorker>()
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "titan_edge_compute_immediate",
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    fun schedulePeriodicCompute(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val periodicRequest = PeriodicWorkRequestBuilder<TitanComputeWorker>(
            2, TimeUnit.HOURS,
            30, TimeUnit.MINUTES
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
