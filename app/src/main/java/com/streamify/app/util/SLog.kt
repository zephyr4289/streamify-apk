package com.streamify.app.util

import android.content.Context
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * SLog — application-wide logging facade with in-app terminal support.
 *
 * Drop-in replacement for [android.util.Log]: identical method signatures, so
 * call sites only change their import (`import com.streamify.app.util.SLog as Log`).
 * Every entry is simultaneously:
 *   1. Forwarded to android.util.Log (adb logcat stays fully functional)
 *   2. Appended to a bounded in-memory ring buffer (read live by AdminTerminalScreen)
 *   3. Spooled to rotating session files under filesDir/logs/ (survives restarts,
 *      cleared on data wipe)
 *
 * Sensitive material (SAPISIDHASH headers, cookies, CDN signature params) is
 * redacted at append time.
 */
object SLog {

    // ------------------------------------------------------------------ model

    const val LEVEL_V = 'V'
    const val LEVEL_D = 'D'
    const val LEVEL_I = 'I'
    const val LEVEL_W = 'W'
    const val LEVEL_E = 'E'
    const val LEVEL_F = 'F' // fatal / crash

    data class Entry(
        val timeMs: Long,
        val level: Char,
        val tag: String,
        val message: String
    )

    private val buffer = ArrayDeque<Entry>()
    private val bufferLock = Any()
    private var bufferCap = 8000

    private val tailFlowInternal = kotlinx.coroutines.flow.MutableSharedFlow<Entry>(
        replay = 256, extraBufferCapacity = 4096
    )
    val tail: kotlinx.coroutines.flow.SharedFlow<Entry> get() = tailFlowInternal

    // ------------------------------------------------------------------ disk

    private const val LOG_DIR = "logs"
    private const val MAX_FILE_BYTES = 5L * 1024 * 1024
    private const val ROTATE_KEEP = 2
    private val diskQueue = Channel<String>(Channel.UNLIMITED)
    private val writerStarted = AtomicBoolean(false)
    private lateinit var logDir: File
    private val tsFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    // ------------------------------------------------------------------ init

