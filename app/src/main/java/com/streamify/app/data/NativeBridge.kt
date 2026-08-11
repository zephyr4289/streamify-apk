package com.streamify.app.data

import com.streamify.app.data.models.TrackNative

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
}
