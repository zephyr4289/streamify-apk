package com.streamify.app.jam

import com.streamify.app.data.models.Track
import com.streamify.app.data.remote.ListeningSession
import com.streamify.app.data.remote.SupabaseClient
import com.streamify.app.data.remote.jamTrackFromJson
import com.streamify.app.data.remote.jamTrackToJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * JAM LOCKSTEP ENGINE v2 — Single-writer playback authority
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * Protocol invariants (Spotify-Jam-grade guarantees):
 *
 *  1. HOST AUTHORITY — only the host emits TICK / control intents that mutate
 *     room playback state. Guests emit REQUESTS; the host ratifies them by
 *     executing locally, which re-broadcasts an authoritative intent.
 *  2. EPOCH ORDERING — every host intent carries a monotonically increasing
 *     epoch. Receivers drop anything older than the newest regime they applied,
 *     making out-of-order/duplicate/replayed packets harmless.
 *  3. SENDER IDENTITY — every packet carries sender_id (a per-device nonce, so
 *     two devices on one account still work). Receivers ignore their own echoes
 *     and enforce policy on who may control playback.
 *  4. JOIN/RECONNECT HANDSHAKE — authoritative state is fetched from the DB row
 *     (position extrapolated through host_clock_timestamp), never guessed.
 *  5. LOSSLESS IDENTITY — track payloads carry ytmVideoId/isrc so guests pin
 *     the exact same upload; zero fuzzy resolution inside a Jam.
 *  6. SHARED QUEUE PERSISTENCE — queue_json is canonical on the host; every
 *     mutation is persisted (PATCH) and echoed; late joiners hydrate from row.
 *  7. HOST-DRIVEN AUTO-ADVANCE — when a track ends, ONLY the host advances the
 *     room (popping the shared queue head); guests deliberately idle awaiting
 *     the TRACK_CHANGE instead of derailing into personal radio.
 */
object JamEngine {

    // ═══════════════ Types ═══════════════

    data class Member(
        val userId: String,
        val name: String,
        val avatarUrl: String?,
        val isHost: Boolean,
        val lastSeenMs: Long
    )

    enum class ConnStatus { LIVE, DEGRADED, OFFLINE }
    enum class ControlPolicy { HOST_ONLY, EVERYONE }

    /** Decisions made by the protocol brain; executed by the player owner. */
    sealed class Command {
        data class ApplyTrack(val track: Track, val positionMs: Long, val play: Boolean) : Command()
        data class ApplySeek(val positionMs: Long) : Command()
        data class ApplyPlayPause(val play: Boolean) : Command()
        data class ApplyPllTick(
            val hostPositionMs: Long,
            val hostEpochMs: Long,
            val durationMs: Long,
            val play: Boolean
        ) : Command()
        object SessionEnded : Command()

        /** PHASE 4: engine-detected demotion/partition → VM re-runs handshake. */
        object Rehandshake : Command()
    }

    /** Live-player facade attached by the app shell. */
    interface Bridge {
        fun loadTrack(track: Track, positionMs: Long, play: Boolean)
        fun setPlaying(play: Boolean)
    }

    // ═══════════════ State ═══════════════

    private val _members = MutableStateFlow<List<Member>>(emptyList())
    val members: StateFlow<List<Member>> = _members.asStateFlow()

    private val _connStatus = MutableStateFlow(ConnStatus.OFFLINE)
    val connStatus: StateFlow<ConnStatus> = _connStatus.asStateFlow()

    private val _policy = MutableStateFlow(ControlPolicy.EVERYONE)
    val policy: StateFlow<ControlPolicy> = _policy.asStateFlow()

    private val _queue = MutableStateFlow<List<Track>>(emptyList())
    val queue: StateFlow<List<Track>> = _queue.asStateFlow()

    private val _commands = MutableSharedFlow<Command>(extraBufferCapacity = 64)
    val commands: SharedFlow<Command> = _commands

    /** trackId -> "Added by X" attribution, learned from queue wire payloads. */
    private val addedByMap = ConcurrentHashMap<String, String>()
    fun addedBy(trackId: Int): String? = addedByMap[trackId.toString()]

    /** Deep-link invite consumed by the Jam screen on open. */
    @Volatile var pendingInviteCode: String? = null

    /** Non-suspend signal so the shell can auto-navigate into the room. */
    val inviteNavigationEvents = MutableSharedFlow<String>(extraBufferCapacity = 4)

    /** Per-process device nonce: two devices signed into one account stay distinct. */
    val deviceId: String = UUID.randomUUID().toString().take(8)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val epochCounter = AtomicLong(0)

    // ── Phase 1 sync-core state ──────────────────────────────────────────

    /** Host-only: monotonic wire sequence for lossless tick ordering. */
    private val tickSeqCounter = AtomicLong(0)

    /** Synced-clock stamp of the last playback regime change (play/pause/seek/track). */
    @Volatile private var lastRegimeChangeSyncedMs = 0L

    /** Guest-side: track announced by host NEXT_IS, awaiting zero-gap handoff. */
    @Volatile var pendingNextIsTrack: Track? = null
        private set

    /** Guest-side: last SYNC_REQ fire time (synced domain) — retry pacing. */
    @Volatile private var lastSyncReqAtMs = 0L

    /** Host-side: identity of the queue head already announced via NEXT_IS. */
    @Volatile var announcedNextId: String? = null

    // ── Phase 2: CRDT + outbox state ─────────────────────────────────────

    /**
     * Element identity index: cad_id -> add_op_id. Populated on every Add we
     * generate or accept, so Remove/Reorder ops can target exact elements
     * instead of matching title+artist strings (P6).
     */
    private val elementIndex = ConcurrentHashMap<Long, Long>()

    /** cad_id -> Track object cache for rebuilding the UI view post-fold. */
    private val cadTrackCache = ConcurrentHashMap<Long, Track>()

    /** cad_id -> live fractional index from the latest authoritative fold. */
    private val fracByCad = ConcurrentHashMap<Long, Double>()

    @Volatile var outboxReady: Boolean = false
        private set

    // ── Phase 3: lease & succession state ────────────────────────────────

    /** Authoritative fencing token from the server (adopted verbatim). */
    @Volatile var currentEpochFence: Long = 0L
        private set

