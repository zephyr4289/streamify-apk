package com.streamify.app.data

import com.streamify.app.data.models.Track
import kotlin.math.exp
import kotlin.random.Random

object ReRanker {

    /**
     * Helper to normalize artist string for comparison
     */
    fun normalizeArtist(artist: String): String =
        artist.lowercase().trim().split(",", "&", "feat.", "ft.", "x", "/").firstOrNull()?.trim() ?: artist.lowercase().trim()

    /**
     * Determines tempo cluster to avoid genre/tempo monoculture (e.g. all Phonk/Hardstyle)
     */
    fun getTempoCluster(bpm: Float): String {
        return when {
            bpm <= 0f -> "UNKNOWN"
            bpm < 95f -> "CHILL_LOFI"
            bpm in 95f..125f -> "MID_POP_RNB"
            else -> "HIGH_ENERGY"
        }
    }

    /**
     * Gaussian BPM Bell Curve scoring (Sigma = 25.0)
     * Soft bell curve prevents 110-125 BPM Pop/Indie from being discarded by 140 BPM daytime target.
     */
    fun calculateGaussianBpmWeight(bpm: Float, targetBpm: Float, sigma: Float = 25.0f): Float {
        if (bpm <= 0f || targetBpm <= 0f) return 1.0f
        val diff = bpm - targetBpm
        return exp(-(diff * diff) / (2.0f * sigma * sigma))
    }

    /**
     * Samples distinct diverse seed tracks across different artists and tempo clusters.
     */
    fun getDistinctGenreSeeds(allTracks: List<Track>, limit: Int = 4): List<Track> {
        if (allTracks.isEmpty()) return emptyList()
        val distinctArtists = mutableMapOf<String, Track>()
        val distinctClusters = mutableMapOf<String, Track>()

        for (track in allTracks.shuffled(Random(System.currentTimeMillis()))) {
            val primary = normalizeArtist(track.artist)
            val cluster = getTempoCluster(track.bpm)

            if (primary.isNotBlank() && !distinctArtists.containsKey(primary)) {
                distinctArtists[primary] = track
                distinctClusters[cluster] = track
                if (distinctArtists.size >= limit) break
            }
        }

        return (distinctArtists.values.toList() + distinctClusters.values.toList()).distinctBy { it.id }.take(limit).ifEmpty { allTracks.take(limit) }
    }

    /**
     * Re-ranks and diversifies raw candidate tracks for UI shelves with MMR and Genre Saturation Capping.
     *
     * @param candidates Raw candidate list from nearest neighbor search or online discovery
     * @param maxPerArtist Maximum tracks allowed per artist (default: 2)
     * @param maxPerTempoCluster Maximum tracks allowed per tempo cluster (default: 3 to break Phonk dominance)
     * @param explorationRatio Percentage of exploration / novelty tracks to inject (default: 0.20 = 20%)
     * @param explorationPool Optional pool of novel or unplayed tracks to sample exploration from
     * @param targetBpm Optional circadian target BPM to apply Gaussian weighting
     * @param limit Desired output shelf size
     */
    fun reRank(
        candidates: List<Track>,
        maxPerArtist: Int = 2,
        maxPerTempoCluster: Int = 3,
        explorationRatio: Float = 0.20f,
        explorationPool: List<Track> = emptyList(),
        targetBpm: Float = 0.0f,
        limit: Int = 10
    ): List<Track> {
        if (candidates.isEmpty()) return explorationPool.take(limit)

        // 1. Sort candidates using Gaussian BPM weighting if targetBpm provided
        val weightedCandidates = if (targetBpm > 0f) {
            candidates.sortedByDescending { track ->
                calculateGaussianBpmWeight(track.bpm, targetBpm, sigma = 25.0f)
            }
        } else {
            candidates
        }

        val result = mutableListOf<Track>()
        val artistCounts = mutableMapOf<String, Int>()
        val clusterCounts = mutableMapOf<String, Int>()
        val seenTrackIds = mutableSetOf<Int>()

        val numExploration = (limit * explorationRatio).toInt()
        val numExploitation = limit - numExploration

        // 2. Exploitation Stage: Pick best candidates with Artist & Tempo Cluster Saturation Damping
        for (track in weightedCandidates) {
            if (result.size >= numExploitation) break
            if (track.id in seenTrackIds) continue

            val primaryArtist = normalizeArtist(track.artist)
            val cluster = getTempoCluster(track.bpm)
            val currentArtistCount = artistCounts.getOrDefault(primaryArtist, 0)
            val currentClusterCount = clusterCounts.getOrDefault(cluster, 0)

            if (currentArtistCount < maxPerArtist && (cluster == "UNKNOWN" || currentClusterCount < maxPerTempoCluster)) {
                result.add(track)
                seenTrackIds.add(track.id)
                artistCounts[primaryArtist] = currentArtistCount + 1
                clusterCounts[cluster] = currentClusterCount + 1
            }
        }

        // 3. Exploration Stage: Inject novel tracks from explorationPool across distinct clusters
        val eligibleExploration = if (explorationPool.isNotEmpty()) {
            explorationPool.filter { it.id !in seenTrackIds }
        } else {
            weightedCandidates.drop(numExploitation).filter { it.id !in seenTrackIds }
        }

        if (eligibleExploration.isNotEmpty()) {
            val shuffledExploration = eligibleExploration.shuffled(Random(System.currentTimeMillis()))
            for (track in shuffledExploration) {
                if (result.size >= limit) break
                val primaryArtist = normalizeArtist(track.artist)
                val cluster = getTempoCluster(track.bpm)
                val currentArtistCount = artistCounts.getOrDefault(primaryArtist, 0)
                val currentClusterCount = clusterCounts.getOrDefault(cluster, 0)

                if (currentArtistCount < maxPerArtist && (cluster == "UNKNOWN" || currentClusterCount < maxPerTempoCluster)) {
                    result.add(track)
                    seenTrackIds.add(track.id)
                    artistCounts[primaryArtist] = currentArtistCount + 1
                    clusterCounts[cluster] = currentClusterCount + 1
                }
            }
        }

        // 4. Fallback Fill: If still under limit, backfill remaining candidates
        for (track in weightedCandidates) {
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
            .map { it.artist.split(",", "&", "feat.", "ft.", "x", "/").firstOrNull()?.trim() ?: it.artist.trim() }
            .filter { it.isNotBlank() && !it.equals("Unknown Artist", ignoreCase = true) }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(limit)
            .map { it.key }
    }
}
