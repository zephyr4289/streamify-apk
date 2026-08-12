package com.streamify.app.data.models

data class LyricsLine(
    val timeMs: Long,
    val text: String
)

data class LyricsData(
    val lines: List<LyricsLine>
) {
    companion object {
        fun parseLrc(lrcContent: String): LyricsData {
            val lines = lrcContent.split("\n")
            val parsedLines = mutableListOf<LyricsLine>()
            val timeRegex = Regex("\\[(\\d{2}):(\\d{2})\\.(\\d{2,3})\\]")

            for (line in lines) {
                val matchResult = timeRegex.find(line)
                if (matchResult != null) {
                    val (min, sec, msStr) = matchResult.destructured
                    val ms = if (msStr.length == 2) msStr.toLong() * 10 else msStr.toLong()
                    val timeMs = (min.toLong() * 60 * 1000) + (sec.toLong() * 1000) + ms
                    val text = line.substring(matchResult.range.last + 1).trim()
                    if (text.isNotEmpty()) {
                        parsedLines.add(LyricsLine(timeMs, text))
                    }
                }
            }
            return LyricsData(parsedLines)
        }
    }
}
