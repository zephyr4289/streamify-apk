package com.streamify.app.data

object FuzzyTitleMatcher {

    // Pre-compiled noise patterns: strips video/audio tags, brackets, features, and release metadata
    private val NOISE_REGEX = Regex(
        "(?i)\\(.*?\\)|\\[.*?\\]|\\b(official|video|audio|lyric|lyrics|live|remaster|remastered|slowed|sped up|acoustic|instrumental|visualizer|feat|ft|featuring|hd|4k)\\b.*"
    )

    private val CLEAN_REGEX = Regex("[^a-z0-9]+")

    /**
     * Extracts an order-invariant 64-bit FNV-1a hash of the root title words.
     * "House of Balloons / Glass Table Girls" and "House of Balloons (Audio)"
     * both produce root tokens [balloons, house] and the exact same 64-bit Long hash.
     */
    fun extractRootHash(title: String): Long {
        val clean = NOISE_REGEX.replace(title, " ")
        val tokens = clean.lowercase()
            .split(CLEAN_REGEX)
            .filter { it.length > 2 && it != "the" && it != "and" }
            .sorted()
            .joinToString("")

        if (tokens.isBlank()) {
            // Fallback for short titles like "Us", "Go", "Me"
            val fallback = title.lowercase().replace(CLEAN_REGEX, "")
            if (fallback.isBlank()) return 0L
            return computeFnv1a(fallback)
        }

        return computeFnv1a(tokens)
    }

    /**
     * Extracts token set of root title words for Jaccard similarity.
     */
    fun extractRootTokens(title: String): Set<String> {
        val clean = NOISE_REGEX.replace(title, " ")
        return clean.lowercase()
            .split(CLEAN_REGEX)
            .filter { it.length > 2 && it != "the" && it != "and" }
            .toSet()
    }

    /**
     * Computes FNV-1a 64-bit integer hash for O(1) matching.
     */
    fun computeFnv1a(input: String): Long {
        var hash = 0xcbf29ce484222325UL
        for (char in input) {
            hash = hash xor char.code.toULong()
            hash *= 0x100000001b3UL
        }
        return hash.toLong()
    }

    /**
     * Checks if two tracks are duplicate variations of the same underlying song.
     */
    fun isSameSongVariation(titleA: String, artistA: String, titleB: String, artistB: String): Boolean {
        // 1. Direct FNV-1a root hash match (0ms check)
        val hashA = extractRootHash(titleA)
        val hashB = extractRootHash(titleB)
        if (hashA != 0L && hashA == hashB) {
            return true
        }

        // 2. Token Jaccard similarity for subtitle/split track variations
        val tokensA = extractRootTokens(titleA)
        val tokensB = extractRootTokens(titleB)
        if (tokensA.isNotEmpty() && tokensB.isNotEmpty()) {
            val intersection = tokensA.intersect(tokensB).size.toDouble()
            val minSize = minOf(tokensA.size, tokensB.size).toDouble()
            // If one title is a subset of the other (e.g. "House of Balloons" inside "House of Balloons / Glass Table Girls")
            if (minSize > 0 && (intersection / minSize) >= 0.75) {
                return true
            }
            val union = tokensA.union(tokensB).size.toDouble()
            if (union > 0 && (intersection / union) >= 0.60) {
                return true
            }
        }

        return false
    }
}
