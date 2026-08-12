package com.streamify.app.util

import android.content.ContentResolver
import android.content.Context
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object MediaStoreScanner {
    
    data class LocalAudioFile(
        val id: Long,
        val title: String,
        val artist: String,
        val dataPath: String,
        val durationMs: Long
    )

    suspend fun scanLocalMusic(context: Context): List<LocalAudioFile> = withContext(Dispatchers.IO) {
        val audioFiles = mutableListOf<LocalAudioFile>()
        val resolver: ContentResolver = context.contentResolver
        val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.DURATION
        )

        // Optionally filter by size/duration to avoid short notification sounds
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} >= 30000"

        resolver.query(uri, projection, selection, null, null)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val title = cursor.getString(titleColumn) ?: "Unknown Title"
                val artist = cursor.getString(artistColumn) ?: "Unknown Artist"
                val dataPath = cursor.getString(dataColumn) ?: continue
                val durationMs = cursor.getLong(durationColumn)

                audioFiles.add(LocalAudioFile(id, title, artist, dataPath, durationMs))
            }
        }
        
        return@withContext audioFiles
    }
}
