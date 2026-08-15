package com.streamify.app.data.models

data class RecommendationNative(
    val trackId: Int,
    val score: Float,
    val vectorScore: Float = 0f,
    val bpmMatchScore: Float = 0.5f
)

