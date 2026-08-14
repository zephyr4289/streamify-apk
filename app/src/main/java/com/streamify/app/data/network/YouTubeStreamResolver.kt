package com.streamify.app.data.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

data class ResolvedStream(
    val streamUrl: String,
    val mimeType: String,
    val bitrate: Int,
    val durationSec: Int
)

object YouTubeStreamResolver {

    private const val INNERTUBE_PLAYER_URL = "https://www.youtube.com/youtubei/v1/player"
    private const val USER_AGENT_ANDROID = "com.google.android.apps.youtube.music/6.42.52 (Linux; U; Android 14; en_US) gzip"

    fun extractVideoId(urlOrId: String): String? {
        val trimmed = urlOrId.trim()
        if (trimmed.length == 11 && !trimmed.contains("/") && !trimmed.contains("?")) {
            return trimmed
        }
        val matchWatch = Regex("[?&]v=([a-zA-Z0-9_-]{11})").find(trimmed)
        if (matchWatch != null) {
            return matchWatch.groupValues[1]
        }
        val matchShort = Regex("youtu\\.be/([a-zA-Z0-9_-]{11})").find(trimmed)
        if (matchShort != null) {
            return matchShort.groupValues[1]
        }
        val matchEmbed = Regex("/embed/([a-zA-Z0-9_-]{11})").find(trimmed)
        if (matchEmbed != null) {
            return matchEmbed.groupValues[1]
        }
        val matchLive = Regex("/live/([a-zA-Z0-9_-]{11})").find(trimmed)
        if (matchLive != null) {
            return matchLive.groupValues[1]
        }
        return null
    }

    suspend fun resolveStreamUrl(urlOrId: String): ResolvedStream? = withContext(Dispatchers.IO) {
        val videoId = extractVideoId(urlOrId) ?: return@withContext null

        // 1. Try ANDROID_MUSIC client (Instant raw unthrottled audio streams)
        val streamMusic = executePlayerRequest(videoId, clientName = "ANDROID_MUSIC", clientVersion = "6.42.52")
        if (streamMusic != null) {
            return@withContext streamMusic
        }

        // 2. Try standard ANDROID client fallback
        val streamAndroid = executePlayerRequest(videoId, clientName = "ANDROID", clientVersion = "19.09.37")
        if (streamAndroid != null) {
            return@withContext streamAndroid
        }

        // 3. Try IOS client fallback
        val streamIos = executePlayerRequest(videoId, clientName = "IOS", clientVersion = "19.09.3")
        return@withContext streamIos
    }

    private fun executePlayerRequest(videoId: String, clientName: String, clientVersion: String): ResolvedStream? {
        try {
            val url = URL(INNERTUBE_PLAYER_URL)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 3500
                readTimeout = 3500
                doOutput = true
                doInput = true
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                setRequestProperty("User-Agent", USER_AGENT_ANDROID)
                setRequestProperty("Accept", "*/*")
                setRequestProperty("Accept-Encoding", "gzip, deflate")
                setRequestProperty("X-YouTube-Client-Name", if (clientName == "ANDROID_MUSIC") "21" else "3")
                setRequestProperty("X-YouTube-Client-Version", clientVersion)
            }

            val requestJson = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", clientName)
                        put("clientVersion", clientVersion)
                        put("androidSdkVersion", 34)
                        put("hl", "en")
                        put("gl", "US")
                    })
                })
                put("videoId", videoId)
                put("contentCheckOk", true)
                put("racyCheckOk", true)
            }

            conn.outputStream.use { os ->
                os.write(requestJson.toString().toByteArray(Charsets.UTF_8))
                os.flush()
            }

            if (conn.responseCode != 200) {
                return null
            }

            val inputStream = if ("gzip".equals(conn.contentEncoding, ignoreCase = true)) {
                GZIPInputStream(conn.inputStream)
            } else {
                conn.inputStream
            }

            val responseBody = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { it.readText() }
            val root = JSONObject(responseBody)
            
            return parsePlayerResponse(root)
        } catch (e: Exception) {
            return null
        }
    }

    private fun parsePlayerResponse(root: JSONObject): ResolvedStream? {
        try {
            val playabilityStatus = root.optJSONObject("playabilityStatus")
            val status = playabilityStatus?.optString("status", "")
            if (status != null && !status.equals("OK", ignoreCase = true)) {
                return null
            }

            val streamingData = root.optJSONObject("streamingData") ?: return null
            val durationSec = root.optJSONObject("videoDetails")?.optString("lengthSeconds", "0")?.toIntOrNull() ?: 0

            val candidateFormats = mutableListOf<JSONObject>()

            // Collect adaptive formats (pure audio streams)
            val adaptiveFormats = streamingData.optJSONArray("adaptiveFormats")
            if (adaptiveFormats != null) {
                for (i in 0 until adaptiveFormats.length()) {
                    val f = adaptiveFormats.getJSONObject(i)
                    val mime = f.optString("mimeType", "")
                    val streamUrl = f.optString("url", "")
                    if (mime.startsWith("audio/") && streamUrl.isNotBlank()) {
                        candidateFormats.add(f)
                    }
                }
            }

            // Fallback to standard formats
            if (candidateFormats.isEmpty()) {
                val formats = streamingData.optJSONArray("formats")
                if (formats != null) {
                    for (i in 0 until formats.length()) {
                        val f = formats.getJSONObject(i)
                        val streamUrl = f.optString("url", "")
                        if (streamUrl.isNotBlank()) {
                            candidateFormats.add(f)
                        }
                    }
                }
            }

            if (candidateFormats.isEmpty()) return null

            // Sort: Prioritize audio/mp4 (m4a) and highest bitrate
            candidateFormats.sortWith(Comparator { a, b ->
                val mimeA = a.optString("mimeType", "")
                val mimeB = b.optString("mimeType", "")
                val isMp4A = if (mimeA.contains("audio/mp4") || mimeA.contains("m4a")) 1 else 0
                val isMp4B = if (mimeB.contains("audio/mp4") || mimeB.contains("m4a")) 1 else 0

                if (isMp4A != isMp4B) {
                    return@Comparator isMp4B.compareTo(isMp4A)
                }

                val bitrateA = a.optInt("bitrate", a.optInt("averageBitrate", 0))
                val bitrateB = b.optInt("bitrate", b.optInt("averageBitrate", 0))
                bitrateB.compareTo(bitrateA)
            })

            val bestFormat = candidateFormats.first()
            val streamUrl = bestFormat.optString("url", "")
            val mimeType = bestFormat.optString("mimeType", "audio/mp4")
            val bitrate = bestFormat.optInt("bitrate", bestFormat.optInt("averageBitrate", 128000))

            if (streamUrl.isNotBlank()) {
                return ResolvedStream(
                    streamUrl = streamUrl,
                    mimeType = mimeType,
                    bitrate = bitrate,
                    durationSec = durationSec
                )
            }
            return null
        } catch (e: Exception) {
            return null
        }
    }
}
