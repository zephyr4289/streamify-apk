package com.streamify.app.jam

import com.streamify.app.util.SLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * LeaderlessElectionEngine — Blake3 deterministic election FSM + Death Pivot
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * Replaces every PostgreSQL lease / RPC succession call with a fully
 * distributed, server-free authority hand-off:
 *
 *  A. HEARTBEAT LOSS DETECTION — if the active host fails to broadcast a
 *     tick for [ElectionConfig.heartbeatTimeoutNanos] (1 200 ms per the
 *     protocol spec), every guest independently enters [ElectionState.ELECTION].
 *
 *  B. DETERMINISTIC MINIMIZATION — the successor is
 *     `argmin Blake3(deviceId + sessionId)` over all live connected nodes.
 *     No votes, no terms, no split-brain ballot: every peer that observed the
 *     same live-set computes the identical winner in O(n) hashes. A peer that
 *     did NOT make the cut simply waits for the winner's CONTINUATION epoch;
 *     if the winner is itself dead (battery death cascade), its liveness goes
 *     stale, the next election round excludes it, and authority walks down
 *     the Blake3 ordering — a Bully protocol with a hash-ordered bully rank.
 *
 *  C. THE DEATH PIVOT — the successor inherits authority and extrapolates
 *     the playhead through the failure using the last verified monotonic
 *     tick:
 *
 *        ExtrapolatedPosition = LastHostPosition + (CurrentSyncedNanos
 *                                − LastHostNanos)
 *
 *     then immediately broadcasts the authoritative CONTINUATION epoch.
 *     Guests never paused, so there is nothing to resume: their PLL was
 *     already tracking the extrapolated trajectory and simply re-locks onto
 *     the successor's tick stream. Zero audio stutter by construction.
 *
 *  D. PARTITION HEAL — when two self-claimed leaders meet after a network
 *     merge, the higher Blake3 rank demotes itself to guest; the lower rank
 *     re-broadcasts its CONTINUATION. Determinism makes the outcome identical
 *     on both sides without another message round.
 *
 * The engine is a pure Kotlin state machine over an injected clock and
 * transport — coroutine virtual-time testable with [StandardTestDispatcher]
 * and fully exercisable without any native artifact.
 */
