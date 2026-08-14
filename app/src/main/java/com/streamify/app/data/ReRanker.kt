package com.streamify.app.data

import com.streamify.app.data.models.Track
import kotlin.random.Random

object ReRanker {

    /**
     * Re-ranks and diversifies raw candidate tracks for UI shelves.
     *
     * @param candidates Raw candidate list from nearest neighbor search or online discovery
     * @param maxPerArtist Maximum tracks allowed per artist (default: 2)
     * @param explorationRatio Percentage of exploration / novelty tracks to inject (default: 0.20 = 20%)
     * @param explorationPool Optional pool of novel or unplayed tracks to sample exploration from
     * @param limit Desired output shelf size
     */
    fun reRank(
        candidates: List<Track>,
        maxPerArtist: Int = 2,
        explorationRatio: Float = 0.20f,
        explorationPool: List<Track> = emptyList(),
        limit: Int = 10
    ): List<Track> {
        if (candidates.isEmpty()) return explorationPool.take(limit)

        val result = mutableListOf<Track>()
        val artistCounts = mutableMapOf<String, Int>()
        val seenTrackIds = mutableSetOf<Int>()

        // Helper to normalize artist string for comparison
        fun normalizeArtist(artist: String): String =
            artist.lowercase().trim().split(",", "&", "feat.", "ft.").firstOrNull()?.trim() ?: artist.lowercase().trim()

        val numExploration = (limit * explorationRatio).toInt()
        val numExploitation = limit - numExploration

        // 1. Exploitation Stage: Pick best candidates with artist damping
        for (track in candidates) {
            if (result.size >= numExploitation) break
            if (track.id in seenTrackIds) continue

            val primaryArtist = normalizeArtist(track.artist)
            val currentCount = artistCounts.getOrDefault(primaryArtist, 0)

            if (currentCount < maxPerArtist) {
                result.add(track)
                seenTrackIds.add(track.id)
                artistCounts[primaryArtist] = currentCount + 1
            }
        }

        // 2. Exploration Stage: Inject novel tracks from explorationPool or tail candidates
        val eligibleExploration = if (explorationPool.isNotEmpty()) {
            explorationPool.filter { it.id !in seenTrackIds }
        } else {
            candidates.drop(numExploitation).filter { it.id !in seenTrackIds }
        }

        if (eligibleExploration.isNotEmpty()) {
            val shuffledExploration = eligibleExploration.shuffled(Random(System.currentTimeMillis()))
            for (track in shuffledExploration) {
                if (result.size >= limit) break
                val primaryArtist = normalizeArtist(track.artist)
                val currentCount = artistCounts.getOrDefault(primaryArtist, 0)
                if (currentCount < maxPerArtist) {
                    result.add(track)
                    seenTrackIds.add(track.id)
                    artistCounts[primaryArtist] = currentCount + 1
                }
            }
        }

        // 3. Fallback Fill: If still under limit, backfill remaining candidates
        for (track in candidates) {
            if (result.size >= limit) break
            if (track.id !in seenTrackIds) {
                result.add(track)
                seenTrackIds.add(track.id)
            }
        }

        return result
    }

    /**
     * Extracts top distinct artist names from a track list for 2-hop graph discovery.
     */
    fun extractTopArtists(tracks: List<Track>, limit: Int = 3): List<String> {
        return tracks
            .map { it.artist.split(",", "&", "feat.", "ft.").firstOrNull()?.trim() ?: it.artist.trim() }
            .filter { it.isNotBlank() && !it.equals("Unknown Artist", ignoreCase = true) }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(limit)
            .map { it.key }
    }
}
