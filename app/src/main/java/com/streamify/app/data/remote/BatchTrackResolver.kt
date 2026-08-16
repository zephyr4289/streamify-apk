package com.streamify.app.data.remote

import android.content.Context
import com.streamify.app.data.PlaylistRepository
import com.streamify.app.data.TrackRepository
import com.streamify.app.data.models.Track
import com.streamify.app.data.network.YouTubeMusicSearchApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

data class ImportProgress(
    val total: Int,
    val completed: Int,
    val currentTrackTitle: String,
    val playlistId: String = "",
    val isComplete: Boolean = false,
    val errorMessage: String? = null
)

object BatchTrackResolver {

    fun resolveAndImportPlaylist(
        playlist: ScrapedPlaylist,
        context: Context
    ): Flow<ImportProgress> = flow {
        val totalTracks = playlist.tracks.size
        if (totalTracks == 0) {
            emit(ImportProgress(0, 0, "", isComplete = false, errorMessage = "No tracks found in playlist link."))
            return@flow
        }

        emit(ImportProgress(total = totalTracks, completed = 0, currentTrackTitle = "Creating playlist..."))

        // 1. Create Playlist in SQLite
        val newPlaylist = PlaylistRepository.createPlaylist(
            name = playlist.name,
            description = "Imported via Streamify Universal Importer (${playlist.tracks.size} tracks)"
        )

        val semaphore = Semaphore(4) // Bounded Concurrency: max 4 simultaneous network searches
        var completedCount = 0

        // 2. Resolve Tracks
        for (scraped in playlist.tracks) {
            emit(
                ImportProgress(
                    total = totalTracks,
                    completed = completedCount,
                    currentTrackTitle = "${scraped.title} - ${scraped.artist}",
                    playlistId = newPlaylist.id
                )
            )

            try {
                semaphore.withPermit {
                    withContext(Dispatchers.IO) {
                        // Check local SQLite first
                        val allLocal = TrackRepository.getAllTracks()
                        val localMatch = allLocal.find {
                            it.title.contains(scraped.title, ignoreCase = true) ||
                            (it.artist.contains(scraped.artist, ignoreCase = true) && it.title.contains(scraped.title.take(6), ignoreCase = true))
                        }

                        if (localMatch != null) {
                            PlaylistRepository.addTrackToPlaylist(newPlaylist.id, localMatch.id)
                        } else {
                            // Search via YouTube Music API
                            val query = "${scraped.title} ${scraped.artist}"
                            val searchResults = YouTubeMusicSearchApi.search(query, maxResults = 1)
                            val bestResult = searchResults.firstOrNull()

                            if (bestResult != null) {
                                val trackModel = Track(
                                    id = 0,
                                    title = scraped.title,
                                    artist = scraped.artist.ifBlank { bestResult.uploader },
                                    album = playlist.name,
                                    durationSec = bestResult.duration,
                                    filepath = bestResult.url,
                                    coverArtPath = bestResult.thumbnail.takeIf { it.isNotBlank() },
                                    bpm = 0f,
                                    key = "",
                                    lyricsPath = null,
                                    source = "online_stream"
                                )
                                val savedTrack = TrackRepository.registerStreamedTrack(trackModel, context)
                                if (savedTrack.id > 0) {
                                    PlaylistRepository.addTrackToPlaylist(newPlaylist.id, savedTrack.id)
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                // Continue to next track even if one fails
            }

            completedCount++
        }

        // 3. Complete
        emit(
            ImportProgress(
                total = totalTracks,
                completed = totalTracks,
                currentTrackTitle = "Import Complete!",
                playlistId = newPlaylist.id,
                isComplete = true
            )
        )
    }.flowOn(Dispatchers.IO)
}