class LeaderlessElectionEngine(
    private val scope: CoroutineScope,
    val selfNonce: String,
    private val config: ElectionConfig = ElectionConfig(),
    /** Synced nanosecond room clock (PTP domain). */
    private val clock: () -> Long,
    /** Session key material for the Blake3 election rank (null = no room). */
    private val sessionId: () -> String?,
    /** Outgoing wire for CONTINUATION broadcasts. */
    private val transport: Transport,
    /** Last verified host tick — the Death Pivot extrapolation source. */
    private val tickSource: HostTickSource
) {

    /** Wire send surface implemented by JamEngine. */
    interface Transport {
        fun broadcastContinuation(body: JamWire.ContinuationBody): Boolean
    }

    /** Verified playback state of the dead host — supplied by JamEngine. */
    interface HostTickSource {
        data class Snapshot(
            val hostNonce: String,
            val positionMs: Long,
            val syncedNanos: Long,
            val durationMs: Long,
            val playing: Boolean
        )

        fun lastVerifiedTick(): Snapshot?
    }

    data class ElectionConfig(
        /** Host-tick silence that triggers ELECTION (spec: 1 200 ms). */
        val heartbeatTimeoutNanos: Long = 1_200_000_000L,
        /** Grace window for the elected successor's CONTINUATION frame. */
        val continuationGraceNanos: Long = 900_000_000L,
        /** Peer liveness horizon — presence/tick freshness. */
        val peerLivenessNanos: Long = 3_600_000_000L,
        /** Watchdog cadence. */
        val pollMillis: Long = 150L,
        /** Rounds without a CONTINUATION before the presumed winner is dropped. */
        val maxRoundsWithoutContinuation: Int = 3
    )

    enum class ElectionState {
        /** No room / engine idle. */
        BOOTSTRAP,

        /** Tracking an active leader's heartbeat. */
        FOLLOWER,

        /** Leader failed; deterministic election in progress. */
        ELECTION,

        /** This device holds authority (initial or Death-Pivot successor). */
        LEADER
    }

    sealed class ElectionEvent {
        data class ElectionStarted(val reason: String) : ElectionEvent()

        data class LeaderElected(
            val leaderNonce: String,
            val epoch: Long,
            val viaDeathPivot: Boolean
        ) : ElectionEvent()

        data class DeathPivotExecuted(
            val successorNonce: String,
            val extrapolatedPosMs: Long,
            val pivotNanos: Long,
            val newEpoch: Long,
            val beyondTrackEnd: Boolean
        ) : ElectionEvent()

        data class Demoted(val newLeaderNonce: String, val epoch: Long) : ElectionEvent()
    }

    // ── Observable state ───────────────────────────────────────────────────

    private val _state = MutableStateFlow(ElectionState.BOOTSTRAP)
    val state: StateFlow<ElectionState> = _state.asStateFlow()

    private val _leaderNonce = MutableStateFlow<String?>(null)
    val leaderNonce: StateFlow<String?> = _leaderNonce.asStateFlow()

    private val _authorityEpoch = MutableStateFlow(0L)
    val authorityEpoch: StateFlow<Long> = _authorityEpoch.asStateFlow()

    private val _events = MutableSharedFlow<ElectionEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<ElectionEvent> = _events

    val isLeader: Boolean get() = _state.value == ElectionState.LEADER

    // ── Peer liveness table ────────────────────────────────────────────────

    private data class PeerRecord(
        @Volatile var lastSeenNanos: Long,
        @Volatile var lastHostTickNanos: Long
    )

    private val peers = ConcurrentHashMap<String, PeerRecord>()
    private var watchdog: Job? = null

    @Volatile private var lastLeaderTickNanos: Long = Long.MIN_VALUE
    @Volatile private var roundsWithoutContinuation = 0
    @Volatile private var electionRoundStartNanos: Long = 0L
    @Volatile private var awaitedSuccessor: String? = null

    // ── Lifecycle ──────────────────────────────────────────────────────────

    fun start() {
        if (watchdog?.isActive == true) return
        watchdog = scope.launch {
            while (isActive) {
                delay(config.pollMillis)
                runCatching { evaluate() }.onFailure {
                    SLog.w(TAG, "watchdog iteration failed", it)
                }
            }
        }
    }

    fun stop() {
        watchdog?.cancel()
        watchdog = null
    }

    /** Full reset between rooms (engine is long-lived across sessions). */
    fun reset(initialState: ElectionState = ElectionState.BOOTSTRAP) {
        peers.clear()
        _state.value = initialState
        _leaderNonce.value = null
        _authorityEpoch.value = 0L
        lastLeaderTickNanos = Long.MIN_VALUE
        roundsWithoutContinuation = 0
        electionRoundStartNanos = 0L
        awaitedSuccessor = null
    }

    /** Room creation path: this device becomes the founding leader, epoch 1. */
    fun becomeFoundingLeader() {
        _authorityEpoch.value = 1L
        _leaderNonce.value = selfNonce
        _state.value = ElectionState.LEADER
        noteLeaderTickInternal(selfNonce, 0L, clock(), 0L, true)
        _events.tryEmit(ElectionEvent.LeaderElected(selfNonce, 1L, viaDeathPivot = false))
        SLog.i(TAG, "founding leader, epoch=1")
    }

    // ── Inputs (fed by the JamEngine frame dispatcher) ─────────────────────

    /** Any inbound frame from [nonce] refreshes that peer's liveness. */
    fun notePeerSeen(nonce: String) {
        if (nonce.isBlank() || nonce == selfNonce) return
        val now = clock()
        var isNew = false
        peers.compute(nonce) { _, r ->
            if (r != null) {
                r.lastSeenNanos = now
                r
            } else {
                isNew = true
                PeerRecord(now, Long.MIN_VALUE)
            }
        }
        // Fresh joiner grace: give an unseen leader one heartbeat window to
        // announce itself before heartbeat-loss can fire (also revives a
        // dead-room join into an election instead of waiting forever).
        if (isNew && lastLeaderTickNanos == Long.MIN_VALUE) lastLeaderTickNanos = now
    }

    /** Authoritative host TICK observed from the current leader. */
    fun noteLeaderTick(
        nonce: String,
        positionMs: Long,
        syncedNanos: Long,
        durationMs: Long,
        playing: Boolean
    ) {
        noteLeaderTickInternal(nonce, positionMs, syncedNanos, durationMs, playing)
        // A leader's live tick while we're mid-election means the "dead" host
        // came back (transient Wi-Fi flap): stand down immediately.
        if (_state.value == ElectionState.ELECTION && _leaderNonce.value == nonce) {
            roundsWithoutContinuation = 0
            _state.value = ElectionState.FOLLOWER
            SLog.i(TAG, "election stood down — leader $nonce resumed ticking")
        }
    }

    private fun noteLeaderTickInternal(
        nonce: String,
        positionMs: Long,
        syncedNanos: Long,
        durationMs: Long,
        playing: Boolean
    ) {
        // Verified-tick bookkeeping is owned by the tickSource (JamEngine);
        // here we only track WHO leads and WHEN we last heard them.
        if (_state.value == ElectionState.LEADER && nonce != selfNonce) {
            // A foreign leader's tick with our own authority is a partition
            // symptom; resolve by rank (section D).
            resolveCompetingLeader(nonce)
            return
        }
        _leaderNonce.value = nonce
        lastLeaderTickNanos = clock()
        // A leader's OWN tick (the local tick loop) must never demote it;
        // only a foreign tick while we hold no authority demotes us.
        if (_state.value != ElectionState.ELECTION && nonce != selfNonce) {
            _state.value = ElectionState.FOLLOWER
        }
        peers.compute(nonce) { _, r ->
            r?.also { it.lastSeenNanos = clock(); it.lastHostTickNanos = clock() }
                ?: PeerRecord(clock(), clock())
        }
    }

    /** Mesh-level peer departure (battery death, Wi-Fi loss). */
    fun notePeerLeft(nonce: String) {
        peers.remove(nonce)
        if (_leaderNonce.value == nonce) {
            // Don't wait out the full heartbeat timeout for a confirmed death.
            lastLeaderTickNanos = Long.MIN_VALUE
        }
    }

    /**
     * CONTINUATION epoch from a successor. Epoch-gated: stale/replayed
     * hand-offs are ignored, exactly like the legacy HOST_TAKEOVER contract.
     */
    fun noteContinuation(body: JamWire.ContinuationBody) {
        notePeerSeen(body.successorNonce)
        if (body.newEpoch <= _authorityEpoch.value) return

        val wasLeader = _state.value == ElectionState.LEADER
        _authorityEpoch.value = body.newEpoch
        _leaderNonce.value = body.successorNonce
        lastLeaderTickNanos = clock()
        roundsWithoutContinuation = 0
        _state.value = ElectionState.FOLLOWER
        _events.tryEmit(
            if (wasLeader) ElectionEvent.Demoted(body.successorNonce, body.newEpoch)
            else ElectionEvent.LeaderElected(body.successorNonce, body.newEpoch, viaDeathPivot = true)
        )
        SLog.i(
            TAG,
            "continuation epoch=${body.newEpoch} leader=${body.successorNonce} " +
                "pivot=${body.pivotPosMs}ms (was ${if (wasLeader) "LEADER" else "guest"})"
        )
    }

    // ── Deterministic election core ────────────────────────────────────────

    /**
     * The election rank: `Blake3(deviceId + sessionId)` as unsigned bytes.
     * Identical computation on every peer — that is the whole consensus.
     */
    fun electionRank(deviceNonce: String): ByteArray? {
        val sid = sessionId() ?: return null
        return Blake3.hash("$deviceNonce$sid")
    }

    /**
     * Deterministic minimization over the live node set (self included).
     * Returns the winning nonce, or null when the session is unknown.
     */
    fun electDeterministically(liveNonces: Collection<String>): String? {
        val sid = sessionId() ?: return null
        var best: String? = null
        var bestRank: ByteArray? = null
        for (candidate in liveNonces + selfNonce) {
            if (candidate.isBlank()) continue
            val rank = Blake3.hash("$candidate$sid")
            if (bestRank == null || Blake3.compareUnsigned(rank, bestRank) < 0) {
                bestRank = rank
                best = candidate
            }
        }
        return best
    }

    /** Live = seen within [ElectionConfig.peerLivenessNanos]. */
    private fun livePeers(): List<String> {
        val now = clock()
        return peers.entries.mapNotNull { (nonce, rec) ->
            if (now - rec.lastSeenNanos <= config.peerLivenessNanos) nonce else null
        }
    }

    private fun evaluate() {
        when (_state.value) {
            ElectionState.BOOTSTRAP, ElectionState.LEADER -> {
                // LEADER liveness is maintained by JamEngine's own tick loop;
                // BOOTSTRAP has nothing to watch yet.
            }
            ElectionState.FOLLOWER -> {
                val last = lastLeaderTickNanos
                if (last == Long.MIN_VALUE) {
                    if (_leaderNonce.value != null) {
                        // Leader departure was mesh-confirmed (notePeerLeft
                        // cleared the tick horizon): skip the heartbeat wait.
                        enterElection("leader departed (mesh-confirmed)")
                    }
                    // else: never had a leader — nothing to lose yet.
                } else if (clock() - last > config.heartbeatTimeoutNanos) {
                    enterElection("host tick silent > ${config.heartbeatTimeoutNanos / 1_000_000} ms")
                }
            }
            ElectionState.ELECTION -> {
                val winner = electDeterministically(livePeers())
                if (winner == null) return
                if (winner == selfNonce) {
                    executeDeathPivot()
                } else {
                    // Await the winner's CONTINUATION. Liveness is proven only
                    // by an actual tick/continuation frame (noteLeaderTick's
                    // stand-down resolves flaps); a stale rank can never stand
                    // an election down by itself. If the winner is dead its
                    // grace round expires and authority walks down the Blake3
                    // ordering to the next live node.
                    if (awaitedSuccessor != winner) {
                        awaitedSuccessor = winner
                        electionRoundStartNanos = clock()
                    }
                    val winnerSeen = peers[winner]?.lastSeenNanos ?: Long.MIN_VALUE
                    val roundExpired = clock() - electionRoundStartNanos > config.continuationGraceNanos
                    val winnerFresh = clock() - winnerSeen <= config.peerLivenessNanos
                    if (roundExpired || !winnerFresh) {
                        roundsWithoutContinuation++
                        SLog.w(
                            TAG,
                            "elected $winner sent no continuation (round " +
                                "$roundsWithoutContinuation/${config.maxRoundsWithoutContinuation})"
                        )
                        peers.remove(winner) // presumed dead: cascade to next rank
                        awaitedSuccessor = null
                        electionRoundStartNanos = clock()
                        if (roundsWithoutContinuation >= config.maxRoundsWithoutContinuation) {
                            roundsWithoutContinuation = 0
                        }
                    }
                }
            }
        }
    }

    private fun enterElection(reason: String) {
        _state.value = ElectionState.ELECTION
        roundsWithoutContinuation = 0
        electionRoundStartNanos = clock()
        awaitedSuccessor = null
        _events.tryEmit(ElectionEvent.ElectionStarted(reason))
        SLog.w(TAG, "ELECTION entered: $reason")
        evaluate() // deterministic: the outcome may be instant
    }

    /**
     * THE DEATH PIVOT. The successor inherits authority, extrapolates the
     * playhead through the failure window, and broadcasts the continuation
     * epoch. Audio never pauses on any surviving device.
     */
    private fun executeDeathPivot() {
        val nowNanos = clock()
        val last = tickSource.lastVerifiedTick()
        val newEpoch = _authorityEpoch.value + 1

        var extrapolatedPosMs = last?.positionMs ?: 0L
        var beyondEnd = false
        if (last != null) {
            extrapolatedPosMs = extrapolatePositionMs(
                lastHostPositionMs = last.positionMs,
                lastHostNanos = last.syncedNanos,
                currentSyncedNanos = nowNanos
            )
            if (last.durationMs > 0 && extrapolatedPosMs >= last.durationMs) {
                beyondEnd = true
                extrapolatedPosMs = 0L
            }
        }

        _authorityEpoch.value = newEpoch
        _leaderNonce.value = selfNonce
        _state.value = ElectionState.LEADER
        lastLeaderTickNanos = nowNanos

        val sent = transport.broadcastContinuation(
            JamWire.ContinuationBody(
                newEpoch = newEpoch,
                successorNonce = selfNonce,
                pivotPosMs = extrapolatedPosMs,
                pivotSyncedNanos = nowNanos,
                durationMs = last?.durationMs ?: 0L,
                playing = last?.playing ?: true,
                trackMatches = !beyondEnd
            )
        )

        _events.tryEmit(
            ElectionEvent.LeaderElected(selfNonce, newEpoch, viaDeathPivot = true)
        )
        _events.tryEmit(
            ElectionEvent.DeathPivotExecuted(
                successorNonce = selfNonce,
                extrapolatedPosMs = extrapolatedPosMs,
                pivotNanos = nowNanos,
                newEpoch = newEpoch,
                beyondTrackEnd = beyondEnd
            )
        )
        SLog.i(
            TAG,
            "DEATH PIVOT executed: epoch=$newEpoch pivot=${extrapolatedPosMs}ms " +
                "beyondEnd=$beyondEnd broadcast=${if (sent) "ok" else "queued"}"
        )
    }

    /** Spec formula: LastHostPosition + (CurrentSyncedNanos − LastHostNanos). */
    private fun extrapolatePositionMs(
        lastHostPositionMs: Long,
        lastHostNanos: Long,
        currentSyncedNanos: Long
    ): Long {
        if (lastHostNanos <= 0L) return lastHostPositionMs
        val elapsedNanos = currentSyncedNanos - lastHostNanos
        if (elapsedNanos <= 0L) return lastHostPositionMs
        // Anti-replay sanity: a failure window longer than 10 minutes means
        // the "last verified tick" predates any plausible session continuity.
        val elapsedMs = (elapsedNanos / 1_000_000L).coerceIn(0L, 600_000L)
        return lastHostPositionMs + elapsedMs
    }

    /**
     * Partition heal (section D): a foreign leader is broadcasting. Whoever
     * holds the HIGHER Blake3 rank demotes; the lower rank stands. Both
     * sides compute the same answer from the two nonces.
     */
    private fun resolveCompetingLeader(foreignNonce: String) {
        val myRank = electionRank(selfNonce) ?: return
        val theirRank = electionRank(foreignNonce) ?: return
        if (Blake3.compareUnsigned(myRank, theirRank) > 0) {
            // We lose: drop authority. The foreign leader's next CONTINUATION
            // epoch will formalize the takeover on our side.
            _state.value = ElectionState.FOLLOWER
            _leaderNonce.value = foreignNonce
            SLog.w(TAG, "partition heal: demoting to $foreignNonce (Blake3 rank)")
        } else {
            // We win: ignore their ticks; they will demote on seeing ours.
            SLog.i(TAG, "partition heal: retaining authority over $foreignNonce")
        }
    }

    private companion object {
        const val TAG = "JamElection"
    }
}
