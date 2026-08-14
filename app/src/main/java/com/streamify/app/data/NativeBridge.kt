package com.streamify.app.data

import com.streamify.app.data.models.TrackNative
import com.streamify.app.data.models.RecommendationNative
import com.streamify.app.data.models.OrchestratorStatusNative

object NativeBridge {
    init { System.loadLibrary("streamify_core") }
    external fun stringFromJNI(): String
    
    // Database
    external fun initDatabase(dbPath: String): Boolean
    external fun getAllTracks(): Array<TrackNative>
    external fun searchTracks(query: String): Array<TrackNative>
    external fun insertTrack(filepath: String, title: String, artist: String, album: String, durationSec: Int, bpm: Float): Long
    
    // Liked Songs
    external fun toggleLike(userId: Int, trackId: Int): Boolean
    external fun getLikedTracks(userId: Int): Array<TrackNative>

    // AI Recommender / VectorStore / Audio Pipeline
    external fun initVectorStore(binPath: String): Boolean
    external fun initAudioPipeline(modelPath: String): Boolean
    external fun processAudioFile(trackId: Int, filePath: String): Int
    external fun extractBPM(trackId: Int, filePath: String): Float
    external fun updateTrackCoverArt(trackId: Int, coverArtPath: String): Boolean
    external fun updateTrackMetadata(trackId: Int, title: String, artist: String, album: String): Boolean
    external fun searchSimilarTracks(trackId: Int, topK: Int): IntArray
    external fun getRecommendations(trackId: Int, recentHistory: IntArray, userId: Int, limit: Int): Array<RecommendationNative>

    // Events
    external fun logPlayEvent(fromTrackId: Int, toTrackId: Int, userId: Int)
    external fun logSkipEvent(fromTrackId: Int, toTrackId: Int, userId: Int)

    // Resource-Aware Dynamic Task Orchestrator (Project Prometheus)
    external fun setHighPriorityActive(active: Boolean)
    external fun setBatterySaverActive(active: Boolean)
    external fun setTotalAiTasks(total: Int)
    external fun getOrchestratorStatus(): OrchestratorStatusNative?

    // Stream Persistence & Top Rotation
    external fun upsertStreamedTrack(
        filepath: String,
        title: String,
        artist: String,
        album: String,
        durationSec: Int,
        coverArtPath: String,
        lyricsPath: String,
        bpm: Float,
        key: String
    ): Int
    external fun recordTrackPlay(trackId: Int): Boolean
    external fun getTopPlayedTracks(limit: Int): Array<TrackNative>

    // Session & Long-Term Taste Profiling
    external fun updateSessionVector(trackId: Int, alpha: Float)
    external fun getSessionRecommendations(limit: Int): Array<RecommendationNative>
    external fun getLongTermRecommendations(userId: Int, limit: Int): Array<RecommendationNative>

    // Native DSP Soft-Knee Limiter (Project Sonic Maxx)
    external fun processLimiterShorts(buffer: ShortArray, length: Int, threshold: Float, kneeWidth: Float)
    external fun processLimiterFloats(buffer: FloatArray, length: Int, threshold: Float, kneeWidth: Float)
}
