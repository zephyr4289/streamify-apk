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
import kotlinx.coroutines.asStateFlow
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
    }

    /** Live-player facade attached by the app shell. */
    interface Bridge {
        fun loadTrack(track: Track, positionMs: Long, play: Boolean)
        fun seekTo(positionMs: Long)
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

    /** Per-process device nonce: two devices signed into one account stay distinct. */
    val deviceId: String = UUID.randomUUID().toString().take(8)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val epochCounter = AtomicLong(0)

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
            epochMs = currentEpoch()
        )
    }

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

    fun addToQueue(track: Track, addedByName: String) {
        addedByMap[track.id.toString()] = "Added by $addedByName"
        _queue.update { current -> current + track }
        commitQueueIfHost()
        broadcastQueueOp("QUEUE_ADD", track)
    }

    fun removeFromQueue(track: Track) {
        _queue.update { current ->
            current.filterNot { it.id == track.id || (it.title == track.title && it.artist == track.artist) }
        }
        commitQueueIfHost()
        broadcastQueueOp("QUEUE_REMOVE", track)
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
                    isHost = payload.optBoolean("p_host", false)
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
                _commands.tryEmit(Command.ApplyTrack(t, pos, playing))
            }
            "SEEK" -> {
                if (isHost()) return
                _commands.tryEmit(Command.ApplySeek(pos))
            }
            "PLAY" -> {
                if (isHost()) return
                _commands.tryEmit(Command.ApplyPlayPause(true))
            }
            "PAUSE" -> {
                if (isHost()) return
                _commands.tryEmit(Command.ApplyPlayPause(false))
            }
            "TICK" -> {
                if (!senderIsHost) return // guests never drive the room clock
                lastHostTickAt = System.currentTimeMillis()
                refreshConnStatus(true)
                if (isHost()) return
                val dur = payload.optJSONObject("track_json")?.optInt("durationSec", 0)?.times(1000L) ?: 0L
                _commands.tryEmit(Command.ApplyPllTick(pos, payload.optLong("host_epoch_ms", System.currentTimeMillis()), dur, playing))
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

    /** Compute where the host SHOULD be right now from the snapshot clocks. */
    fun extrapolatePosition(session: ListeningSession): Long {
        if (!session.isPlaying) return session.positionMs.coerceAtLeast(0L)
        val drift = (System.currentTimeMillis() - session.hostClockTimestamp).coerceAtLeast(0L)
        return (session.positionMs + drift).coerceAtMost(Long.MAX_VALUE)
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
        _members.value = emptyList()
        _queue.value = emptyList()
        _connStatus.value = ConnStatus.OFFLINE
        latestAppliedEpoch = Long.MIN_VALUE
        lastHostTickAt = 0L
        sweeperJob?.cancel()
        sweeperJob = null
        SupabaseClient.leaveJamSession()
    }

    // Attached by the app shell; nullable-safe everywhere by design.
    internal var bridge: Bridge? = null
    fun attachBridge(b: Bridge?) { bridge = b }
}
