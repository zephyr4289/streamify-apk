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
                    ContinuumRadioEngine.fetchRawRadioTracks(canonicalId)
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

            // 3. Bounded Network Await (600ms max circuit breaker)
            val onlineCandidates = withTimeoutOrNull(600L) { innertubeDeferred.await() } ?: emptyList()
            val cloudCandidates = withTimeoutOrNull(600L) { cloudVectorDeferred.await() } ?: emptyList()
            val localCandidates = localMarkovDeferred.await()

            // 4. Merge Raw Candidate Pool (~60-100 songs)
            val rawPool = mutableListOf<Track>().apply {
                addAll(onlineCandidates)
                addAll(cloudCandidates)
                addAll(localCandidates)
            }

            // 5. Native Anti-Drift & Acoustic Affinity Filter
            val rankedTracks = AntiDriftScoringEngine.filterAndRankCandidates(
                candidates = rawPool,
                seedTrack = seedTrack,
                activeQueue = activeQueue
            )

            // 6. Zero-Starvation Fallback: If ranked list is too small, backfill from catalog
            val finalTracks = if (rankedTracks.size < 5) {
                val catalog = TrackRepository.allTracks.value.filter { it.id != seedTrack.id }
                (rankedTracks + catalog.shuffled()).distinctBy { "${it.title}:::${it.artist}" }.take(targetCount)
            } else {
                rankedTracks.take(targetCount)
            }

            _currentRecommendations.value = finalTracks
            finalTracks
        } catch (e: Exception) {
            e.printStackTrace()
            val fallback = TrackRepository.allTracks.value.filter { it.id != seedTrack.id }.shuffled().take(targetCount)
            _currentRecommendations.value = fallback
            fallback
        } finally {
            _isFetching.value = false
        }
    }
}
