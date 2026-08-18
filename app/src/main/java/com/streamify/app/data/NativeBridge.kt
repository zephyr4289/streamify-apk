package com.streamify.app.data

import com.streamify.app.data.models.TrackNative
import com.streamify.app.data.models.RecommendationNative
import com.streamify.app.data.models.OrchestratorStatusNative

object NativeBridge {
    init {
        try {
            System.loadLibrary("streamify_core")
        } catch (e: Throwable) {
            android.util.Log.e("StreamifyNative", "Failed to load streamify_core native library", e)
        }
    }
    external fun stringFromJNI(): String
    
    // Database
    external fun initDatabase(dbPath: String): Boolean
    external fun getAllTracks(): Array<TrackNative>
    external fun searchTracks(query: String): Array<TrackNative>
    external fun insertTrack(filepath: String, title: String, artist: String, album: String, durationSec: Int, bpm: Float): Long
    
    // Liked Songs
    external fun toggleLike(userId: Int, trackId: Int): Boolean
    external fun getLikedTracks(userId: Int): Array<TrackNative>

    // AI Recommender / VectorStore / Audio Pipeline
    external fun initVectorStore(binPath: String): Boolean
    external fun initAudioPipeline(modelPath: String): Boolean
    external fun processAudioFile(trackId: Int, filePath: String): Int
    external fun extractBPM(trackId: Int, filePath: String): Float
    external fun updateTrackCoverArt(trackId: Int, coverArtPath: String): Boolean
    external fun updateTrackMetadata(trackId: Int, title: String, artist: String, album: String): Boolean
    external fun searchSimilarTracks(trackId: Int, topK: Int): IntArray
    external fun getRecommendations(trackId: Int, recentHistory: IntArray, userId: Int, limit: Int): Array<RecommendationNative>

    // Events
    external fun logPlayEvent(fromTrackId: Int, toTrackId: Int, userId: Int)
    external fun logSkipEvent(fromTrackId: Int, toTrackId: Int, userId: Int)

    // Resource-Aware Dynamic Task Orchestrator (Project Prometheus)
    external fun setHighPriorityActive(active: Boolean)
    external fun setBatterySaverActive(active: Boolean)
    external fun setTotalAiTasks(total: Int)
    external fun getOrchestratorStatus(): OrchestratorStatusNative?

    // Stream Persistence & Top Rotation
    external fun upsertStreamedTrack(
        filepath: String,
        title: String,
        artist: String,
        album: String,
        durationSec: Int,
        coverArtPath: String,
        lyricsPath: String,
        bpm: Float,
        key: String
    ): Int
    external fun recordTrackPlay(trackId: Int): Boolean
    external fun getTopPlayedTracks(limit: Int): Array<TrackNative>

    // Session & Long-Term Taste Profiling
    external fun updateSessionVector(trackId: Int, alpha: Float)
    external fun getSessionRecommendations(limit: Int): Array<RecommendationNative>
    external fun getLongTermRecommendations(userId: Int, limit: Int): Array<RecommendationNative>

    // Native DSP Soft-Knee Limiter (Project Sonic Maxx)
    external fun processLimiterShorts(buffer: ShortArray, length: Int, threshold: Float, kneeWidth: Float)
    external fun processLimiterFloats(buffer: FloatArray, length: Int, threshold: Float, kneeWidth: Float)

    // Universal Migration & Fuzzy Matcher (Project Janus)
    external fun findFuzzyTrackMatch(title: String, artist: String): Int
    external fun getTracksBatch(offset: Int, limit: Int): Array<TrackNative>

    // Project Chronos: Circadian Listening Patterns
    external fun logEngagementEvent(trackId: Int, durationSec: Int, completionRatio: Float, hourOfDay: Int): Boolean
    external fun getCircadianRecommendations(hourOfDay: Int, limit: Int): Array<RecommendationNative>
    external fun getCircadianSlot(hourOfDay: Int): String

    // Project Nexus: Scrubber Hook Telemetry & Co-occurrence Graph
    external fun logHookTelemetry(trackId: Int, favoriteSeekMs: Long, lyricsDwellSec: Int, volumeFlare: Int): Boolean
    external fun recordTrackCooccurrence(trackAId: Int, trackBId: Int): Boolean
    external fun getFavoriteSeekMs(trackId: Int): Long
    external fun getCooccurrenceRecommendations(trackId: Int, limit: Int): IntArray

