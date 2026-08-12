package com.streamify.app.data

import com.streamify.app.data.models.TrackNative
import com.streamify.app.data.models.RecommendationNative

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
    external fun updateTrackCoverArt(trackId: Int, coverArtPath: String): Boolean
    external fun updateTrackMetadata(trackId: Int, title: String, artist: String, album: String): Boolean
    external fun searchSimilarTracks(trackId: Int, topK: Int): IntArray
    external fun getRecommendations(trackId: Int, recentHistory: IntArray, userId: Int, limit: Int): Array<RecommendationNative>

    // Events
    external fun logPlayEvent(fromTrackId: Int, toTrackId: Int, userId: Int)
    external fun logSkipEvent(fromTrackId: Int, toTrackId: Int, userId: Int)
}
