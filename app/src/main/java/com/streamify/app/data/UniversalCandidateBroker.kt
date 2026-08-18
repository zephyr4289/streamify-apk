package com.streamify.app.data

import com.streamify.app.data.models.Track
import com.streamify.app.data.network.CanonicalSeedResolver
import com.streamify.app.data.network.YouTubeStreamResolver
import com.streamify.app.data.remote.SupabaseClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object UniversalCandidateBroker {

    private val _currentRecommendations = MutableStateFlow<List<Track>>(emptyList())
    val currentRecommendations: StateFlow<List<Track>> = _currentRecommendations.asStateFlow()

    private val _isFetching = MutableStateFlow(false)
    val isFetching: StateFlow<Boolean> = _isFetching.asStateFlow()

    /**
     * Harvests and scores candidates across Innertube, Supabase Cloud pgvector,
     * and Local C++ Markov models into a single, high-affinity recommendation pool.
     */
    suspend fun fetchCandidates(
        seedTrack: Track,
        activeQueue: List<Track> = emptyList(),
        targetCount: Int = 20
    ): List<Track> = coroutineScope {
        _isFetching.value = true
        try {
            // 1. Resolve Canonical 11-char YouTube Music Video ID in <50ms
            val canonicalId = CanonicalSeedResolver.resolveToCanonicalId(seedTrack)

            // 2. 3-Way Parallel Coroutine Fan-Out
            val innertubeDeferred = async(Dispatchers.IO) {
                try {
                    ContinuumRadioEngine.fetchRawRadioTracks(canonicalId, seedTrack)
                } catch (e: Exception) {
                    emptyList()
                }
            }

            val cloudVectorDeferred = async(Dispatchers.IO) {
                try {
                    SupabaseClient.fetchCloudSongRadio(seedTrack, limit = 25)
                } catch (e: Exception) {
                    emptyList()
                }
            }

            val localMarkovDeferred = async(Dispatchers.IO) {
                try {
                    val allLocal = TrackRepository.allTracks.value
                    if (allLocal.isNotEmpty()) {
                        allLocal.filter {
                            it.id != seedTrack.id && (
                                it.artist.equals(seedTrack.artist, ignoreCase = true) ||
                                (seedTrack.bpm > 0f && kotlin.math.abs(it.bpm - seedTrack.bpm) < 15f)
                            )
                        }.shuffled().take(15)
                    } else emptyList()
                } catch (e: Exception) {
                    emptyList()
                }
            }

            // 3. Bounded Network Await (2500ms budget for robust TLS + HTTP/2 roundtrip)
            val onlineCandidates = withTimeoutOrNull(2500L) { innertubeDeferred.await() } ?: emptyList()
            val cloudCandidates = withTimeoutOrNull(2500L) { cloudVectorDeferred.await() } ?: emptyList()
            val localCandidates = localMarkovDeferred.await()

            // 4. Build Online Candidate Pool (YouTube Music Radio + Cloud Vector)
            val onlinePool = mutableListOf<Track>().apply {
                addAll(onlineCandidates)
                addAll(cloudCandidates)
            }

            // Online Discovery Fallback: If online pool has fewer than 5 tracks, fetch search mix
            if (onlinePool.size < 5) {
                try {
                    val query = "${seedTrack.title} ${seedTrack.artist} mix".trim()
                    val dynamicBpm = if (seedTrack.bpm > 0f) seedTrack.bpm else 120f
                    val searchFallback = com.streamify.app.data.network.YouTubeMusicSearchApi.search(query, maxResults = 15).mapNotNull { item ->
                        val vid = YouTubeStreamResolver.extractVideoId(item.url) ?: return@mapNotNull null
                        Track(
                            id = -(vid.hashCode()),
                            title = item.title,
                            artist = item.uploader,
                            album = "Streamify Radio",
                            durationSec = item.duration,
                            filepath = "https://www.youtube.com/watch?v=$vid",
                            coverArtPath = item.thumbnail.ifBlank { "https://i.ytimg.com/vi/$vid/hqdefault.jpg" },
                            bpm = dynamicBpm,
                            key = "",
                            lyricsPath = null,
                            source = "online_stream"
                        )
                    }
                    onlinePool.addAll(searchFallback)
                } catch (e: Exception) {
                    // Non-fatal
                }
            }

            // 5. Native Anti-Drift & Acoustic Affinity Filter (Ranked independently)
            val rankedOnline = AntiDriftScoringEngine.filterAndRankCandidates(
                candidates = onlinePool,
                seedTrack = seedTrack,
                activeQueue = activeQueue
            )

            val rankedLocal = AntiDriftScoringEngine.filterAndRankCandidates(
                candidates = localCandidates,
                seedTrack = seedTrack,
                activeQueue = activeQueue
            )

            // 6. Discovery-First Interleaving (70% Internet Discovery / 30% Local Anchors)
            val finalTracks = mutableListOf<Track>()
            var onlineIdx = 0
            var localIdx = 0

            while (finalTracks.size < targetCount && (onlineIdx < rankedOnline.size || localIdx < rankedLocal.size)) {
                // Add up to 2 online discovery tracks
                repeat(2) {
                    if (onlineIdx < rankedOnline.size && finalTracks.size < targetCount) {
                        finalTracks.add(rankedOnline[onlineIdx++])
                    }
                }
                // Add 1 local favorite anchor
                if (localIdx < rankedLocal.size && finalTracks.size < targetCount) {
                    finalTracks.add(rankedLocal[localIdx++])
                }
                // Backfill from remaining online if local exhausted
                if (localIdx >= rankedLocal.size && onlineIdx < rankedOnline.size && finalTracks.size < targetCount) {
                    finalTracks.add(rankedOnline[onlineIdx++])
                }
                // Backfill from remaining local if online exhausted
                if (onlineIdx >= rankedOnline.size && localIdx < rankedLocal.size && finalTracks.size < targetCount) {
                    finalTracks.add(rankedLocal[localIdx++])
                }
            }

            _currentRecommendations.value = finalTracks
            finalTracks
        } catch (e: Exception) {
            e.printStackTrace()
            val activeKeys = activeQueue.map { "${it.title}:::${it.artist}".lowercase() }.toSet()
            val offlineFallback = TrackRepository.allTracks.value.filter {
                it.id != seedTrack.id && !activeKeys.contains("${it.title}:::${it.artist}".lowercase())
            }.shuffled().take(targetCount)
            _currentRecommendations.value = offlineFallback
            offlineFallback
        } finally {
            _isFetching.value = false
        }
    }
}