    // C++20 Lock-Free Psychometric Event Queue
    const val EVENT_SCRUB_SEEK = 1
    const val EVENT_VOLUME_CHANGE = 2
    const val EVENT_LYRICS_DWELL = 3
    const val EVENT_PLAY_TRANSITION = 4

    external fun pushTelemetryEvent(type: Int, trackId: Long, value: Float)
    external fun getMarkovProbability(fromTrackId: Int, toTrackId: Int): Float
    external fun get2ndOrderMarkovProbability(trackA: Int, trackB: Int, trackC: Int, alpha: Float = 0.1f): Float
    external fun getSatiationPenalty(trackId: Int): Float

    // Psychoacoustic Dynamic LUFS Normalizer
    external fun processLufsNormalizerFloats(buffer: FloatArray, length: Int, targetLufs: Float)
    external fun processLufsNormalizerShorts(buffer: ShortArray, length: Int, targetLufs: Float)
    external fun getDynamicTargetLufs(): Float

    // Project Titan: Distributed Edge Compute & Proof-of-Compute
    external fun generateProofOfCompute(buffer: FloatArray, length: Int, nonce: String): String

    // Hybrid Asymmetric Recommendation Engine (K-Means & Last.fm Similarity)
    external fun getTargetBpmForTimeSlot(slotOrdinal: Int): Float
    external fun getVectorRecommendations(
        currentTrackId: Int,
        timeWeight: Float,
        deviceWeight: Float,
        bpmTarget: Float,
        limit: Int
    ): Array<RecommendationNative>
    external fun updateTrackEmbedding(trackId: Int, embedding: FloatArray): Boolean
    external fun getTrackEmbedding(trackId: Int): FloatArray?
    external fun cacheSimilarTracks(
        trackId: Int,
        titles: Array<String>,
        artists: Array<String>,
        mbids: Array<String>,
        weights: FloatArray
    ): Boolean

    // Project Pulse: Sub-15ms Precision Time Protocol (IEEE 1588)
    external fun processPtpTimestamps(t0: Long, t1: Long, t2: Long, t3: Long): Long
    external fun getSynchronizedClockMs(): Long
    external fun getPtpClockOffsetNanos(): Long
    external fun getPtpRttNanos(): Long
    external fun resetPtpState()

    // Zhipu AI NDK Key Vault
    external fun getZhipuKey(index: Int): String

    // Atomic Database Purge
    external fun nukeLocalDatabase(): Boolean

    // Project Fluid: C++20 RK4 AirDrop Fluid Dynamics Engine
    external fun stepAirDropPhysics(
        inOutBuffer: FloatArray,
        targetX: Float,
        targetY: Float,
        initialDist: Float,
        dt: Float
    )

    // Project Nexus: In-Stream Acoustic DNA & Core Pinning
    external fun pinToLittleCores()
    external fun analyzePcmAcousticDNA(
        directBuffer: java.nio.ByteBuffer,
        byteCount: Int,
        sampleRate: Int,
        channelCount: Int,
        outResults: FloatArray
    ): String

    // Project SLYR: Real-Time Syllable Karaoke & Wiener–Khinchin Cross-Correlation
    external fun calculateLyricDrift(
        directPcmBuffer: java.nio.ByteBuffer,
        pcmByteCount: Int,
        textOnsetsMs: LongArray,
        onsetCount: Int,
        sampleRate: Int,
        channelCount: Int
    ): Int

    // ═══════════════════════════════════════════════════════════════
    // PROJECT TITAN: HIGH-PERFORMANCE RUST CORE ENGINE (JNI / FFI)
    // ═══════════════════════════════════════════════════════════════
    external fun rustFuzzyRankCandidates(query: String, candidatesJson: String): String?
    external fun rustCalculateSimilarity(s1: String, s2: String): Float
    external fun rustParseYouTubePlaylist(jsonBytes: ByteArray): String?
    external fun rustComputeFftSpectrum(pcmFloats: FloatArray, barCount: Int, outBars: FloatArray): Int
    external fun rustProcessEqualizerFrame(pcmFloats: FloatArray, channels: Int, gains: FloatArray?): Int
    external fun rustDownloadStreamDirect(streamUrl: String, destPath: String): String?
}



