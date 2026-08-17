package com.streamify.app.data.models

data class TrackNative(
    val id: Int,
    val filepath: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationSec: Int,
    val bpm: Float,
    val key: String,
    val vectorOffset: Int,
    val coverArtPath: String,
    val lyricsPath: String,
    val source: String,
    val isProcessed: Int,
    val downloadQuality: String
)

@androidx.compose.runtime.Stable
data class Track(
    val id: Int = 0,
    val filepath: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val durationSec: Int = 0,
    val bpm: Float = 0f,
    val key: String = "",
    val coverArtPath: String? = null,
    val lyricsPath: String? = null,
    val source: String = "local",
    val isLiked: Boolean = false,
    val isProcessed: Boolean = false,
    val genre: String = "",
    val playCount: Int = 0
) {
    val filePath: String get() = filepath
}

fun TrackNative.toTrack() = Track(
    id = id,
    filepath = filepath,
    title = title,
    artist = artist,
    album = album,
    durationSec = durationSec,
    bpm = bpm,
    key = key,
    coverArtPath = coverArtPath.ifBlank { null },
    lyricsPath = lyricsPath.ifBlank { null },
    source = source,
    isProcessed = isProcessed == 1
)