    /** Set when this device lost authority to a successor. */
    @Volatile private var demotedAtMs: Long = 0L

    /**
     * Marks a HOST_TAKEOVER: adopts the server-issued fencing epoch, resets
     * the PLL + tick matrix (new host ⇒ new seq stream), and marks regime
     * change so the adaptive cadence converges fast.
     */
    fun adoptTakeover(newEpoch: Long) {
        if (newEpoch > 0) {
            if (newEpoch > epochCounter.get()) epochCounter.set(newEpoch)
            currentEpochFence = newEpoch
        }
        latestAppliedEpoch = Long.MIN_VALUE
        // PHASE 4: proper native wipe — without it a new host's seq=1 is
        // misread as a 4-billion wrap and every tick is silently dropped.
        com.streamify.app.data.NativeBridge.jamTickMatrixReset()
        com.streamify.app.data.NativeBridge.kalmanPllReset()
        tickSeqCounter.set(0) // our own outgoing stream restarts cleanly too
        markRegimeChange()
        lastRegimeChangeSyncedMs = nowSynced()
        SLog.i("JamLease", "takeover adopted, fence=$newEpoch")
    }

    /** Host self-demotes after the server fenced it out. */
    fun selfDemote(newHostId: String?) {
        if (demotedAtMs != 0L) return
        demotedAtMs = System.currentTimeMillis()
        SLog.w("JamLease", "self-demoted → ${newHostId ?: "successor"}")
        val s = activeSession() ?: return
        SupabaseClient.adoptForeignHost(s.sessionCode, newHostId)
        markRegimeChange()
    }

    fun clearDemotion() {
        demotedAtMs = 0L
    }


    val isDemoted: Boolean get() = demotedAtMs != 0L

    val senderPacked: Long by lazy {
        // Stable per-process 4-byte device identity for op envelopes.
        try {
            java.nio.ByteBuffer.wrap(
                java.security.MessageDigest.getInstance("MD5")
                    .digest(deviceId.toByteArray())
            ).long // first 8 bytes as long; low 4 bytes are the nonce half
        } catch (_: Throwable) {
            deviceId.hashCode().toLong()
        }
    }

    companion object OpCodes {
        const val OP_ADD: Int = 1
        const val OP_REMOVE: Int = 2
        const val OP_REORDER: Int = 3
    }

