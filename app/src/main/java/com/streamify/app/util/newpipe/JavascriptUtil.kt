package com.streamify.app.util.newpipe

import org.json.JSONArray
import org.json.JSONObject

/**
 * Port of NewPipe's PoToken JavascriptUtil, rewritten against org.json +
 * android.util.Base64 so no serialization plugin is required.
 *
 * Handles the data massaging between YouTube's BotGuard endpoints and the
 * JavaScript VM running inside PoTokenWebView.
 */
object JavascriptUtil {

    /**
     * Parses the raw challenge data obtained from the Create endpoint and returns an object that
     * can be embedded in a JavaScript snippet.
     */
    fun parseChallengeData(rawChallengeData: String): String {
        val scrambled = JSONArray(rawChallengeData)

        val challengeData: JSONArray =
            if (scrambled.length() > 1 && !scrambled.isNull(1) && scrambled.get(1) is String) {
                JSONArray(descramble(scrambled.getString(1)))
            } else {
                scrambled.getJSONArray(1)
            }

        val messageId = challengeData.getString(0)
        val interpreterHash = challengeData.getString(3)
        val program = challengeData.getString(4)
        val globalName = challengeData.getString(5)
        val clientExperimentsStateBlob = challengeData.getString(7)

        var safeScriptWrapped: Any? = null
        if (!challengeData.isNull(1)) {
            val arr = challengeData.optJSONArray(1)
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    if (arr.get(i) is String) { safeScriptWrapped = arr.get(i); break }
                }
            }
        }
        var trustedResourceWrapped: Any? = null
        if (!challengeData.isNull(2)) {
            val arr = challengeData.optJSONArray(2)
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    if (arr.get(i) is String) { trustedResourceWrapped = arr.get(i); break }
                }
            }
        }

        val interpreterJs = JSONObject()
        interpreterJs.put(
            "privateDoNotAccessOrElseSafeScriptWrappedValue",
            safeScriptWrapped ?: JSONObject.NULL
        )
        interpreterJs.put(
            "privateDoNotAccessOrElseTrustedResourceUrlWrappedValue",
            trustedResourceWrapped ?: JSONObject.NULL
        )

        val out = JSONObject()
        out.put("messageId", messageId)
        out.put("interpreterJavascript", interpreterJs)
        out.put("interpreterHash", interpreterHash)
        out.put("program", program)
        out.put("globalName", globalName)
        out.put("clientExperimentsStateBlob", clientExperimentsStateBlob)
        return out.toString()
    }

    /**
     * Parses the raw integrity token data obtained from the GenerateIT endpoint into a JavaScript
     * Uint8Array snippet and the token duration in seconds.
     */
    fun parseIntegrityTokenData(rawIntegrityTokenData: String): Pair<String, Long> {
        val arr = JSONArray(rawIntegrityTokenData)
        return base64ToU8(arr.getString(0)) to arr.getLong(1)
    }

    /** Converts an identifier string to a JS Uint8Array snippet. */
    fun stringToU8(identifier: String): String = newUint8Array(identifier.toByteArray())

    /**
     * Converts poToken bytes rendered as comma-separated ints ("97,98,99") — the output of
     * Uint8Array::toString() — into YouTube's base64url-ish poToken representation.
     */
    fun u8ToBase64(poToken: String): String {
        val bytes = poToken.split(",")
            .map { it.trim().toInt().toByte() }
            .toByteArray()
        val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        return b64.replace("+", "-").replace("/", "_")
    }

    /** Adds 97 to each byte of the base64-scrambled challenge. */
    private fun descramble(scrambledChallenge: String): String {
        val decoded = base64ToByteString(scrambledChallenge)
        val out = ByteArray(decoded.size)
        for (i in decoded.indices) out[i] = (decoded[i] + 97).toByte()
        return String(out, Charsets.ISO_8859_1)
    }

    private fun base64ToU8(base64: String): String = newUint8Array(base64ToByteString(base64))

    private fun newUint8Array(contents: ByteArray): String =
        "new Uint8Array([" + contents.joinToString(separator = ",") { (it.toInt() and 0xFF).toString() } + "])"

    private fun base64ToByteString(base64: String): ByteArray {
        val base64Mod = base64
            .replace('-', '+')
            .replace('_', '/')
            .replace('.', '=')
        return try {
            android.util.Base64.decode(base64Mod, android.util.Base64.DEFAULT)
        } catch (t: Throwable) {
            throw Exception("Cannot base64 decode", t)
        }
    }
}
