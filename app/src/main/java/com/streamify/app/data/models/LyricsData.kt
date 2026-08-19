package com.streamify.app.data.models

data class Syllable(
    val text: String,
    val startMs: Long,
    val endMs: Long
)

data class LyricsLine(
    val timeMs: Long,
    val text: String,
    val syllables: List<Syllable> = emptyList(),
    val durationMs: Long = 0L
)

data class LyricsData(
    val lines: List<LyricsLine>,
    val isSynced: Boolean = true,
    val globalOffsetMs: Long = 0L
) {
    companion object {
        private val offsetRegex = Regex("""\[offset:\s*(-?\d+)\]""", RegexOption.IGNORE_CASE)
        private val lineRegex = Regex("""\[(\d{2}):(\d{2})\.(\d{2,3})\](.*)""")
        private val wordRegex = Regex("""<(\d{2}):(\d{2})\.(\d{2,3})>([^<\[]*)""")

        fun parseLrc(lrcContent: String): LyricsData {
            if (lrcContent.isBlank()) return LyricsData(emptyList(), isSynced = false)

            // 1. Extract Global Header Offset
            var offsetMs = 0L
            offsetRegex.find(lrcContent)?.let {
                offsetMs = it.groupValues[1].toLongOrNull() ?: 0L
            }

            val rawLines = lrcContent.split("\n")
            val syncedLines = mutableListOf<LyricsLine>()
            val unsyncedLines = mutableListOf<LyricsLine>()

            for (raw in rawLines) {
                val trimmed = raw.trim()
                if (trimmed.isEmpty()) continue

                val lineMatch = lineRegex.find(trimmed)
                if (lineMatch != null) {
                    val (min, sec, msStr) = lineMatch.destructured
                    val ms = if (msStr.length == 2) msStr.toLong() * 10 else msStr.toLong()
                    val lineStartMs = (min.toLong() * 60 * 1000) + (sec.toLong() * 1000) + ms + offsetMs

                    val content = lineMatch.groupValues[4].trim()
                    if (content.isEmpty()) continue

                    val syllables = mutableListOf<Syllable>()
                    val wordMatches = wordRegex.findAll(content).toList()

                    if (wordMatches.isNotEmpty()) {
                        var plainText = ""
                        for (i in wordMatches.indices) {
                            val wm = wordMatches[i]
                            val (wMin, wSec, wMsStr) = wm.destructured
                            val wMs = if (wMsStr.length == 2) wMsStr.toLong() * 10 else wMsStr.toLong()
                            val wStartMs = (wMin.toLong() * 60 * 1000) + (wSec.toLong() * 1000) + wMs + offsetMs
                            val wordText = wm.groupValues[4]
                            plainText += wordText

                            val nextStartMs = if (i < wordMatches.size - 1) {
                                val nextWm = wordMatches[i + 1]
                                val (nMin, nSec, nMsStr) = nextWm.destructured
                                val nMs = if (nMsStr.length == 2) nMsStr.toLong() * 10 else nMsStr.toLong()
                                (nMin.toLong() * 60 * 1000) + (nSec.toLong() * 1000) + nMs + offsetMs
                            } else {
                                wStartMs + 3500L
                            }

                            syllables.add(Syllable(wordText, wStartMs, nextStartMs))
                        }
                        syncedLines.add(LyricsLine(lineStartMs, plainText.trim(), syllables))
                    } else {
                        // Standard line-level LRC without embedded syllable timestamps
                        syncedLines.add(LyricsLine(lineStartMs, content, emptyList()))
                    }
                } else if (!trimmed.startsWith("[") && trimmed.isNotBlank()) {
                    // Plain unsynced text line (Never discard!)
                    unsyncedLines.add(LyricsLine(timeMs = 0L, text = trimmed, syllables = emptyList()))
                }
            }

            if (syncedLines.isNotEmpty()) {
                val sorted = syncedLines.sortedBy { it.timeMs }.toMutableList()
                // Compute true inter-line duration calculus
                for (i in sorted.indices) {
                    val line = sorted[i]
                    val duration = if (i < sorted.size - 1) {
                        (sorted[i + 1].timeMs - line.timeMs).coerceAtLeast(1200L)
                    } else {
                        4000L
                    }
                    val nextLineTimeMs = line.timeMs + duration
                    val finalSyllables = if (line.syllables.isEmpty()) {
                        listOf(Syllable(line.text, line.timeMs, nextLineTimeMs))
                    } else {
                        line.syllables
                    }
                    sorted[i] = line.copy(durationMs = duration, syllables = finalSyllables)
                }
                return LyricsData(lines = sorted, isSynced = true, globalOffsetMs = offsetMs)
            } else if (unsyncedLines.isNotEmpty()) {
                return LyricsData(lines = unsyncedLines, isSynced = false, globalOffsetMs = 0L)
            }

            return LyricsData(emptyList(), isSynced = false)
        }

        fun shiftTimestamps(lines: List<LyricsLine>, offsetMs: Long): List<LyricsLine> {
            if (offsetMs == 0L) return lines
            return lines.map { line ->
                val adjustedLineStart = (line.timeMs + offsetMs).coerceAtLeast(0L)
                val shiftedSyllables = line.syllables.map { syl ->
                    syl.copy(
                        startMs = (syl.startMs + offsetMs).coerceAtLeast(0L),
                        endMs = (syl.endMs + offsetMs).coerceAtLeast(0L)
                    )
                }
                line.copy(
                    timeMs = adjustedLineStart,
                    syllables = shiftedSyllables
                )
            }
        }

        fun formatLrc(lines: List<LyricsLine>, offsetMs: Long = 0L): String {
            val sb = StringBuilder()
            for (line in lines) {
                val adjustedMs = (line.timeMs + offsetMs).coerceAtLeast(0L)
                val min = (adjustedMs / 60000).toInt()
                val sec = ((adjustedMs % 60000) / 1000).toInt()
                val ms = ((adjustedMs % 1000) / 10).toInt()
                if (line.syllables.isNotEmpty()) {
                    sb.append(String.format(java.util.Locale.US, "[%02d:%02d.%02d]", min, sec, ms))
                    for (syl in line.syllables) {
                        val sMs = (syl.startMs + offsetMs).coerceAtLeast(0L)
                        val sMin = (sMs / 60000).toInt()
                        val sSec = ((sMs % 60000) / 1000).toInt()
                        val sMillis = ((sMs % 1000) / 10).toInt()
                        sb.append(String.format(java.util.Locale.US, "<%02d:%02d.%02d>%s", sMin, sSec, sMillis, syl.text))
                    }
                    sb.append("\n")
                } else {
                    sb.append(String.format(java.util.Locale.US, "[%02d:%02d.%02d]%s\n", min, sec, ms, line.text))
                }
            }
            return sb.toString().trim()
        }
    }
}

