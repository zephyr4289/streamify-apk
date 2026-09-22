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

/** Admin console backend: telemetry, role management, session/comment moderation, broadcasts. */
internal object SupabaseAdminClient {
    suspend fun getAdminTelemetry(): Result<AdminTelemetry> = withContext(Dispatchers.IO) {
        val startMs = System.currentTimeMillis()
        try {
            var totalUsers = 0
            var totalTracks = 0
            var totalPlaylists = 0
            var activeJams = 0
            var totalComments = 0
            var totalLikes = 0
            var totalPlays = 0L
            var dau24h = 0
            var serverStatus = "Operational"
            var rpcHealthy = false
            var engineMode = "PostgreSQL 15 + pgvector 0.5.1"

            try {
                val (rpcCode, resp) = SupabaseClient.executeRpc("rpc/get_admin_dashboard_stats", "POST")
                if (rpcCode in 200..299 && resp != null) {
                    val o = JSONObject(resp)
                    totalUsers = o.optInt("total_users", 0)
                    totalTracks = o.optInt("total_tracks", 0)
                    totalPlaylists = o.optInt("total_playlists", 0)
                    activeJams = o.optInt("active_jam_sessions", 0)
                    totalComments = o.optInt("total_comments", 0)
                    totalLikes = o.optInt("total_likes", 0)
                    totalPlays = o.optLong("total_plays", 0L)
                    dau24h = o.optInt("dau_24h", 0)
                    serverStatus = o.optString("server_status", "Operational")
                    engineMode = o.optString("engine_mode", "PostgreSQL 15 + pgvector 0.5.1")
                    rpcHealthy = true
                }
            } catch (e: Exception) {
                SLog.st("SupabaseClient", "SupabaseClient.getAdminTelemetry failed", e)
            }

            val (code, resp) = SupabaseClient.executeRpc("profiles?select=*&order=created_at.desc&limit=100", "GET")

            val users = mutableListOf<UserProfile>()
            val currentLocalUser = SupabaseClient._currentUser.value
            val context = TrackRepository.appContext
            val prefs = context?.getSharedPreferences("streamify_playback_telemetry", android.content.Context.MODE_PRIVATE)
            val localSec = prefs?.getLong("total_listened_seconds", 0L) ?: 0L
            val localTopTracks = TrackRepository.getTopPlayedTracks(1)
            val localTopTrackTitle = localTopTracks.firstOrNull()?.let { "${it.title} • ${it.artist}" } ?: ""
            val localTotalPlays = TrackRepository.getAllTracks().sumOf { it.playCount }.coerceAtLeast(localTopTracks.size)

            if (code in 200..299 && resp != null) {
                val arr = JSONArray(resp)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val uId = o.optString("id", "")
                    val uEmail = o.optString("email", "")
                    val isCurrent = (uId.isNotBlank() && uId == currentLocalUser?.id) ||
                            (uEmail.isNotBlank() && uEmail.equals(currentLocalUser?.email, ignoreCase = true)) ||
                            uEmail.contains("sireenyadav", ignoreCase = true) ||
                            (currentLocalUser == null && i == 0)

                    var rawListeningSeconds = o.optLong("listening_seconds", 0L)
                    var rawTotalPlays = o.optInt("total_plays", 0)
                    var rawTopTrack = o.optString("top_track", "")
                    var rawBio = o.optString("bio", "")
                    var rawGenre = o.optString("favorite_genre", "")

                    if (isCurrent) {
                        rawListeningSeconds = maxOf(rawListeningSeconds, localSec)
                        rawTotalPlays = maxOf(rawTotalPlays, localTotalPlays)
                        if (localTopTrackTitle.isNotBlank() && rawTopTrack.isBlank()) rawTopTrack = localTopTrackTitle
                        if (rawBio.isBlank()) rawBio = "⚡ Kinetic Pulse Runner (Owner)"
                        if (rawGenre.isBlank()) rawGenre = "All"
                    }

                    users.add(
                        UserProfile(
                            id = uId,
                            email = uEmail,
                            displayName = o.optString("display_name", if (isCurrent) "Admin" else "User"),
                            avatarUrl = o.optString("avatar_url", ""),
                            bio = rawBio.ifBlank { if (rawListeningSeconds > 0) "Music Explorer 🎧" else "New Explorer 🎧" },
                            favoriteGenre = rawGenre.ifBlank { "All" },
                            topTrack = rawTopTrack,
                            isAdmin = o.optBoolean("is_admin", false) || isCurrent,
                            totalPlays = rawTotalPlays,
                            listeningSeconds = rawListeningSeconds,
                            createdAt = o.optString("created_at", ""),
                            lastActiveAt = o.optString("last_active_at", "")
                        )
                    )
                }
            }

            val endMs = System.currentTimeMillis()
            val latency = (endMs - startMs).coerceAtLeast(12L)

            val telemetry = AdminTelemetry(
                totalUsers = if (totalUsers > 0) totalUsers else users.size.coerceAtLeast(1),
                totalTracks = totalTracks,
                totalPlaylists = totalPlaylists,
                activeJamSessions = activeJams,
                totalComments = totalComments,
                totalLikes = totalLikes,
                totalPlays = if (totalPlays > 0) totalPlays else users.sumOf { it.totalPlays.toLong() }.coerceAtLeast(localTotalPlays.toLong()),
                dau24h = dau24h,
                userList = users,
                serverStatus = if (rpcHealthy) serverStatus else "RPC ERROR — check is_admin / migration",
                latencyMs = latency,
                engineMode = engineMode
            )

            Result.success(telemetry)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun setUserAdminRole(targetUserId: String, isAdmin: Boolean): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().apply {
                put("target_user_id", targetUserId)
                put("new_admin_status", isAdmin)
            }
            val (code, _) = SupabaseClient.executeRpc("rpc/set_user_admin_role", "POST", body.toString())
            Result.success(code in 200..299)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun terminateJamSessionAdmin(sessionId: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().apply {
                put("target_session_id", sessionId)
            }
            val (code, _) = SupabaseClient.executeRpc("rpc/terminate_jam_session", "POST", body.toString())
            Result.success(code in 200..299)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteCommentAdmin(commentId: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().apply {
                put("target_comment_id", commentId)
            }
            val (code, _) = SupabaseClient.executeRpc("rpc/delete_comment_admin", "POST", body.toString())
            Result.success(code in 200..299)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getAdminJamSessions(): Result<List<AdminJamSession>> = withContext(Dispatchers.IO) {
        try {
            val (code, resp) = SupabaseClient.executeRpc("rpc/get_admin_jam_sessions", "POST")

            val list = mutableListOf<AdminJamSession>()
            if (code in 200..299 && resp != null) {
                val arr = JSONArray(resp)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    list.add(
                        AdminJamSession(
                            id = o.optString("id", ""),
                            sessionCode = o.optString("session_code", ""),
                            hostName = o.optString("host_name", "Host"),
                            hostEmail = o.optString("host_email", ""),
                            currentTrackTitle = o.optString("current_track_title", "None"),
                            currentTrackArtist = o.optString("current_track_artist", ""),
                            participantCount = o.optInt("participant_count", 1),
                            isPlaying = o.optBoolean("is_playing", false),
                            updatedAt = o.optString("updated_at", "")
                        )
                    )
                }
            }
            Result.success(list)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getAdminRecentComments(limit: Int = 50): Result<List<AdminCommentItem>> = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().apply {
                put("limit_count", limit)
            }
            val (code, resp) = SupabaseClient.executeRpc("rpc/get_admin_recent_comments", "POST", body.toString())

            val list = mutableListOf<AdminCommentItem>()
            if (code in 200..299 && resp != null) {
                val arr = JSONArray(resp)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    list.add(
                        AdminCommentItem(
                            id = o.optString("id", ""),
                            trackId = o.optString("track_id", ""),
                            trackTitle = o.optString("track_title", "Track"),
                            userId = o.optString("user_id", ""),
                            userName = o.optString("user_name", "User"),
                            userAvatar = o.optString("user_avatar", ""),
                            commentText = o.optString("comment_text", ""),
                            timestampMs = o.optLong("timestamp_ms", 0L),
                            createdAt = o.optString("created_at", "")
                        )
                    )
                }
            }
            Result.success(list)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun postAdminBroadcast(message: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().apply {
                put("message", message)
                put("author_email", SupabaseClient._currentUser.value?.email ?: BuildConfig.ADMIN_EMAIL)
                put("is_active", true)
            }
            val (code, _) = SupabaseClient.executeRpc("admin_broadcasts", "POST", body.toString(), prefer = "return=minimal")
            Result.success(code in 200..299)
        } catch (e: Exception) {
            SLog.st("SupabaseClient", "SupabaseClient.postAdminBroadcast failed", e)
            Result.failure(e)
        }
    }

    // ============================================================================
    // PROJECT TITAN: DISTRIBUTED EDGE COMPUTE MESH
    // ============================================================================

}
