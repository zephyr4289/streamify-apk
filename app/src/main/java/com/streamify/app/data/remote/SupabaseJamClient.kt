package com.streamify.app.data.remote

import com.streamify.app.util.SLog
import android.content.Context
import android.content.SharedPreferences
import com.streamify.app.BuildConfig
import com.streamify.app.data.models.Track
import com.streamify.app.data.TrackRepository
import com.streamify.app.data.EdgeMeshRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import com.streamify.app.data.network.NetworkEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Distributed jam sessions: create/join, playback sync, queue broadcast, lease + takeover protocol. */
internal object SupabaseJamClient {
    internal val _activeJam = MutableStateFlow<ListeningSession?>(null)

    val activeJam: StateFlow<ListeningSession?> = _activeJam.asStateFlow()

    suspend fun createJamSession(track: Track, positionMs: Long): Result<ListeningSession> = withContext(Dispatchers.IO) {
        val user = SupabaseClient._currentUser.value ?: return@withContext Result.failure(Exception("Sign in with Google in Profile to start a Jam session"))
        try {
            if (SupabaseClient.isJwtExpired(SupabaseClient._accessToken.value)) {
                SupabaseClient.refreshSession()
            }
            SupabaseClient.ensureProfile(user)
            val sessionCode = (1..6).map { ('A'..'Z').random() }.joinToString("")
            val url = URL("${BuildConfig.SUPABASE_URL}/rest/v1/listening_sessions")

            val trackObj = com.streamify.app.data.remote.jamTrackToJson(track)

            val body = JSONObject().apply {
                put("host_user_id", user.id)
                put("session_code", sessionCode)
                put("current_track_id", track.id.toString())
                put("current_track_json", trackObj)
                put("position_ms", positionMs)
                put("is_playing", true)
                put("host_clock_timestamp", System.currentTimeMillis())
                put("participant_ids", JSONArray().put(user.id))
                put("queue_json", JSONArray().put(trackObj))
            }

            val (code, resp) = SupabaseClient.executeAdaptivePostgrestRequest(
                url = url,
                method = "POST",
                table = "listening_sessions",
                initialBody = body,
                prefer = "return=representation"
            )

            if (code in 200..299) {
                val arr = JSONArray(resp)
                val o = arr.getJSONObject(0)
                val jam = ListeningSession(
                    id = o.optString("id"),
                    sessionCode = sessionCode,
                    hostUserId = user.id,
                    currentTrackId = track.id.toString(),
                    currentTrackJson = trackObj,
                    positionMs = positionMs,
                    isPlaying = true,
                    // v3: synced-monotonic domain (skew-free extrapolation on guests).
                    hostClockTimestamp = com.streamify.app.data.NativeBridge.getSyncedJamMonotonicMs(),
                    queue = listOf(track),
                    participantIds = listOf(user.id)
                )
                _activeJam.value = jam
                joinJamRealtimeChannel(sessionCode)
                Result.success(jam)
            } else {
                Result.failure(Exception("Failed to initialize Jam: $resp"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun joinJamSession(sessionCode: String): Result<ListeningSession> = withContext(Dispatchers.IO) {
        val user = SupabaseClient._currentUser.value ?: return@withContext Result.failure(Exception("Sign in with Google in Profile to join a Jam"))
        try {
            if (SupabaseClient.isJwtExpired(SupabaseClient._accessToken.value)) {
                SupabaseClient.refreshSession()
            }
            SupabaseClient.ensureProfile(user)
            val safeCode = URLEncoder.encode(sessionCode.uppercase().trim(), "UTF-8")
            val url = URL("${BuildConfig.SUPABASE_URL}/rest/v1/listening_sessions?session_code=eq.$safeCode")

            val (code, resp) = SupabaseClient.executeAdaptivePostgrestRequest(
                url = url,
                method = "GET",
                table = "listening_sessions",
                initialBody = null
            )

            if (code in 200..299) {
                val arr = JSONArray(resp)
                if (arr.length() > 0) {
                    val o = arr.getJSONObject(0)
                    val rawTrackJson = o.optJSONObject("current_track_json")
                    val currentTrackId = o.optString("current_track_id")

                    val effectiveTrackJson = rawTrackJson ?: run {
                        if (currentTrackId.isNotBlank()) {
                            val localTrack = com.streamify.app.data.TrackRepository.getAllTracks().find {
                                it.id.toString() == currentTrackId || it.filepath.contains(currentTrackId)
                            }
                            localTrack?.let {
                                JSONObject().apply {
                                    put("id", it.id)
                                    put("title", it.title)
                                    put("artist", it.artist)
                                    put("filepath", it.filepath)
                                    put("coverArtPath", it.coverArtPath ?: "")
                                    put("durationSec", it.durationSec)
                                }
                            }
                        } else null
                    }

                    val persistedQueue = o.optJSONArray("queue_json")?.let { qArr ->
                        (0 until qArr.length()).mapNotNull { jamTrackFromJson(qArr.optJSONObject(it)) }
                    } ?: emptyList()
                    val participants = o.optJSONArray("participant_ids")?.let { pArr ->
                        (0 until pArr.length()).mapNotNull { pArr.optString(it).ifBlank { null } }
                    } ?: listOf(user.id)
                    val jam = ListeningSession(
                        id = o.optString("id"),
                        sessionCode = o.optString("session_code"),
                        hostUserId = o.optString("host_user_id"),
                        currentTrackId = currentTrackId,
                        currentTrackJson = effectiveTrackJson,
                        positionMs = o.optLong("position_ms", 0L),
                        isPlaying = o.optBoolean("is_playing", false),
                        hostClockTimestamp = o.optLong("host_clock_timestamp", System.currentTimeMillis()),
                        queue = persistedQueue,
                        participantIds = participants
                    )
                    _activeJam.value = jam
                    joinJamRealtimeChannel(sessionCode)
                    Result.success(jam)
                } else {
                    Result.failure(Exception("Jam room code not found"))
                }
            } else {
                Result.failure(Exception("Could not join Jam session: $resp"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateJamPlayback(sessionCode: String, track: Track, positionMs: Long, isPlaying: Boolean): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            // 1. Channel B: Instant Ephemeral WebSocket Broadcast (<15ms latency, Zero DB load)
            broadcastJamTick(
                sessionCode = sessionCode,
                trackId = track.id.toString(),
                trackTitle = track.title,
                trackArtist = track.artist,
                positionMs = positionMs,
                isPlaying = isPlaying,
                hostEpochMs = System.currentTimeMillis()
            )

            // 2. Channel A: Relational Control Plane Persistence
            val safeCode = URLEncoder.encode(sessionCode.uppercase().trim(), "UTF-8")
            val url = URL("${BuildConfig.SUPABASE_URL}/rest/v1/listening_sessions?session_code=eq.$safeCode")

            val trackObj = com.streamify.app.data.remote.jamTrackToJson(track)

            val body = JSONObject().apply {
                put("current_track_id", track.id.toString())
                put("current_track_json", trackObj)
                put("position_ms", positionMs)
                put("is_playing", isPlaying)
                put("host_clock_timestamp", System.currentTimeMillis())
            }

            val (code, _) = SupabaseClient.executeAdaptivePostgrestRequest(
                url = url,
                method = "PATCH",
                table = "listening_sessions",
                initialBody = body,
                prefer = "return=minimal"
            )

            Result.success(code in 200..299)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun broadcastJamTick(
        sessionCode: String,
        trackId: String,
        trackTitle: String,
        trackArtist: String,
        positionMs: Long,
        isPlaying: Boolean,
        hostEpochMs: Long = System.currentTimeMillis(),
        action: String = "TICK",
        trackJson: JSONObject? = null,
        senderId: String = "",
        epochMs: Long = 0L,
        extras: JSONObject? = null
    ): Boolean {
        val ws = SupabaseRealtimeClient.realtimeWebSocket ?: return false
        if (!SupabaseRealtimeClient._isRealtimeConnected.value) return false
        return try {
            val payload = JSONObject().apply {
                put("session_code", sessionCode.uppercase())
                put("action", action)
                put("track_id", trackId)
                put("track_title", trackTitle)
                put("track_artist", trackArtist)
                put("position_ms", positionMs)
                put("is_playing", isPlaying)
                put("host_epoch_ms", hostEpochMs)
                put("client_epoch_ms", System.currentTimeMillis())
                if (senderId.isNotBlank()) put("sender_id", senderId)
                if (epochMs > 0) put("epoch", epochMs)
                if (trackJson != null) {
                    put("track_json", trackJson)
                }
                if (extras != null) {
                    extras.keys().forEach { k -> put(k, extras.opt(k)) }
                }
            }
            val broadcastMsg = JSONObject().apply {
                put("topic", "realtime:jam_${sessionCode.uppercase()}")
                put("event", "broadcast")
                put("payload", JSONObject().apply {
                    put("type", "jam_tick")
                    put("payload", payload)
                })
                put("ref", "jam_${System.currentTimeMillis()}")
            }
            ws.send(broadcastMsg.toString())
        } catch (e: Exception) {
            false // Non-blocking
        }
    }

    fun broadcastJamQueue(sessionCode: String, queue: List<Track>) {
        val ws = SupabaseRealtimeClient.realtimeWebSocket ?: return
        if (!SupabaseRealtimeClient._isRealtimeConnected.value) return
        try {
            val queueArr = JSONArray()
            queue.forEach { t ->
                queueArr.put(JSONObject().apply {
                    put("id", t.id)
                    put("title", t.title)
                    put("artist", t.artist)
                    put("album", t.album)
                    put("filepath", t.filepath)
                    put("coverArtPath", t.coverArtPath ?: "")
                    put("durationSec", t.durationSec)
                })
            }
            val payload = JSONObject().apply {
                put("session_code", sessionCode.uppercase())
                put("queue", queueArr)
            }
            val broadcastMsg = JSONObject().apply {
                put("topic", "realtime:jam_${sessionCode.uppercase()}")
                put("event", "broadcast")
                put("payload", JSONObject().apply {
                    put("type", "jam_queue_updated")
                    put("payload", payload)
                })
                put("ref", "jam_q_${System.currentTimeMillis()}")
            }
            ws.send(broadcastMsg.toString())
        } catch (e: Exception) {
            false // Non-blocking
        }
    }

    fun joinJamRealtimeChannel(sessionCode: String) {
        val ws = SupabaseRealtimeClient.realtimeWebSocket ?: return
        if (!SupabaseRealtimeClient._isRealtimeConnected.value) return
        try {
            val joinMsg = JSONObject().apply {
                put("topic", "realtime:jam_${sessionCode.uppercase()}")
                put("event", "phx_join")
                put("payload", JSONObject())
                put("ref", "join_jam_${sessionCode.uppercase()}")
            }
            ws.send(joinMsg.toString())
        } catch (e: Exception) {
            // Non-blocking
        }
    }

    /**
     * PHASE 4 (U2): subscribes to Postgres Changes on THIS session's row so
     * lease expiry / host takeover push instantly instead of via REST polls.
     */

    fun joinSessionRowChannel(sessionId: String) {
        val ws = SupabaseRealtimeClient.realtimeWebSocket ?: return
        if (!SupabaseRealtimeClient._isRealtimeConnected.value) return
        try {
            val topic = "realtime:public:listening_sessions:id=eq.$sessionId"
            val joinMsg = JSONObject().apply {
                put("topic", topic)
                put("event", "phx_join")
                put("payload", JSONObject().apply {
                    put("config", JSONObject().apply {
                        put("postgres_changes", JSONArray().apply {
                            put(JSONObject().apply {
                                put("event", "UPDATE")
                                put("schema", "public")
                                put("table", "listening_sessions")
                                put("filter", "id=eq.$sessionId")
                            })
                        })
                    })
                })
                put("ref", "jam_lease_$sessionId")
            }
            ws.send(joinMsg.toString())
        } catch (_: Exception) {
        }
    }

    fun leaveJamSession() {
        _activeJam.value = null
    }

    // ========================================================================
    // JAM LOCKSTEP v2 — snapshot & persistence primitives
    // ========================================================================

    /** Full authoritative state fetch: used on join AND on every reconnect. */
    /**
     * PHASE 3: local adoption of a foreign host after takeover/demotion —
     * keeps the in-memory session mirror consistent with the server row.
     */
    /**
     * PHASE 4 (U2): listening_sessions row updates are forwarded here by the
     * WS dispatcher. JamEngine registers this at runtime — a static callback
     * avoids a circular import (engine already imports this file).
     */
    @Volatile

    fun adoptForeignHost(sessionCode: String, newHostUserId: String?) {
        val current = _activeJam.value ?: return
        if (current.sessionCode != sessionCode) return
        _activeJam.value = current.copy(
            hostUserId = newHostUserId ?: current.hostUserId
        )
    }

    // ── PHASE 3: host lease & takeover RPCs ──────────────────────────────

    suspend fun jamHeartbeat(sessionId: String, posMs: Long, monoMs: Long): String =
        withContext(Dispatchers.IO) {
            try {
                val (code, body) = SupabaseClient.executeRpc(
                    endpoint = "rpc/jam_heartbeat",
                    body = org.json.JSONObject()
                        .put("p_session_id", sessionId)
                        .put("p_pos_ms", posMs)
                        .put("p_mono_ms", monoMs)
                        .toString(),
                    prefer = "return=representation"
                )
                if (code in 200..299) {
                    body?.trim('"', ' ') ?: "DEMOTED"
                } else "DEMOTED"
            } catch (_: Throwable) {
                "DEMOTED"
            }
        }

    /**
     * Claims authority on an expired lease. Returns the server-issued
     * fencing-token epoch, or null when rejected (not successor / grace
     * pending / not a member).
     */

    suspend fun jamTakeover(
        sessionId: String,
        advisorySuccessor: String,
        pivotPosMs: Long,
        pivotMonoMs: Long
    ): Long? = withContext(Dispatchers.IO) {
        try {
            val (code, body) = SupabaseClient.executeRpc(
                endpoint = "rpc/jam_takeover",
                body = org.json.JSONObject()
                    .put("p_session_id", sessionId)
                    .put("p_advisory_successor", advisorySuccessor)
                    .put("p_pivot_pos_ms", pivotPosMs)
                    .put("p_pivot_mono_ms", pivotMonoMs)
                    .toString(),
                prefer = "return=representation"
            )
            if (code in 200..299) {
                org.json.JSONArray(body).optLong(0, -1L).takeIf { it > 0 }
            } else null
        } catch (_: Throwable) {
            null
        }
    }

    /** Lightweight lease-state read for guest succession watches. */

    suspend fun fetchJamLeaseRow(sessionId: String): SupabaseClient.JamLeaseSnapshot? =
        withContext(Dispatchers.IO) {
            try {
                val safe = android.net.Uri.encode(sessionId)
                val url = URL("${BuildConfig.SUPABASE_URL}/rest/v1/listening_sessions?id=eq.$safe&select=id,host_user_id,host_lease_expires_at,last_tick_pos_ms,last_tick_mono_ms,participant_ids,host_epoch")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("apikey", BuildConfig.SUPABASE_ANON_KEY)
                conn.setRequestProperty("Authorization", "Bearer ${SupabaseClient.getAuthToken()}")
                conn.connectTimeout = 4000
                conn.readTimeout = 4000
                conn.inputStream.bufferedReader().use { r ->
                    val arr = org.json.JSONArray(r.readText())
                    if (arr.length() == 0) return@use null
                    val o = arr.getJSONObject(0)
                    val expires = o.optString("host_lease_expires_at", "")
                    val expired = expires.isBlank() || runCatching {
                        java.time.Instant.parse(expires).toEpochMilli() < System.currentTimeMillis()
                    }.getOrDefault(true)
                    SupabaseClient.JamLeaseSnapshot(
                        sessionId = o.getString("id"),
                        hostUserId = o.optString("host_user_id").ifBlank { null },
                        leaseExpired = expired,
                        lastTickPosMs = o.optLong("last_tick_pos_ms", 0L),
                        lastTickMonoMs = o.optLong("last_tick_mono_ms", 0L),
                        participantIds = buildList {
                            val parr = o.optJSONArray("participant_ids") ?: return@buildList
                            for (i in 0 until parr.length()) add(parr.getString(i))
                        },
                        hostEpoch = o.optLong("host_epoch", 0L)
                    )
                }
            } catch (_: Throwable) {
                null
            }
        }

    suspend fun fetchJamSnapshot(sessionCode: String): Result<ListeningSession> = withContext(Dispatchers.IO) {
        try {
            val safeCode = URLEncoder.encode(sessionCode.uppercase().trim(), "UTF-8")
            val (code, resp) = SupabaseClient.executeRpc("listening_sessions?session_code=eq.$safeCode", "GET")
            if (code in 200..299 && resp != null) {
                val arr = JSONArray(resp)
                if (arr.length() > 0) {
                    val o = arr.getJSONObject(0)
                    val queue = o.optJSONArray("queue_json")?.let { qArr ->
                        (0 until qArr.length()).mapNotNull { jamTrackFromJson(qArr.optJSONObject(it)) }
                    } ?: emptyList()
                    val participants = o.optJSONArray("participant_ids")?.let { pArr ->
                        (0 until pArr.length()).mapNotNull { pArr.optString(it).ifBlank { null } }
                    } ?: emptyList()
                    Result.success(
                        ListeningSession(
                            id = o.optString("id"),
                            sessionCode = o.optString("session_code"),
                            hostUserId = o.optString("host_user_id"),
                            currentTrackId = o.optString("current_track_id"),
                            currentTrackJson = o.optJSONObject("current_track_json"),
                            positionMs = o.optLong("position_ms", 0L),
                            isPlaying = o.optBoolean("is_playing", false),
                            hostClockTimestamp = o.optLong("host_clock_timestamp", System.currentTimeMillis()),
                            queue = queue,
                            participantIds = participants
                        )
                    )
                } else Result.failure(Exception("Jam room no longer exists"))
            } else Result.failure(Exception("Snapshot fetch failed: $code"))
        } catch (e: Exception) { Result.failure(e) }
    }

    /** Host-only persistence of the canonical shared queue. */

    fun patchJamQueueJson(sessionCode: String, queue: List<Track>) {
        try {
            val arr = JSONArray()
            queue.forEach { arr.put(jamTrackToJson(it)) }
            val safeCode = URLEncoder.encode(sessionCode.uppercase().trim(), "UTF-8")
            val body = JSONObject().put("queue_json", arr).toString()
            SupabaseClient.executeRpc("listening_sessions?session_code=eq.$safeCode", "PATCH", body, prefer = "return=minimal")
        } catch (e: Exception) { -1 }
    }

    /** Best-effort roster persistence (presence channel remains the live truth). */

    fun patchJamParticipant(sessionCode: String, userId: String, add: Boolean) {
        try {
            val safeCode = URLEncoder.encode(sessionCode.uppercase().trim(), "UTF-8")
            val (code, resp) = SupabaseClient.executeRpc("listening_sessions?session_code=eq.$safeCode&select=participant_ids", "GET")
            val current = mutableListOf<String>()
            if (code in 200..299 && resp != null) {
                val arr = JSONArray(resp)
                if (arr.length() > 0) {
                    val ids = arr.getJSONObject(0).optJSONArray("participant_ids")
                    if (ids != null) for (i in 0 until ids.length()) current.add(ids.optString(i))
                }
            }
            val next = (if (add) current + userId else current - userId).distinct()
            if (next == current) return
            val body = JSONArray().apply { next.forEach { put(it) } }
            SupabaseClient.executeRpc("listening_sessions?session_code=eq.$safeCode", "PATCH", JSONObject().put("participant_ids", body).toString(), prefer = "return=minimal")
        } catch (e: Exception) {
            // Best-effort: presence broadcast remains the live source of truth
        }
    }

    // ========================================================================
    // COMMUNITY PLAYLISTS & FRIEND ACTIVITY
    // ========================================================================

}
