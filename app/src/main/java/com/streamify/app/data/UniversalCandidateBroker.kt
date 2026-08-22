package com.streamify.app.data

import com.streamify.app.data.models.Track
import com.streamify.app.radio.OnlineRadioEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * QUEUE BROKER v2 — thin delegate over [OnlineRadioEngine].
 *
 * HISTORY: this broker used to fan out to Innertube + Supabase cloud-taste
 * rows + LOCAL library Markov anchors, interleave them 70/30, and — on any
 * failure — silently substitute the user's entire shuffled library as the
 * "radio". That was the source of playlists leaking into queues and of
 * intermittent builds.
 *
 * NOW: pure online construction (YTM radio → strict search-mix → Spotify
 * recommendations). Local ingestion is structurally impossible here; total
 * provider failure returns an EMPTY queue which callers surface honestly.
 *
 * The object shell (flows + signature) is preserved so every existing call
 * site keeps compiling unchanged.
 */
object UniversalCandidateBroker {

    private val _currentRecommendations = MutableStateFlow<List<Track>>(emptyList())
    val currentRecommendations: StateFlow<List<Track>> = _currentRecommendations.asStateFlow()

    private val _isFetching = MutableStateFlow(false)
    val isFetching: StateFlow<Boolean> = _isFetching.asStateFlow()

    suspend fun fetchCandidates(
        seedTrack: Track,
        activeQueue: List<Track> = emptyList(),
        targetCount: Int = 20,
        @Suppress("UNUSED_PARAMETER") allowLocalAnchors: Boolean = false
    ): List<Track> {
        _isFetching.value = true
        try {
            val result = OnlineRadioEngine.fetchCandidates(
                seedTrack = seedTrack,
                activeQueue = activeQueue,
                targetCount = targetCount
            )
            _currentRecommendations.value = result
            return result
        } finally {
            _isFetching.value = false
        }
    }
}
