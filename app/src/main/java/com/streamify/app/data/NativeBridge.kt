package com.streamify.app.data

import com.streamify.app.data.models.OrchestratorStatusNative
import com.streamify.app.data.models.RecommendationNative
import com.streamify.app.data.models.TrackNative

object NativeBridge {
    init {
        try {
            System.loadLibrary("streamify_core_rs")
        } catch (e: UnsatisfiedLinkError) {
            e.printStackTrace()
        }
        try {
            System.loadLibrary("streamify_native_core")
        } catch (e: UnsatisfiedLinkError) {
            e.printStackTrace()
        }
        try {
            System.loadLibrary("streamify_core")
        } catch (e: UnsatisfiedLinkError) {
            e.printStackTrace()
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // PHASE 3: C++20 DSP & 128-D VECTOR STORE INTERFACES
    // ═══════════════════════════════════════════════════════════════
    fun pinToLittleCores(): Boolean = try {
        nativePinToLittleCores()
    } catch (e: Throwable) {
        false
    }

    fun processLivePcmTap(directBuffer: java.nio.ByteBuffer, sampleCount: Int): Float {
        if (!directBuffer.isDirect) throw IllegalArgumentException("Buffer must be allocated direct")
        return try {
            nativeProcessPcmTap(directBuffer, sampleCount)
        } catch (e: Throwable) {
            1.0f
        }
    }

    fun insertVector(trackId: Long, embedding: FloatArray) {
        require(embedding.size == 128) { "Embedding must be exactly 128 dimensions" }
        try {
            nativeInsertVector(trackId, embedding)
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    fun queryTopK(targetEmbedding: FloatArray, k: Int): LongArray {
        require(targetEmbedding.size == 128) { "Embedding must be exactly 128 dimensions" }
        return try {
            nativeQueryTopK(targetEmbedding, k) ?: LongArray(0)
        } catch (e: Throwable) {
            LongArray(0)
        }
    }

    private external fun nativePinToLittleCores(): Boolean
    private external fun nativeProcessPcmTap(directBuffer: java.nio.ByteBuffer, sampleCount: Int): Float
    private external fun nativeInsertVector(trackId: Long, embedding: FloatArray)
    private external fun nativeQueryTopK(targetEmbedding: FloatArray, k: Int): LongArray?

    // ═══════════════════════════════════════════════════════════════
    // PHASE 4: SLYR PARSER & WIENER-KHINCHIN LYRIC ALIGNMENT
    // ═══════════════════════════════════════════════════════════════
    fun findActiveSlyrLine(slyrBytes: ByteArray, slyrLen: Int, playheadMs: Int): Int {
        return try {
            nativeFindActiveSlyrLine(slyrBytes, slyrLen, playheadMs)
        } catch (e: Throwable) {
            -1
        }
    }

    fun calculateDriftOffset(
        vocalEnergy: FloatArray, vocalLen: Int,
        lyricOnsets: FloatArray, lyricLen: Int
    ): Int {
        return try {
            nativeCalculateDriftOffset(vocalEnergy, vocalLen, lyricOnsets, lyricLen)
        } catch (e: Throwable) {
            0
        }
    }

    private external fun nativeFindActiveSlyrLine(
        slyrBuffer: ByteArray, slyrLen: Int, playheadMs: Int
    ): Int

    private external fun nativeCalculateDriftOffset(
        vocalEnergy: FloatArray, vocalLen: Int,
        lyricOnsets: FloatArray, lyricLen: Int
    ): Int

    private const val BUFFER_SIZE = 512

    // ═══════════════════════════════════════════════════════════════
    // PHASE 2: JIT STREAM RESOLVER & CDN EXTRACTION
    // ═══════════════════════════════════════════════════════════════
    fun resolveCdnUrl(
        videoId: String?,
        isrc: String?,
        title: String,
        artist: String
    ): String? {
        val videoIdBytes = videoId?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
        val isrcBytes = isrc?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
        val titleBytes = title.toByteArray(Charsets.UTF_8)
        val artistBytes = artist.toByteArray(Charsets.UTF_8)
        val outBuffer = ByteArray(BUFFER_SIZE)
        val written = try {
            nativeResolveTrackCdn(
                videoIdBytes, videoIdBytes.size,
                isrcBytes, isrcBytes.size,
                titleBytes, titleBytes.size,
                artistBytes, artistBytes.size,
                outBuffer, outBuffer.size
            )
        } catch (e: UnsatisfiedLinkError) {
            -1
        }
        return if (written > 0) String(outBuffer, 0, written, Charsets.UTF_8) else null
    }

    private external fun nativeResolveTrackCdn(
        videoId: ByteArray, videoIdLen: Int,
        isrc: ByteArray, isrcLen: Int,
        title: ByteArray, titleLen: Int,
        artist: ByteArray, artistLen: Int,
        outBuf: ByteArray, outBufLen: Int
    ): Int

    // ═══════════════════════════════════════════════════════════════
    // PHASE 1: SAPISID AUTH & CANONICAL AUDIO DESCRIPTOR (CAD)
    // ═══════════════════════════════════════════════════════════════
    fun getSapisidHash(sapisid: String, origin: String = "https://music.youtube.com"): String? {
        if (sapisid.isBlank()) return null
        return try {
            nativeGenerateSapisidHash(sapisid.trim(), origin.trim())
        } catch (e: Throwable) {
            null
        }
    }

    external fun nativeGenerateCadId(title: String, artist: String, durationSec: Int): String

    private external fun nativeGenerateSapisidHash(
        sapisid: String,
        origin: String
    ): String?

    // ═══════════════════════════════════════════════════════════════
    // CORE C++ ENGINE JNI BINDINGS
    // ═══════════════════════════════════════════════════════════════
    external fun stringFromJNI(): String
    external fun initDatabase(dbPath: String): Boolean
    external fun getAllTracks(): Array<TrackNative>
    external fun searchTracks(query: String): Array<TrackNative>
    external fun insertTrack(filepath: String, title: String, artist: String, album: String, durationSec: Int, bpm: Float): Long
    external fun toggleLike(userId: Int, trackId: Int): Boolean
    external fun getLikedTracks(userId: Int): Array<TrackNative>

    external fun initVectorStore(binPath: String): Boolean
    external fun searchSimilarTracks(trackId: Int, topK: Int): IntArray

    external fun getRecommendations(trackId: Int, recentHistory: IntArray, userId: Int, limit: Int): Array<RecommendationNative>
    external fun logPlayEvent(fromTrackId: Int, toTrackId: Int, userId: Int)
    external fun logSkipEvent(fromTrackId: Int, toTrackId: Int, userId: Int)

    external fun initAudioPipeline(modelPath: String): Boolean
    external fun processAudioFile(trackId: Int, filePath: String): Int
    external fun extractBPM(trackId: Int, filePath: String): Float
    external fun updateTrackCoverArt(trackId: Int, coverArtPath: String): Boolean
    external fun updateTrackMetadata(trackId: Int, title: String, artist: String, album: String): Boolean

    external fun setHighPriorityActive(active: Boolean)
    external fun setTotalAiTasks(total: Int)
    external fun getOrchestratorStatus(): OrchestratorStatusNative?
    external fun setBatterySaverActive(active: Boolean)

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

    external fun updateSessionVector(trackId: Int, alpha: Float)
    external fun getSessionRecommendations(limit: Int): Array<RecommendationNative>
    external fun getLongTermRecommendations(userId: Int, limit: Int): Array<RecommendationNative>

    external fun processLimiterShorts(buffer: ShortArray, length: Int, threshold: Float, kneeWidth: Float)
    external fun processLimiterFloats(buffer: FloatArray, length: Int, threshold: Float, kneeWidth: Float)

    external fun findFuzzyTrackMatch(title: String, artist: String): Int
    external fun getTracksBatch(offset: Int, limit: Int): Array<TrackNative>

    external fun logEngagementEvent(trackId: Int, durationSec: Int, completionRatio: Float, hourOfDay: Int): Boolean
    external fun getCircadianRecommendations(hourOfDay: Int, limit: Int): Array<RecommendationNative>
    external fun getCircadianSlot(hourOfDay: Int): String

    external fun logHookTelemetry(trackId: Int, favoriteSeekMs: Long, lyricsDwellSec: Int, volumeFlare: Int): Boolean
    external fun recordTrackCooccurrence(trackAId: Int, trackBId: Int): Boolean
    external fun getFavoriteSeekMs(trackId: Int): Long
    external fun getCooccurrenceRecommendations(trackId: Int, limit: Int): IntArray

    const val EVENT_PLAY_START = 1
    const val EVENT_SCRUB_SEEK = 2
    const val EVENT_LYRICS_DWELL = 3
    const val EVENT_VOLUME_CHANGE = 4
    const val EVENT_PLAY_TRANSITION = 5
    external fun pushTelemetryEvent(type: Int, trackId: Long, value: Float)

    external fun getMarkovProbability(fromTrackId: Int, toTrackId: Int): Float
    external fun get2ndOrderMarkovProbability(trackA: Int, trackB: Int, trackC: Int, alpha: Float = 0.1f): Float
    external fun getSatiationPenalty(trackId: Int): Float

    external fun processLufsNormalizerFloats(buffer: FloatArray, length: Int, targetLufs: Float)
    external fun processLufsNormalizerShorts(buffer: ShortArray, length: Int, targetLufs: Float)
    external fun getDynamicTargetLufs(): Float

    external fun generateProofOfCompute(buffer: FloatArray, length: Int, nonce: String): String

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

    external fun processPtpTimestamps(t0: Long, t1: Long, t2: Long, t3: Long): Long
    external fun getSynchronizedClockMs(): Long
    external fun getPtpClockOffsetNanos(): Long
    external fun getPtpRttNanos(): Long
    external fun resetPtpState()

    external fun getZhipuKey(index: Int): String
    external fun nukeLocalDatabase(): Boolean

    fun stepAirDropPhysics(stateArray: FloatArray, targetX: Float, targetY: Float, dt: Float) {
        require(stateArray.size >= 13) { "State vector must contain at least 13 floats" }
        val dx = targetX - stateArray[0]
        val dy = targetY - stateArray[1]
        val initialDist = kotlin.math.sqrt(dx * dx + dy * dy).coerceAtLeast(1.0f)
        stepAirDropPhysics(stateArray, targetX, targetY, initialDist, dt)
    }

    external fun stepAirDropPhysics(
        inOutBuffer: FloatArray,
        targetX: Float,
        targetY: Float,
        initialDist: Float,
        dt: Float
    )

    external fun analyzePcmAcousticDNA(
        directBuffer: java.nio.ByteBuffer,
        byteCount: Int,
        sampleRate: Int,
        channelCount: Int,
        outResults: FloatArray
    ): String

    external fun calculateLyricDrift(
        directPcmBuffer: java.nio.ByteBuffer,
        pcmByteCount: Int,
        textOnsetsMs: LongArray,
        onsetCount: Int,
        sampleRate: Int,
        channelCount: Int
    ): Int

    // ═══════════════════════════════════════════════════════════════
    // RUST CORE ENGINE (JNI / FFI)
    // ═══════════════════════════════════════════════════════════════
    external fun rustFuzzyRankCandidates(query: String, candidatesJson: String): String?
    external fun rustCalculateSimilarity(s1: String, s2: String): Float
    external fun rustParseYouTubePlaylist(jsonBytes: ByteArray): String?
    external fun rustComputeFftSpectrum(pcmFloats: FloatArray, barCount: Int, outBars: FloatArray): Int
    external fun rustProcessEqualizerFrame(pcmFloats: FloatArray, channels: Int, gains: FloatArray?): Int
    external fun rustDownloadStreamDirect(streamUrl: String, destPath: String): String?
    external fun rustScoreAndRankRadioCandidates(
        candidatesJson: String,
        seedBpm: Float,
        seedKey: String,
        seedDurSec: Int,
        seedSig: String,
        queueJson: String
    ): String?
    external fun rustProcessCrossfadePcm(
        outgoingBuf: FloatArray,
        incomingBuf: FloatArray,
        mixedBuf: FloatArray,
        progress: Float
    ): Int
    external fun rustEncryptVaultFile(srcPath: String, destPath: String, masterKey: ByteArray): Int
    external fun rustDecryptVaultFile(srcPath: String, destPath: String, masterKey: ByteArray): Int
    external fun rustParseBackupCsv(csvContent: String): String?
    external fun rustAlignAndCompileLyrics(rawLyrics: String, durationMs: Int, energyFloats: FloatArray?): String?
    external fun rustGenerateNeuroQueue(
        seedJson: String,
        candidatesJson: String,
        brainState: Int,
        nowSec: Long,
        hourOfDay: Int,
        targetCount: Int
    ): String?

    // ═══════════════════════════════════════════════════════════════
    // PHASE 7: BYZANTINE CONSENSUS & IEEE 1588 PTP JNI EXPORTS
    // ═══════════════════════════════════════════════════════════════

    fun verifyPeerConsensus(
        node1: String, lufs1: Float, key1: String, vec1: FloatArray, proof1: ByteArray,
        node2: String, lufs2: Float, key2: String, vec2: FloatArray, proof2: ByteArray
    ): Boolean {
        require(vec1.size == 128 && vec2.size == 128) { "Vectors must be 128-D" }
        return nativeVerifyPeerConsensus(
            node1, lufs1, key1, vec1, proof1,
            node2, lufs2, key2, vec2, proof2
        )
    }

    fun computePtpOffsetAndDelay(
        seqId: Int, t0: Long, t1: Long, t2: Long, t3: Long, outResultsUs: LongArray
    ) {
        require(outResultsUs.size >= 2) { "Output array must hold at least 2 elements [offset, delay]" }
        nativeCalculatePtp(seqId, t0, t1, t2, t3, outResultsUs)
    }

    private external fun nativeVerifyPeerConsensus(
        node1: String, lufs1: Float, key1: String, vec1: FloatArray, proof1: ByteArray,
        node2: String, lufs2: Float, key2: String, vec2: FloatArray, proof2: ByteArray
    ): Boolean

    private external fun nativeCalculatePtp(
        seqId: Int, t0: Long, t1: Long, t2: Long, t3: Long, outResults: LongArray
    )
}

