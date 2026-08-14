package com.streamify.app.data.network

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

object LyricsResolver {

    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private fun cleanQuery(s: String): String {
        if (s.isBlank()) return ""
        return s.replace(Regex("""[\(\[\{].*?(official|video|audio|lyric|hd|4k|remastered|mv|topic|vevo).*?[\)\]\}]""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""(feat\.|ft\.).*""", RegexOption.IGNORE_CASE), "")
            .replace("\"", "")
            .replace("'", "")
            .trim()
    }

    suspend fun fetchSyncedLyrics(title: String, artist: String, durationSec: Int = 0): String? = withContext(Dispatchers.IO) {
        val cleanTitle = cleanQuery(title).ifBlank { title.trim() }
        val cleanArtist = cleanQuery(artist.split(',')[0].replace("- Topic", "").replace("VEVO", "")).ifBlank { artist.trim() }

        if (cleanTitle.isBlank()) return@withContext null

        return@withContext raceLyricsProviders(cleanTitle, cleanArtist, durationSec)
    }

    private suspend fun raceLyricsProviders(title: String, artist: String, durationSec: Int): String? = coroutineScope {
        val winnerDeferred = CompletableDeferred<String?>()

        val tasks = listOf(
            async { fetchLrclibExact(title, artist, durationSec) },
            async { fetchLrclibFuzzy(title, artist) },
            async { fetchNetEase(title, artist) },
            async { fetchLyricsOvh(title, artist) }
        )

        tasks.forEach { task ->
            task.invokeOnCompletion {
                try {
                    val res = task.getCompleted()
                    if (!res.isNullOrBlank() && (res.contains("[") || res.length > 20)) {
                        winnerDeferred.complete(res)
                    }
                } catch (e: Exception) {
                    // Ignore single provider error
                }
                if (tasks.all { it.isCompleted } && !winnerDeferred.isCompleted) {
                    winnerDeferred.complete(null)
                }
            }
        }

        val winner = winnerDeferred.await()
        tasks.forEach { if (!it.isCompleted) it.cancel() }
        return@coroutineScope winner
    }

    private fun fetchLrclibExact(title: String, artist: String, durationSec: Int): String? {
        try {
            var url = "https://lrclib.net/api/get?track_name=${URLEncoder.encode(title, "UTF-8")}&artist_name=${URLEncoder.encode(artist, "UTF-8")}"
            if (durationSec > 0) {
                url += "&duration=$durationSec"
            }

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .get()
                .build()

            NetworkEngine.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                val json = JSONObject(body)
                val synced = json.optString("syncedLyrics", "")
                if (synced.isNotBlank()) return synced
                val plain = json.optString("plainLyrics", "")
                if (plain.isNotBlank()) return plain
            }
        } catch (e: Exception) {
            // Ignore
        }
        return null
    }

    private fun fetchLrclibFuzzy(title: String, artist: String): String? {
        try {
            val q = URLEncoder.encode("$title $artist", "UTF-8")
            val url = "https://lrclib.net/api/search?q=$q"

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .get()
                .build()

            NetworkEngine.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                val array = JSONArray(body)
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    val synced = item.optString("syncedLyrics", "")
                    if (synced.isNotBlank()) return synced
                }
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    val plain = item.optString("plainLyrics", "")
                    if (plain.isNotBlank()) return plain
                }
            }
        } catch (e: Exception) {
            // Ignore
        }
        return null
    }

    private fun fetchNetEase(title: String, artist: String): String? {
        try {
            val q = URLEncoder.encode("$title $artist", "UTF-8")
            val searchUrl = "https://music.163.com/api/search/get/web?s=$q&type=1&offset=0&total=true&limit=1"

            val reqSearch = Request.Builder()
                .url(searchUrl)
                .header("User-Agent", USER_AGENT)
                .header("Referer", "https://music.163.com/")
                .get()
                .build()

            NetworkEngine.client.newCall(reqSearch).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                val root = JSONObject(body)
                val songs = root.optJSONObject("result")?.optJSONArray("songs") ?: return null
                if (songs.length() == 0) return null
                val songId = songs.getJSONObject(0).optLong("id", 0L)
                if (songId <= 0) return null

                // Fetch lyrics for songId
                val lrcUrl = "https://music.163.com/api/song/lyric?os=pc&id=$songId&lv=-1&kv=-1&tv=-1"
                val reqLrc = Request.Builder().url(lrcUrl).header("User-Agent", USER_AGENT).get().build()
                NetworkEngine.client.newCall(reqLrc).execute().use { lrcResp ->
                    if (!lrcResp.isSuccessful) return null
                    val lrcBody = lrcResp.body?.string() ?: return null
                    val lrcJson = JSONObject(lrcBody)
                    val lrcStr = lrcJson.optJSONObject("lrc")?.optString("lyric", "")
                    if (!lrcStr.isNullOrBlank() && lrcStr.contains("[")) return lrcStr
                }
            }
        } catch (e: Exception) {
            // Ignore
        }
        return null
    }

    private fun fetchLyricsOvh(title: String, artist: String): String? {
        try {
            val url = "https://api.lyrics.ovh/v1/${URLEncoder.encode(artist, "UTF-8")}/${URLEncoder.encode(title, "UTF-8")}"
            val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).get().build()
            NetworkEngine.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                val json = JSONObject(body)
                val lyrics = json.optString("lyrics", "")
                if (lyrics.isNotBlank() && lyrics.length > 15) return lyrics
            }
        } catch (e: Exception) {
            // Ignore
        }
        return null
    }
}