    fun initialize(context: Context, capacity: Int = 8000) {
        bufferCap = capacity.coerceIn(1000, 50_000)
        logDir = File(context.filesDir, LOG_DIR).apply { mkdirs() }
        installCrashHook()
        if (writerStarted.compareAndSet(false, true)) {
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                for (line in diskQueue) {
                    writeToDisk(line)
                }
            }
        }
        i(TAG_INIT, "SLog initialized — cap=$bufferCap entries")
        i(
            TAG_INIT,
            "device=${Build.MANUFACTURER} ${Build.MODEL} sdk=${Build.VERSION.SDK_INT} " +
                "app=${context.packageName}"
        )
    }

    /** Boot banner with the app version once BuildConfig is available. */
    fun logBootBanner(appVersion: String) {
        i(TAG_INIT, "boot versionName=$appVersion")
    }

    // ------------------------------------------------------------- public api
    // Signatures mirror android.util.Log exactly (return Int like Log does).

    fun v(tag: String, msg: String): Int = append(LEVEL_V, tag, msg).also { android.util.Log.v(safeTag(tag), msg) }
    fun v(tag: String, msg: String, tr: Throwable?): Int = append(LEVEL_V, tag, msg + fmt(tr)).also { android.util.Log.v(safeTag(tag), msg, tr) }
    fun d(tag: String, msg: String): Int = append(LEVEL_D, tag, msg).also { android.util.Log.d(safeTag(tag), msg) }
    fun d(tag: String, msg: String, tr: Throwable?): Int = append(LEVEL_D, tag, msg + fmt(tr)).also { android.util.Log.d(safeTag(tag), msg, tr) }
    fun i(tag: String, msg: String): Int = append(LEVEL_I, tag, msg).also { android.util.Log.i(safeTag(tag), msg) }
    fun i(tag: String, msg: String, tr: Throwable?): Int = append(LEVEL_I, tag, msg + fmt(tr)).also { android.util.Log.i(safeTag(tag), msg, tr) }
    fun w(tag: String, msg: String): Int = append(LEVEL_W, tag, msg).also { android.util.Log.w(safeTag(tag), msg) }
    fun w(tag: String, msg: String, tr: Throwable?): Int = append(LEVEL_W, tag, msg + fmt(tr)).also { android.util.Log.w(safeTag(tag), msg, tr) }
    fun e(tag: String, msg: String): Int = append(LEVEL_E, tag, msg).also { android.util.Log.e(safeTag(tag), msg) }
    fun e(tag: String, msg: String, tr: Throwable?): Int = append(LEVEL_E, tag, msg + fmt(tr)).also { android.util.Log.e(safeTag(tag), msg, tr) }
    fun wtf(tag: String, msg: String): Int = append(LEVEL_F, tag, msg).also { android.util.Log.wtf(safeTag(tag), msg) }

    /**
     * Convenience: replaces bare `printStackTrace()` calls with a contextual
     * warning carrying the throwable through the whole pipeline.
     */
    fun st(tag: String, context: String, tr: Throwable?) {
        w(tag, "$context — ${tr?.javaClass?.simpleName}: ${tr?.message}", tr)
    }

    // ------------------------------------------------------------- accessors

    /** Snapshot of the ring buffer, oldest first. */
    fun snapshot(): List<Entry> = synchronized(bufferLock) { buffer.toList() }

    /** Entire buffer formatted logcat-style — used by the terminal Copy button. */
    fun snapshotFormatted(): String = synchronized(bufferLock) {
        val sb = StringBuilder(buffer.size * 80)
        for (e in buffer) sb.append(format(e)).append('\n')
        sb.toString()
    }

    fun clearMemoryBuffer() = synchronized(bufferLock) { buffer.clear() }

    /** Current on-disk session file (may be null before initialize). */
    fun currentLogFile(): File? = if (this::logDir.isInitialized) File(logDir, SESSION_FILE) takeIf { it.exists() } else null

    fun allLogFiles(): List<File> = if (this::logDir.isInitialized) logDir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList() else emptyList()

    // ------------------------------------------------------------ internals

    private const val TAG_INIT = "SLog"
    private const val SESSION_FILE = "session-current.log"

    private fun safeTag(tag: String): String = if (tag.length <= 23) tag else tag.substring(0, 23)

    private fun append(level: Char, rawTag: String, rawMsg: String): Int {
        val entry = Entry(System.currentTimeMillis(), level, rawTag, redact(rawMsg))
        synchronized(bufferLock) {
            if (buffer.size >= bufferCap) buffer.removeFirst()
            buffer.addLast(entry)
        }
        tailFlowInternal.tryEmit(entry)
        if (writerStarted.get()) {
            diskQueue.trySend(format(entry))
        }
        return 0
    }

    private fun fmt(tr: Throwable?): String =
        if (tr == null) "" else "\n${android.util.Log.getStackTraceString(tr)}"

    private fun format(e: Entry): String =
        "${tsFormat.format(Date(e.timeMs))} ${e.level}/${safeTag(e.tag)}: ${e.message}"

    /**
     * Redacts credentials before any persistence/display:
     *  - SAPISIDHASH <sha-token>
     *  - Cookie header values
     *  - googlevideo signature params
     */
    private fun redact(msg: String): String {
        if (!msg.contains("SAPISID") && !msg.contains("Cookie", ignoreCase = true) &&
            !msg.contains("signature=", ignoreCase = true)
        ) return msg
        var out = msg
        out = out.replace(Regex("(SAPISIDHASH\\s)[A-Za-z0-9_]+"), "$1<redacted>")
        out = out.replace(Regex("(?i)(cookie:\\s*?)[^\\n]{8,}"), "$1<redacted>")
        out = out.replace(Regex("(?i)((?:signature|sig|s)=)[^&\\s\"']+"), "$1<redacted>")
        return out
    }

    // -------------------------------------------------------------- disk io

    private fun writeToDisk(line: String) {
        try {
            val f = File(logDir, SESSION_FILE)
            if (f.exists() && f.length() > MAX_FILE_BYTES) rotate()
            f.appendText(line + "\n")
        } catch (_: Throwable) {
            // Never let logging kill the app.
        }
    }

    private fun rotate() {
        try {
            val oldest = File(logDir, "session-old-${ROTATE_KEEP}.log")
            if (oldest.exists()) oldest.delete()
            for (i in ROTATE_KEEP downTo 2) {
                val from = File(logDir, "session-old-${i - 1}.log")
                if (from.exists()) from.renameTo(File(logDir, "session-old-$i.log"))
            }
            File(logDir, SESSION_FILE).renameTo(File(logDir, "session-old-1.log"))
        } catch (_: Throwable) {
        }
    }

    /** Synchronous best-effort dump used by the crash hook (no coroutine hop). */
    private fun flushDiskQueueSync() {
        while (true) {
            val line = diskQueue.tryReceive().getOrNull() ?: break
            writeToDisk(line)
        }
    }

    // ---------------------------------------------------------- crash hook

    private fun installCrashHook() {
        val prior = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                append(LEVEL_F, "CRASH", "uncaught on thread=${t.name}: ${e.javaClass.name}: ${e.message}")
                append(LEVEL_F, "CRASH", android.util.Log.getStackTraceString(e))
                flushDiskQueueSync()
            } catch (_: Throwable) {
            }
            prior?.uncaughtException(t, e)
        }
    }
}
