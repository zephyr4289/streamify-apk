package com.streamify.app.data.models

data class RecommendationNative @JvmOverloads constructor(
    val trackId: Int,
    val score: Float,
    val vectorScore: Float = 0f,
    val bpmMatchScore: Float = 0.5f
) {
    constructor(trackId: Int, score: Float) : this(trackId, score, 0f, 0.5f)
}

