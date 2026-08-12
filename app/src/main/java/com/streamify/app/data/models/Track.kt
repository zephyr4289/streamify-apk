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

data class Track(
    val id: Int,
    val filepath: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationSec: Int,
    val bpm: Float,
    val key: String,
    val coverArtPath: String?,
    val lyricsPath: String?,
    val source: String,
    val isLiked: Boolean = false
)

fun TrackNative.toTrack() = Track(
    id = id,
    filepath = filepath,
    title = title,
    artist = artist,
    album = album,
    durationSec = durationSec,
    bpm = bpm,
    key = key,
    coverArtPath = coverArtPath.takeIf { it.isNotEmpty() },
    lyricsPath = lyricsPath.takeIf { it.isNotEmpty() },
    source = source
)
