package com.streamify.app.data.remote

import android.content.Context
import com.streamify.app.data.FuzzyTitleMatcher
import com.streamify.app.data.PlaylistRepository
import com.streamify.app.data.TrackRepository
import com.streamify.app.data.models.Track
import com.streamify.app.data.network.YouTubeMusicSearchApi
import com.streamify.app.data.network.YouTubeStreamResolver
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicInteger

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

        emit(ImportProgress(total = totalTracks, completed = 0, currentTrackTitle = "Initializing fast importer..."))

        // 1. Create Playlist Once
        val newPlaylist = PlaylistRepository.createPlaylist(
            name = playlist.name,
            description = "Imported playlist (${playlist.tracks.size} tracks)"
        )

        // 2. Pre-Load Local Library in Memory for O(1) Lookups (Eliminates 100 SQLite scans)
        val allLocal = TrackRepository.getAllTracks()
        val localTrackMap = HashMap<Long, Track>()
        for (t in allLocal) {
            val hash = FuzzyTitleMatcher.extractRootHash(t.title)
            if (hash != 0L) {
                localTrackMap[hash] = t
            }
        }

        val completedCount = AtomicInteger(0)
        val resolvedTrackIds = java.util.Collections.synchronizedList(mutableListOf<Int>())

        // 3. Bounded Parallelism (8 Concurrent Network Workers)
        coroutineScope {
            val semaphore = Semaphore(8)
            val deferredList = playlist.tracks.map { scraped ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        try {
                            val rootHash = FuzzyTitleMatcher.extractRootHash(scraped.title)
                            val localMatch = if (rootHash != 0L) localTrackMap[rootHash] else null

                            val trackId = if (localMatch != null && localMatch.id > 0) {
                                localMatch.id
                            } else {
                                // Search YouTube Music Innertube API
                                val query = "${scraped.title} ${scraped.artist}".trim()
                                val searchResults = YouTubeMusicSearchApi.search(query, maxResults = 1)
                                val bestResult = searchResults.firstOrNull()

                                if (bestResult != null) {
                                    val videoId = YouTubeStreamResolver.extractVideoId(bestResult.url) ?: ""
                                    val canonicalUrl = if (videoId.isNotBlank()) {
                                        YouTubeStreamResolver.getCanonicalWatchUrl(videoId)
                                    } else {
                                        bestResult.url
                                    }

                                    val trackModel = Track(
                                        id = 0,
                                        title = scraped.title,
                                        artist = scraped.artist.ifBlank { bestResult.uploader },
                                        album = playlist.name,
                                        durationSec = bestResult.duration,
                                        filepath = canonicalUrl, // CANONICAL WATCH URL (Never expires!)
                                        coverArtPath = bestResult.thumbnail.takeIf { it.isNotBlank() },
                                        bpm = 0f,
                                        key = "",
                                        lyricsPath = null,
                                        source = "online_stream"
                                    )
                                    val savedTrack = TrackRepository.registerStreamedTrack(trackModel, context)
                                    savedTrack.id
                                } else 0
                            }

                            if (trackId > 0) {
                                resolvedTrackIds.add(trackId)
                            }
                        } catch (e: Exception) {
                            // Continue on individual failure
                        } finally {
                            val done = completedCount.incrementAndGet()
                            emit(
                                ImportProgress(
                                    total = totalTracks,
                                    completed = done,
                                    currentTrackTitle = "${scraped.title} - ${scraped.artist}",
                                    playlistId = newPlaylist.id
                                )
                            )
                        }
                    }
                }
            }

            deferredList.awaitAll()
        }

        // 4. Single Disk/DB Write: Overwrite Playlist Tracks Once at the End
        PlaylistRepository.overwritePlaylistTracks(newPlaylist.id, resolvedTrackIds.toList())

        // 5. Complete
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
