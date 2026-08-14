package com.streamify.app.data.models

data class Syllable(
    val text: String,
    val startMs: Long,
    val endMs: Long
)

data class LyricsLine(
    val timeMs: Long,
    val text: String,
    val syllables: List<Syllable> = emptyList()
)

data class LyricsData(
    val lines: List<LyricsLine>
) {
    companion object {
        private val lineRegex = Regex("""\[(\d{2}):(\d{2})\.(\d{2,3})\](.*)""")
        private val wordRegex = Regex("""<(\d{2}):(\d{2})\.(\d{2,3})>([^<\[]*)""")

        fun parseLrc(lrcContent: String): LyricsData {
            val rawLines = lrcContent.split("\n")
            val parsedLines = mutableListOf<LyricsLine>()

            for (raw in rawLines) {
                val lineMatch = lineRegex.find(raw.trim()) ?: continue
                val (min, sec, msStr) = lineMatch.destructured
                val ms = if (msStr.length == 2) msStr.toLong() * 10 else msStr.toLong()
                val lineStartMs = (min.toLong() * 60 * 1000) + (sec.toLong() * 1000) + ms

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
                        val wStartMs = (wMin.toLong() * 60 * 1000) + (wSec.toLong() * 1000) + wMs
                        val wordText = wm.groupValues[4]
                        plainText += wordText

                        val nextStartMs = if (i < wordMatches.size - 1) {
                            val nextWm = wordMatches[i + 1]
                            val (nMin, nSec, nMsStr) = nextWm.destructured
                            val nMs = if (nMsStr.length == 2) nMsStr.toLong() * 10 else nMsStr.toLong()
                            (nMin.toLong() * 60 * 1000) + (nSec.toLong() * 1000) + nMs
                        } else {
                            wStartMs + 4000L
                        }

                        syllables.add(Syllable(wordText, wStartMs, nextStartMs))
                    }
                    parsedLines.add(LyricsLine(lineStartMs, plainText.trim(), syllables))
                } else {
                    // Standard line-level LRC without embedded syllable timestamps
                    parsedLines.add(LyricsLine(lineStartMs, content, emptyList()))
                }
            }

            // Post-process line durations and fallback syllables
            for (i in parsedLines.indices) {
                val line = parsedLines[i]
                val nextLineTimeMs = if (i < parsedLines.size - 1) parsedLines[i + 1].timeMs else line.timeMs + 5000L
                if (line.syllables.isEmpty()) {
                    // Treat whole line as one single syllable spanning line duration
                    val singleSyllable = listOf(Syllable(line.text, line.timeMs, nextLineTimeMs))
                    parsedLines[i] = line.copy(syllables = singleSyllable)
                }
            }

            return LyricsData(parsedLines)
        }
    }
}