    private fun openOutbox() {
        val ctx = com.streamify.app.data.TrackRepository.appContext ?: return
        outboxReady = try {
            val db = java.io.File(ctx.filesDir, "jam_outbox.db")
            com.streamify.app.data.NativeBridge.jamOutboxOpen(db.absolutePath)
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * THE time source for all Jam math. On hosts / pre-handshake guests this is
     * the raw monotonic clock; on synced guests it maps into the host timeline,
     * making OS NTP skew irrelevant (P1 fix).
     */
    fun nowSynced(): Long = com.streamify.app.data.NativeBridge.getSyncedJamMonotonicMs()

    /**
     * Adaptive host tick interval (P2): steady 1000ms, 250ms convergence burst
     * for 2s after any regime change, 50ms during the final 15s of a track so
     * every device lands the transition frame-perfectly.
     */
    fun tickIntervalMs(positionMs: Long, durationMs: Long): Long {
        if (durationMs > 0 && positionMs >= 0 && durationMs - positionMs <= 15_000) return 50L
        if (lastRegimeChangeSyncedMs > 0 && nowSynced() - lastRegimeChangeSyncedMs < 2_000) return 250L
        return 1_000L
    }

    private fun markRegimeChange() {
        lastRegimeChangeSyncedMs = nowSynced()
        com.streamify.app.data.NativeBridge.kalmanPllReset()
    }

    /** device nonce -> userId, learned from PRESENCE; drives host-authority checks. */
    private val deviceUserMap = ConcurrentHashMap<String, String>()

    @Volatile private var latestAppliedEpoch = Long.MIN_VALUE
    @Volatile private var lastHostTickAt = 0L
    @Volatile private var sessionEndedLocally = false
    private var sweeperJob: Job? = null

    // ═══════════════ Role & session helpers ═══════════════

    fun myUserId(): String = SupabaseClient.currentUser.value?.id ?: "anonymous"
    fun activeSession(): ListeningSession? = SupabaseClient.activeJam.value
    fun isActive(): Boolean = activeSession() != null && !sessionEndedLocally
    fun isHost(): Boolean {
        if (isDemoted) return false
        val s = activeSession() ?: return false
        return s.hostUserId == myUserId()
    }

    fun setPolicy(next: ControlPolicy) {
        _policy.value = next
        val s = activeSession() ?: return
        if (isHost()) {
            SupabaseClient.broadcastJamTick(
                sessionCode = s.sessionCode, trackId = "", trackTitle = "", trackArtist = "",
                positionMs = 0L, isPlaying = false, action = "POLICY",
                senderId = deviceId, epochMs = currentEpoch(),
                extras = JSONObject().put("policy", next.name)
            )
        }
    }

    private fun currentEpoch(): Long = epochCounter.get()

    // ═══════════════ Outgoing ═══════════════

    /**
     * Local playback intent. Hosts broadcast authoritative epochs; members under
     * EVERYONE policy also broadcast intents (ratified receiver-side by role),
     * members under HOST_ONLY are downgraded to no-op requests they can apply
     * to themselves only — the room follows the host regardless.
     */
    fun onLocalPlaybackAction(action: String, track: Track?, positionMs: Long, isPlaying: Boolean) {
        val s = activeSession() ?: return
        val effectiveAction = if (!isHost() && _policy.value == ControlPolicy.HOST_ONLY &&
            action != "TRACK_CHANGE" && action != "TICK") {
            action // local-only: receivers will reject; sender stays consistent locally
        } else action

        val epoch = if (isHost() && action != "TICK") epochCounter.incrementAndGet() else currentEpoch()
        SupabaseClient.broadcastJamTick(
            sessionCode = s.sessionCode,
            trackId = track?.id?.toString() ?: "",
            trackTitle = track?.title ?: "",
            trackArtist = track?.artist ?: "",
            positionMs = positionMs,
            isPlaying = isPlaying,
            action = effectiveAction,
            trackJson = track?.let { jamTrackToJson(it) },
            senderId = deviceId,
            epochMs = epoch
        )
    }

    /** High-resolution position heartbeat — HOST ONLY by protocol contract. */
    fun heartbeatTick(track: Track?, positionMs: Long, isPlaying: Boolean) {
        if (!isActive() || !isHost()) return
        lastHostTickAt = System.currentTimeMillis()
        val s = activeSession() ?: return
        val hostMonoMs = nowSynced()
        SupabaseClient.broadcastJamTick(
            sessionCode = s.sessionCode,
            trackId = track?.id?.toString() ?: "",
            trackTitle = track?.title ?: "",
            trackArtist = track?.artist ?: "",
            positionMs = positionMs,
            isPlaying = isPlaying,
            action = "TICK",
            trackJson = track?.let { jamTrackToJson(it) },
            senderId = deviceId,
            epochMs = currentEpoch(),
            extras = JSONObject()
                .put("seq", tickSeqCounter.incrementAndGet())
                .put("host_mono", hostMonoMs)
                .put("duration_ms", track?.durationSec?.toLong()?.times(1000L) ?: 0L)
        )
    }

    /**
     * P3: predictive JIT pre-hydration. Host announces the upcoming queue head
     * ~30s before the current track ends so every guest resolves + pre-buffers
     * it into SimpleCache BEFORE TRACK_CHANGE lands.
     */
    fun announceNextIs(nextTrack: Track?) {
        if (!isActive() || !isHost()) return
        val s = activeSession() ?: return
        if (nextTrack == null) {
            SupabaseClient.broadcastJamTick(
                sessionCode = s.sessionCode, trackId = "", trackTitle = "", trackArtist = "",
                positionMs = 0L, isPlaying = false, action = "NEXT_IS",
                senderId = deviceId, epochMs = currentEpoch(),
                extras = JSONObject().put("next_is_null", true)
            )
            return
        }
        SupabaseClient.broadcastJamTick(
            sessionCode = s.sessionCode,
            trackId = nextTrack.id.toString(),
            trackTitle = nextTrack.title,
            trackArtist = nextTrack.artist,
            positionMs = 0L, isPlaying = false, action = "NEXT_IS",
            trackJson = jamTrackToJson(nextTrack),
            senderId = deviceId, epochMs = currentEpoch()
        )
    }

    /**
     * P1 bootstrap: guest fires a Cristian handshake probe. Host stamps t1/t2
     * on receipt; guest fuses t0..t3 through the native best-sample filter.
     */
    fun fireClockSyncProbe() {
        if (!isActive()) return
        val s = activeSession() ?: return
        if (isHost()) return
        lastSyncReqAtMs = nowSynced()
        SupabaseClient.broadcastJamTick(
            sessionCode = s.sessionCode, trackId = "", trackTitle = "", trackArtist = "",
            positionMs = 0L, isPlaying = false, action = "SYNC_REQ",
            senderId = deviceId,
            // t0 MUST be raw local monotonic — synced values double-count theta.
            extras = JSONObject().put("t0", com.streamify.app.data.NativeBridge.getLocalMonotonicMs())
        )
    }

    fun clockSyncProbeDue(): Boolean =
        isActive() && !isHost() &&
            (lastSyncReqAtMs == 0L || nowSynced() - lastSyncReqAtMs > 5_000L)

    /** True once the native Cristian filter has locked (>=3 good samples). */
    fun clockLocked(): Boolean = com.streamify.app.data.NativeBridge.getJamClockRttMs() >= 0L

    fun pulsePresence(name: String, avatarUrl: String?) {
        val s = activeSession() ?: return
        noteMember(myUserId(), name, avatarUrl, isHost())
        SupabaseClient.broadcastJamTick(
            sessionCode = s.sessionCode,
            trackId = "", trackTitle = "", trackArtist = "",
            positionMs = 0L, isPlaying = false,
            action = "PRESENCE",
            senderId = deviceId,
            extras = JSONObject()
                .put("p_user", myUserId())
                .put("p_name", name)
                .put("p_avatar", avatarUrl ?: "")
                .put("p_host", isHost())
        )
    }

    // ═══════════════ Shared queue operations ═══════════════

    // ── Phase 2: local-first CRDT mutations (P5/P6) ──────────────────────

    private fun cadFor(track: Track): Long =
        com.streamify.app.data.NativeBridge.jamCanonicalCadId(
            track.title, track.artist, track.durationSec
        )

    /** Seals, applies, persists, and broadcasts one mutation op. */
    private fun mutate(
        type: Int,
        track: Track,
        fracIndexProvider: () -> Double,
        targetAddOpId: Long = 0L
    ): Boolean {
        if (!isActive()) return false
        val nb = com.streamify.app.data.NativeBridge
        val session = activeSession() ?: return false

        val cadId = when (type) {
            OP_ADD -> cadFor(track)
            else -> cadFor(track) // echo for UI cache; engine uses element id
        }
        if (cadId == 0L && type == OP_ADD) return false

        val opId = nb.jamGenerateOpId()
        val frac = fracIndexProvider()
        val applied = nb.jamCrdtApplyLocalOp(
            opId, senderPacked, type, if (_policy.value == ControlPolicy.EVERYONE) 1 else 0,
            cadId, frac, targetAddOpId
        )
        if (!applied) return false

        if (type == OP_ADD) {
            elementIndex[cadId] = opId
            cadTrackCache[cadId] = track
        } else if (targetAddOpId != 0L) {
            // Tombstoned element — drop identity mappings.
            elementIndex.entries.removeIf { it.value == targetAddOpId }
        }

        // Local-first persist (P5): survives partitions & process death.
        nb.jamOutboxEnqueue(opId, senderPacked, type, 0, cadId, frac, targetAddOpId, session.sessionCode)

        refreshQueueFromCrdt()
        broadcastOp(
            JamOpWire(opId, senderPacked, type, 0, cadId, frac, targetAddOpId),
            session.sessionCode
        )
        return true
    }

    fun addToQueue(track: Track, addedByName: String): Boolean {
        // Tail index = after(max known frac); empty queue -> FIRST_INDEX.
        val tailFrac = fracByCad.values.maxOrNull()
            ?.let { FractionalIndexEngine.after(it).value }
            ?: FractionalIndexEngine.FIRST_INDEX
        addedByMap[track.id.toString()] = "Added by $addedByName"
        val ok = mutate(OP_ADD, track, { tailFrac })
        if (!ok) {
            // Native layer unavailable → legacy best-effort path.
            _queue.update { current -> current + track }
            commitQueueIfHost()
            broadcastQueueOp("QUEUE_ADD", track)
        }
        return ok
    }

    fun removeFromQueue(track: Track): Boolean {
        val cad = cadFor(track)
        val target = elementIndex[cad]
            ?: _queue.value.size.let { 0L } // unknown element → legacy below
        val ok = target != 0L && mutate(OP_REMOVE, track, { 0.0 }, target)
        if (!ok) {
            _queue.update { current ->
                current.filterNot { it.id == track.id || (it.title == track.title && it.artist == track.artist) }
            }
            commitQueueIfHost()
            broadcastQueueOp("QUEUE_REMOVE", track)
        }
        return ok
    }

    /**
     * Rebuilds the UI queue from the authoritative CRDT fold, resolving cad
     * identities back to Track objects via the cache; falls back to the
     * existing in-memory list when the native layer is cold.
     */
    private fun refreshQueueFromCrdt() {
        val fold = com.streamify.app.data.NativeBridge.jamCrdtFold() ?: return
        val (triples, _) = fold
        if (triples.isEmpty()) return
        val rebuilt = ArrayList<Track>(triples.size / 3 + 1)
        var i = 0
        while (i + 2 < triples.size) {
            val cad = triples[i + 2]
            cadTrackCache[cad]?.let { rebuilt.add(it) }
            i += 3
        }
        i = 0
        while (i + 2 < triples.size) {
            fracByCad[triples[i + 2]] = Double.fromBits(triples[i])
            i += 3
        }
        if (rebuilt.isNotEmpty()) {
            _queue.value = rebuilt
            commitQueueIfHost()
        }
    }

    data class JamOpWire(
        val opId: Long, val sender: Long, val type: Int,
        val policy: Int, val cadId: Long, val frac: Double, val target: Long
    )

    private fun broadcastOp(op: JamOpWire, sessionCode: String) {
        SupabaseClient.broadcastJamTick(
            sessionCode = sessionCode, trackId = "", trackTitle = "", trackArtist = "",
            positionMs = 0L, isPlaying = false, action = "OP",
            senderId = deviceId, epochMs = currentEpoch(),
            extras = org.json.JSONObject()
                .put("o_id", op.opId)
                .put("o_sender", op.sender)
                .put("o_type", op.type)
                .put("o_policy", op.policy)
                .put("o_cad", op.cadId)
                .put("o_frac_bits", op.frac.toRawBits())
                .put("o_target", op.target)
        )
    }

    fun queueHead(): Track? = _queue.value.firstOrNull()

    /**
     * Called by the player before legacy auto-advance/skip logic.
     *  - Not in a Jam          -> false (legacy behavior)
     *  - Host                  -> pops shared queue head into playback, true
     *  - Guest                 -> true (deliberately idles awaiting host change;
     *                             personal radio hydration must NEVER hijack the room)
     */
    fun interceptAdvance(): Boolean {
        if (!isActive()) return false
        if (!isHost()) return true
        val head = _queue.value.firstOrNull() ?: return true // host keeps room alive via own flow
        _queue.update { it.drop(1) }
        commitQueueIfHost()
        bridge?.loadTrack(head, 0L, true)
        onLocalPlaybackAction("TRACK_CHANGE", head, 0L, true)
        return true
    }

    private fun commitQueueIfHost() {
        if (!isHost()) return
        val s = activeSession() ?: return
        val snapshot = _queue.value
        scope.launch(Dispatchers.IO) { SupabaseClient.patchJamQueueJson(s.sessionCode, snapshot) }
        broadcastQueueSnapshot(snapshot)
    }

    private fun broadcastQueueSnapshot(queue: List<Track>) {
        val s = activeSession() ?: return
        val arr = JSONArray()
        queue.forEach { t -> arr.put(jamTrackToJson(t)) }
        SupabaseClient.broadcastJamTick(
            sessionCode = s.sessionCode, trackId = "", trackTitle = "", trackArtist = "",
            positionMs = 0L, isPlaying = false, action = "QUEUE_SNAPSHOT",
            senderId = deviceId,
            extras = JSONObject().put("q_snapshot", arr)
        )
    }

    private fun broadcastQueueOp(op: String, track: Track) {
        val s = activeSession() ?: return
        SupabaseClient.broadcastJamTick(
            sessionCode = s.sessionCode, trackId = track.id.toString(),
            trackTitle = track.title, trackArtist = track.artist,
            positionMs = 0L, isPlaying = false, action = op,
            trackJson = jamTrackToJson(track),
            senderId = deviceId
        )
    }

    // ═══════════════ Incoming dispatcher ═══════════════

    fun onPayload(payload: JSONObject) {
        val sender = payload.optString("sender_id", "")
        if (sender.isBlank() || sender == deviceId) return // own echo / legacy packet

        val senderUserId = deviceUserMap[sender] ?: sender
        val senderIsHost = senderUserId == activeSession()?.hostUserId
        val action = payload.optString("action", "TICK")
        val epoch = payload.optLong("epoch", 0L)

        when (action) {
            "PRESENCE" -> {
                deviceUserMap[sender] = payload.optString("p_user", sender)
                noteMember(
                    userId = payload.optString("p_user", sender),
                    name = payload.optString("p_name", "Listener"),
                    avatarUrl = payload.optString("p_avatar", "").ifBlank { null },
                    isHostFlag = payload.optBoolean("p_host", false)
                )
                refreshConnStatus(true)
            }

            "LEAVE" -> {
                _members.update { list -> list.filterNot { it.userId == payload.optString("p_user", sender) } }
            }

            "SESSION_END" -> {
                if (senderIsHost || sender == activeSession()?.hostUserId) endLocally()
            }

            "POLICY" -> {
                if (senderIsHost) {
                    runCatching {
                        _policy.value = ControlPolicy.valueOf(payload.optString("policy", "EVERYONE"))
                    }
                }
            }

            // ── Phase 2: CRDT mutation op from a peer ──────────────────────
            "OP" -> {
                if (sender == deviceId) return
                val nb = com.streamify.app.data.NativeBridge
                val opId = payload.optLong("o_id", 0L)
                val oSender = payload.optLong("o_sender", 0L)
                val oType = payload.optInt("o_type", 0)
                val oPolicy = payload.optInt("o_policy", 0)
                val oCad = payload.optLong("o_cad", 0L)
                val oFrac = Double.fromBits(payload.optLong("o_frac_bits", 0L))
                val oTarget = payload.optLong("o_target", 0L)

                val applied = nb.jamCrdtApplyWireOp(
                    opId, oSender, oType, oPolicy, oCad, oFrac, oTarget,
                    checksum = -1L // wire JSON has no checksum field yet; native seals+validates structure
                )
                if (applied) {
                    if (oType == 1 && oCad != 0L) {
                        elementIndex[oCad] = opId
                        // Resolve the Track object from existing queue view if known.
                        _queue.value.firstOrNull {
                            com.streamify.app.data.NativeBridge.jamCanonicalCadId(it.title, it.artist, it.durationSec) == oCad
                        }?.let { cadTrackCache[oCad] = it }
                    }
                    refreshQueueFromCrdt()
                }
            }

            // ── Phase 3: authority transfer ─────────────────────────────────
            "HOST_TAKEOVER" -> {
                val newHost = payload.optString("t_host", "")
                val newEpoch = payload.optLong("t_epoch", 0L)
                val pivotPos = payload.optLong("t_pivot_pos", -1L)
                val code = activeSession()?.sessionCode ?: return

                // Epoch-gated: stale/replayed takeovers are ignored. Guests'
                // local mirrors still show the DEAD host here, so checking
                // senderIsHost would wrongly reject every legitimate claim.
                if (newEpoch > 0 && newEpoch > latestAppliedEpoch && !isHost()) {
                    SupabaseClient.adoptForeignHost(code, newHost.ifBlank { null })
                    // adoptTakeover resets the matrix so the NEW host's seq=1
                    // stream is accepted instead of dropped as replay.
                    adoptTakeover(newEpoch)
                    if (pivotPos >= 0) {
                        _commands.tryEmit(Command.ApplyPllTick(pivotPos, nowSynced(), 0L, true))
                    }
                    refreshConnStatus(true)
                }
            }

            // ── Phase 1: skew-free bootstrap handshake (P1) ────────────────
            "SYNC_REQ" -> {
                if (isHost()) {
                    val nowMono = nowSynced()
                    SupabaseClient.broadcastJamTick(
                        sessionCode = activeSession()?.sessionCode ?: return,
                        trackId = "", trackTitle = "", trackArtist = "",
                        positionMs = 0L, isPlaying = false, action = "SYNC_ACK",
                        senderId = deviceId,
                        extras = JSONObject()
                            .put("t0", payload.optLong("t0", 0L))
                            .put("t1", nowMono)
                            .put("t2", nowMono)
                            .put("target_sender", sender)
                    )
                }
            }

            "SYNC_ACK" -> {
                val target = payload.optString("target_sender", "")
                if (!isHost() && target == deviceId) {
                    val t0 = payload.optLong("t0", 0L)
                    val t1 = payload.optLong("t1", 0L)
                    val t2 = payload.optLong("t2", 0L)
                    val t3 = com.streamify.app.data.NativeBridge.getLocalMonotonicMs()
                    com.streamify.app.data.NativeBridge.jamClockApplySample(t0, t1, t2, t3)
                }
            }

            // ── Phase 1: predictive pre-hydration intent (P3) ───────────────
            "NEXT_IS" -> {
                if (!isHost() && senderIsHost) {
                    pendingNextIsTrack =
                        if (payload.optBoolean("next_is_null", false)) null
                        else jamTrackFromJson(payload.optJSONObject("track_json"))
                    pendingNextIsTrack?.let { onNextIsListener?.invoke(it) }
                }
            }

            "QUEUE_ADD" -> {
                val t = jamTrackFromJson(payload.optJSONObject("track_json")) ?: return
                payload.optJSONObject("track_json")?.optString("addedBy", "")?.let {
                    if (it.isNotBlank()) addedByMap[t.id.toString()] = it
                }
                _queue.update { cur -> cur + t }
                commitQueueIfHost()
            }

            "QUEUE_REMOVE" -> {
                val t = jamTrackFromJson(payload.optJSONObject("track_json")) ?: return
                _queue.update { cur ->
                    cur.filterNot { it.id == t.id || (it.title == t.title && it.artist == t.artist) }
                }
                commitQueueIfHost()
            }

            "QUEUE_SNAPSHOT" -> {
                val arr = payload.optJSONArray("q_snapshot") ?: return
                val incoming = (0 until arr.length()).mapNotNull { jamTrackFromJson(arr.optJSONObject(it)) }
                _queue.value = incoming
            }

            "REQUEST_SKIP" -> {
                if (isHost() && _policy.value == ControlPolicy.EVERYONE && !bridgeIsBusy()) {
                    bridge?.let { /* host executes its own skip path */ }
                }
            }

            else -> handleControlIntent(action, payload, sender, senderIsHost, epoch)
        }
    }

    private fun handleControlIntent(
        action: String,
        payload: JSONObject,
        sender: String,
        senderIsHost: Boolean,
        epoch: Long
    ) {
        // Authority gate: hosts always; others only under EVERYONE policy.
        if (!senderIsHost && _policy.value != ControlPolicy.EVERYONE) return

        // Epoch gate: never regress to an older playback regime.
        if (action != "TICK") {
            if (epoch in 1..latestAppliedEpoch) return
            latestAppliedEpoch = epoch
        } else if (epoch in 1 until latestAppliedEpoch) {
            return // tick from a superseded regime
        }

        val pos = payload.optLong("position_ms", 0L)
        val playing = payload.optBoolean("is_playing", false)

        when (action) {
            "TRACK_CHANGE" -> {
                val t = jamTrackFromJson(payload.optJSONObject("track_json"))
                    ?: return
                if (isHost()) return // host ignores foreign regimes outright
                latestAppliedEpoch = maxOf(latestAppliedEpoch, epoch)
                markRegimeChange()
                _commands.tryEmit(Command.ApplyTrack(t, pos, playing))
            }
            "SEEK" -> {
                if (isHost()) return
                markRegimeChange()
                _commands.tryEmit(Command.ApplySeek(pos))
            }
            "PLAY" -> {
                if (isHost()) return
                markRegimeChange()
                _commands.tryEmit(Command.ApplyPlayPause(true))
            }
            "PAUSE" -> {
                if (isHost()) return
                markRegimeChange()
                _commands.tryEmit(Command.ApplyPlayPause(false))
            }
            "TICK" -> {
                if (!senderIsHost) return // guests never drive the room clock
                lastHostTickAt = System.currentTimeMillis()
                refreshConnStatus(true)
                if (isHost()) return
                val dur = payload.optJSONObject("track_json")?.optInt("durationSec", 0)?.times(1000L)
                    ?: payload.optLong("duration_ms", 0L)

                // P2: lossless sequence matrix — synthesize gap-fills so the
                // PLL never mistakes a dropped packet for a stall.
                val seq = payload.optInt("seq", 0)
                val hostMono = payload.optLong("host_mono", 0L).takeIf { it > 0 } ?: nowSynced()
                val state = if (playing) 0 else 1 // 0=PLAYING 1=PAUSED (tick_matrix contract)
                val packedTicks = com.streamify.app.data.NativeBridge.jamTickIngest(
                    seq, pos, hostMono, state, if (_policy.value == ControlPolicy.EVERYONE) 1 else 0
                )
                for (packed in packedTicks) {
                    val sPos = packed and 0x7FFF_FFFFL
                    _commands.tryEmit(Command.ApplyPllTick(sPos, hostMono, dur, playing))
                }
            }
        }
    }

    private fun bridgeIsBusy(): Boolean = false // reserved: back-pressure hook

    // ═══════════════ Presence bookkeeping & connection status ═══════════════

    private fun noteMember(userId: String, name: String, avatarUrl: String?, isHostFlag: Boolean) {
        val now = System.currentTimeMillis()
        _members.update { list ->
            val existing = list.firstOrNull { it.userId == userId }
            val member = Member(
                userId = userId,
                name = name,
                avatarUrl = avatarUrl,
                isHost = isHostFlag || userId == activeSession()?.hostUserId,
                lastSeenMs = now
            )
            if (existing != null) list.map { if (it.userId == userId) member else it }
            else list + member
        }
    }

    /** Seeds self into roster + device map right after create/join. */
    fun noteSelf() {
        val me = SupabaseClient.currentUser.value
        deviceUserMap[deviceId] = myUserId()
        noteMember(myUserId(), me?.displayName ?: "You", me?.avatarUrl, isHost())
        refreshConnStatus(SupabaseClient.isRealtimeConnected.value)
    }

    fun startRuntime() {
        if (sweeperJob?.isActive == true) return
        sessionEndedLocally = false
        com.streamify.app.data.NativeBridge.jamClockReset()
        com.streamify.app.data.NativeBridge.kalmanPllReset()
        com.streamify.app.data.NativeBridge.jamCrdtReset()
        elementIndex.clear()
        cadTrackCache.clear()
        openOutbox()
        tickSeqCounter.set(0)
        lastRegimeChangeSyncedMs = nowSynced()

        // PHASE 4 (U2): instant lease/host push for this room's row.
        SupabaseClient.onListeningSessionRow = ::onSessionRowUpdate
        activeSession()?.id?.let { SupabaseClient.joinSessionRowChannel(it) }

        startFgsLoops()
        sweeperJob = scope.launch {
            while (isActive) {
                delay(3000)
                val now = System.currentTimeMillis()
                _members.update { list ->
                    list.filter { now - it.lastSeenMs < 15_000 }
                        .map { m ->
                            if (m.userId == myUserId()) m.copy(lastSeenMs = now) else m
                        }
                }
                refreshConnStatus(SupabaseClient.isRealtimeConnected.value)
            }
        }
    }

    fun refreshConnStatus(wsConnected: Boolean) {
        _connStatus.value = when {
            !wsConnected || !isActive() -> ConnStatus.OFFLINE
            !isHost() && System.currentTimeMillis() - lastHostTickAt > 6_000 -> ConnStatus.DEGRADED
            else -> ConnStatus.LIVE
        }
    }

    // ═══════════════ Handshake & teardown ═══════════════

    /**
     * Authoritative join/reconnect reconciliation. Returns the snapshot so the
     * caller can execute Apply commands against the live player.
     */
    suspend fun reconcile(): ListeningSession? {
        val s = activeSession() ?: return null
        val snap = withContext(Dispatchers.IO) {
            SupabaseClient.fetchJamSnapshot(s.sessionCode).getOrNull()
        } ?: return null

        _queue.value = snap.queue

        // Roster seed from row; presence pulses refine within seconds.
        _members.value = snap.participantIds.map { uid ->
            Member(
                userId = uid,
                name = if (uid == myUserId()) (SupabaseClient.currentUser.value?.displayName ?: "You") else uid.take(6),
                avatarUrl = if (uid == myUserId()) SupabaseClient.currentUser.value?.avatarUrl else null,
                isHost = uid == snap.hostUserId,
                lastSeenMs = System.currentTimeMillis()
            )
        }
        noteMember(myUserId(), SupabaseClient.currentUser.value?.displayName ?: "You",
            SupabaseClient.currentUser.value?.avatarUrl, isHost())

        return snap
    }

    /**
     * Compute where the host SHOULD be right now — skew-free (P1).
     *
     * `host_clock_timestamp` / `position_ms` on the DB row were written by the
     * HOST against its synced-monotonic timeline; the guest reads "now" from
     * its own synced clock, so cross-device NTP skew cancels identically.
     * Wall-clock fallback keeps pre-upgrade hosts working.
     */
    fun extrapolatePosition(session: ListeningSession): Long {
        if (!session.isPlaying) return session.positionMs.coerceAtLeast(0L)
        val nowSync = nowSynced()
        val hostStamp = session.hostClockTimestamp
        val elapsed = if (hostStamp in 1 until 100_000_000_000L) {
            // v3 host: row stamp is in the synced-monotonic domain (small value).
            (nowSync - hostStamp).coerceIn(0L, 600_000L)
        } else {
            // Legacy host: wall-clock epoch millis — skew applies (fallback only).
            (System.currentTimeMillis() - hostStamp).coerceAtLeast(0L)
        }
        return session.positionMs + elapsed
    }

    fun leaveSession(endForEveryone: Boolean) {
        val s = activeSession()
        if (s != null) {
            if (endForEveryone && isHost()) {
                SupabaseClient.broadcastJamTick(
                    sessionCode = s.sessionCode, trackId = "", trackTitle = "", trackArtist = "",
                    positionMs = 0L, isPlaying = false, action = "SESSION_END", senderId = deviceId
                )
            } else if (!endForEveryone) {
                SupabaseClient.broadcastJamTick(
                    sessionCode = s.sessionCode, trackId = "", trackTitle = "", trackArtist = "",
                    positionMs = 0L, isPlaying = false, action = "LEAVE",
                    senderId = deviceId,
                    extras = JSONObject().put("p_user", myUserId())
                )
            }
            scope.launch(Dispatchers.IO) {
                SupabaseClient.patchJamParticipant(s.sessionCode, myUserId(), add = false)
            }
        }
        endLocally()
    }

    private fun endLocally() {
        sessionEndedLocally = true
        stopFgsLoops()
        SupabaseClient.onListeningSessionRow = null
        _members.value = emptyList()
        _queue.value = emptyList()
        pendingNextIsTrack = null
        _connStatus.value = ConnStatus.OFFLINE
        latestAppliedEpoch = Long.MIN_VALUE
        lastHostTickAt = 0L
        com.streamify.app.data.NativeBridge.jamClockReset()
        com.streamify.app.data.NativeBridge.kalmanPllReset()
        sweeperJob?.cancel()
        sweeperJob = null
        SupabaseClient.leaveJamSession()
    }

    // Attached by the app shell; nullable-safe everywhere by design.
    internal var bridge: Bridge? = null
    fun attachBridge(b: Bridge?) { bridge = b }

    // ── PHASE 4: FGS-tied runtime ─────────────────────────────────────────

    /** Playback probes attached with the bridge: returns [positionMs, durationMs]. */
    @Volatile var playbackProbe: (() -> LongArray)? = null
    fun attachPlaybackProbe(p: (() -> LongArray)?) { playbackProbe = p }

    private var fgsScope: kotlinx.coroutines.CoroutineScope? = null
    private val runtimeJobs = mutableListOf<Job>()

    /**
     * PHASE 4 (U1): the distributed loops are tethered to the
     * PlaybackService foreground-service scope, NOT a viewModel — they must
     * survive navigation and hold network priority while music plays.
     */
    fun attachRuntimeScope(scope: kotlinx.coroutines.CoroutineScope?) {
        fgsScope = scope
        // Self-healing boot: if the session activated before the FGS existed
        // (or the service was recreated), the loops must come up NOW —
        // startRuntime()'s sweeper guard would otherwise skip them forever.
        if (scope != null && isActiveSessionInternal()) startFgsLoops()
        if (scope == null) stopFgsLoops()
    }

    /** Cached lease row from Postgres Changes / last poll — dead-man input. */
    @Volatile private var cachedLeaseExpired: Boolean = true
    @Volatile private var cachedLeaseHostId: String? = null
    @Volatile private var cachedParticipantsJson: String = ""
    @Volatile private var lastLeaseFetchAtMs: Long = 0L

    /**
     * PHASE 4 (U2): Postgres Changes hook. SupabaseClient forwards every
     * listening_sessions row update for our session here.
     */
    fun onSessionRowUpdate(record: org.json.JSONObject) {
        val s = activeSession() ?: return
        val rid = record.optString("id")
        if (rid.isNotBlank() && rid != s.id) return

        val newHost = record.optString("host_user_id").ifBlank { null }
        val epoch = record.optLong("host_epoch", 0L)
        cachedLeaseHostId = newHost
        cachedLeaseExpired = runCatching {
            val exp = record.optString("host_lease_expires_at", "")
            exp.isBlank() || java.time.Instant.parse(exp).toEpochMilli() < System.currentTimeMillis()
        }.getOrDefault(true)

        SupabaseClient.adoptForeignHost(s.sessionCode, newHost)

        // Instant authority adoption on remote takeover (epoch-gated).
        if (!isHost() && epoch > latestAppliedEpoch && epoch > 0 &&
            newHost != null && newHost != s.hostUserId
        ) {
            adoptTakeover(epoch)
        }
    }

    private fun startFgsLoops() {
        val scope = fgsScope ?: return
        if (runtimeJobs.any { it.isActive }) return // already running
        runtimeJobs.clear()
        runtimeJobs += scope.launch { heartbeatLoop() }
        runtimeJobs += scope.launch { guestLeaseWatchLoop() }
        runtimeJobs += scope.launch { outboxFlushLoop() }
        runtimeJobs += scope.launch { presencePulseLoop() }
        runtimeJobs += scope.launch { clockSyncLoop() }
        SLog.i("JamRuntime", "FGS-tied loops started (${runtimeJobs.size})")
    }

    private fun stopFgsLoops() {
        runtimeJobs.forEach { it.cancel() }
        runtimeJobs.clear()
    }

    // ── Host TTL heartbeat (moved from JamViewModel; FGS-tied) ──
    private suspend fun heartbeatLoop() {
        while (true) {
            delay(5_000)
            if (!isActiveSessionInternal() || !isHost() || isDemoted) continue
            val session = activeSession() ?: continue
            val probe = playbackProbe?.invoke()
            val pos = probe?.getOrNull(0)?.coerceAtLeast(0L) ?: 0L
            val verdict = SupabaseClient.jamHeartbeat(session.id, pos, nowSynced())
            if (verdict == "DEMOTED") {
                selfDemote(cachedLeaseHostId)
                _commands.tryEmit(Command.Rehandshake)
            }
        }
    }

    private fun isActiveSessionInternal(): Boolean =
        activeSession() != null && !sessionEndedLocally

    // ── Guest lease watch + dead-man's switch (U2/U4) ──
    private suspend fun guestLeaseWatchLoop() {
        while (true) {
            delay(2_000)
            if (!isActiveSessionInternal() || isHost()) continue
            val now = System.currentTimeMillis()

            // Tick-silence gating: healthy stream ⇒ zero REST traffic.
            val tickSilent = now - lastHostTickAt > 15_000
            if (!tickSilent && !cachedLeaseExpired) continue
            if (now - lastLeaseFetchAtMs < 10_000) continue
            lastLeaseFetchAtMs = now

            val session = activeSession() ?: continue
            val snap = SupabaseClient.fetchJamLeaseRow(session.id) ?: continue
            cachedLeaseExpired = snap.leaseExpired
            cachedLeaseHostId = snap.hostUserId
            cachedParticipantsJson = snap.participantIds.joinToString(",")

            attemptSuccessionIfEligible(session, snap)
        }
    }

    /** A+B succession attempt — extracted so both poll and dead-man paths share it. */
    private suspend fun attemptSuccessionIfEligible(
        session: com.streamify.app.data.remote.ListeningSession,
        snap: SupabaseClient.JamLeaseSnapshot
    ) {
        val myId = myUserId()
        val advisory = com.streamify.app.data.NativeBridge.jamIsAdvisorySuccessor(
            snap.participantIds, snap.hostUserId ?: return, myId,
            recentlySeen = true
        )
        // Vacuum path: grace elapsed lets ANY participant claim server-side.
        val durMs = playbackProbe?.invoke()?.getOrNull(1) ?: 0L
        val decision = com.streamify.app.data.NativeBridge.jamExtrapolatePivot(
            lastPosMs = snap.lastTickPosMs,
            lastTickMonoMs = snap.lastTickMonoMs,
            currentSyncedMonoMs = nowSynced(),
            durationMs = durMs,
            trackMatches = true
        )
        val pivotPos = when (decision[0].toInt()) {
            PIVOT_OK_CODE -> decision[1]
            PIVOT_BEYOND_CODE -> 0L
            else -> return // MISMATCH handled post-claim via TRACK_CHANGE
        }

        val newEpoch = SupabaseClient.jamTakeover(
            sessionId = session.id,
            advisorySuccessor = myId,
            pivotPosMs = pivotPos,
            pivotMonoMs = nowSynced(),
        ) ?: return

        clearDemotion()
        adoptTakeover(newEpoch)
        if (decision[0].toInt() == PIVOT_OK_CODE) {
            _commands.tryEmit(Command.ApplyPllTick(pivotPos, nowSynced(), durMs, true))
        }
        broadcastJamTickPublic(
            sessionCode = session.sessionCode,
            positionMs = pivotPos.coerceAtLeast(0L),
            action = "HOST_TAKEOVER",
            extras = org.json.JSONObject()
                .put("t_host", myId)
                .put("t_epoch", newEpoch)
                .put("t_pivot_pos", decision[1])
        )
        SLog.i("JamLease", "authority claimed via ${if (advisory) "deterministic" else "vacuum"} path, epoch=$newEpoch")
    }

    const val PIVOT_OK_CODE = 0
    const val PIVOT_BEYOND_CODE = 1
    const val PIVOT_MISMATCH_CODE = 2

    /** Public tick broadcast wrapper for engine-internal authority events. */
    fun broadcastJamTickPublic(
        sessionCode: String,
        positionMs: Long,
        action: String,
        extras: org.json.JSONObject?
    ): Boolean = SupabaseClient.broadcastJamTick(
        sessionCode = sessionCode, trackId = "", trackTitle = "", trackArtist = "",
        positionMs = positionMs, isPlaying = false, action = action,
        senderId = deviceId, epochMs = currentEpoch(), extras = extras
    )

    // ── Outbox flush loop (moved from JamViewModel, role-agnostic) ──
    private suspend fun outboxFlushLoop() {
        var gcCounter = 0
        while (true) {
            delay(1_500)
            if (!isActiveSessionInternal() || !outboxReady) continue
            val session = activeSession() ?: continue

            com.streamify.app.data.NativeBridge.jamOutboxReplay(30_000L)
            val batch = com.streamify.app.data.NativeBridge.jamOutboxPoll(session.sessionCode, 32)
            if (batch.isEmpty()) {
                if (++gcCounter % 200 == 0) {
                    com.streamify.app.data.NativeBridge.jamOutboxGc(24L * 60 * 60 * 1000)
                }
                continue
            }
            val ackIds = ArrayList<Long>(batch.size / 7)
            var i = 0
            while (i + 6 < batch.size) {
                val opId = batch[i]
                val meta = batch[i + 1]
                val oType = ((meta shr 8) and 0xFF).toInt()
                val oCad = batch[i + 2]
                val fracBits = batch[i + 3]
                val oTarget = batch[i + 4]
                val sent = broadcastJamTickPublic(
                    sessionCode = session.sessionCode,
                    positionMs = 0L,
                    action = "OP",
                    extras = org.json.JSONObject()
                        .put("o_id", opId)
                        .put("o_sender", senderPacked)
                        .put("o_type", oType)
                        .put("o_policy", 0)
                        .put("o_cad", oCad)
                        .put("o_frac_bits", fracBits)
                        .put("o_target", oTarget)
                )
                if (sent) ackIds.add(opId)
                i += 7
            }
            if (ackIds.isNotEmpty()) {
                com.streamify.app.data.NativeBridge.jamOutboxAck(ackIds.toLongArray())
            }
            delay(250)
        }
    }

    // ── Presence pulse (moved from JamViewModel) ──
    private suspend fun presencePulseLoop() {
        while (true) {
            delay(5_000)
            if (isActiveSessionInternal()) {
                val me = SupabaseClient.currentUser.value
                pulsePresence(me?.displayName ?: "Listener", me?.avatarUrl)
            }
        }
    }

    // ── Clock-sync probe loop (moved from JamViewModel) ──
    private suspend fun clockSyncLoop() {
        while (true) {
            delay(1_500)
            if (clockSyncProbeDue()) fireClockSyncProbe()
        }
    }

    /** Guest-side shadow-prebuffer hook fired when a host NEXT_IS lands. */
    @Volatile var onNextIsListener: ((Track) -> Unit)? = null
    fun setOnNextIsListener(l: ((Track) -> Unit)?) { onNextIsListener = l }
}
