package com.example.myapplication.data

/** A single lyric line. A null start means the source is plain, not timed. */
data class LyricsLine(
    val text: String,
    val startMs: Long? = null,
    val endMs: Long? = null,
)

data class LyricsDocument(
    val lines: List<LyricsLine>,
    val source: String = "unknown",
    val isSynced: Boolean,
) {
    val plainText: String = lines.joinToString("\n") { it.text }
}

/**
 * Small, dependency-free parser for the formats Moodify receives from its
 * lyric providers. It accepts ordinary text and LRC-style line timestamps,
 * including multiple timestamps on one line.
 */
object LyricsParser {
    private val timestamp = Regex("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?\\]")
    private val metadata = Regex("\\[[a-zA-Z]+:[^]]*]\\s*")
    private val ttmlParagraph = Regex(
        "<p\\b([^>]*)>(.*?)</p>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val qrcToken = Regex("\\[(\\d+),(\\d+)]\\(([^)]*)\\)")
    private val ttmlBegin = Regex("\\bbegin\\s*=\\s*[\\\"']([^\\\"']+)[\\\"']", RegexOption.IGNORE_CASE)
    private val ttmlEnd = Regex("\\bend\\s*=\\s*[\\\"']([^\\\"']+)[\\\"']", RegexOption.IGNORE_CASE)

    fun parse(raw: String, source: String = "unknown", format: String? = null): LyricsDocument? {
        val input = raw.trim()
        if (input.isBlank()) return null

        if (format.equals("ttml", ignoreCase = true) || input.contains("<tt", ignoreCase = true)) {
            parseTtml(input, source)?.let { return it }
        }

        if (format.equals("qrc", ignoreCase = true) || qrcToken.containsMatchIn(input)) {
            parseQrc(input, source)?.let { return it }
        }

        val timedLines = buildList {
            input.lineSequence().forEach { line ->
                val matches = timestamp.findAll(line).toList()
                if (matches.isEmpty()) return@forEach
                val lyricText = line.substring(matches.last().range.last + 1).trim()
                if (lyricText.isBlank()) return@forEach
                matches.forEach { match ->
                    add(
                        LyricsLine(
                            text = lyricText,
                            startMs = timestampToMillis(match),
                        )
                    )
                }
            }
        }.sortedWith(compareBy<LyricsLine> { it.startMs }.thenBy { it.text })

        if (timedLines.isNotEmpty()) {
            val withEndTimes = timedLines.mapIndexed { index, line ->
                line.copy(endMs = timedLines.getOrNull(index + 1)?.startMs)
            }
            return LyricsDocument(withEndTimes, source, isSynced = true)
        }

        val plainLines = input.lineSequence()
            .map { metadata.replace(it, "").trim() }
            .filter { it.isNotBlank() }
            .map { LyricsLine(text = it) }
            .toList()
        return plainLines.takeIf { it.isNotEmpty() }?.let {
            LyricsDocument(it, source, isSynced = false)
        }
    }

    private fun parseTtml(input: String, source: String): LyricsDocument? {
        val lines = ttmlParagraph.findAll(input).mapNotNull { match ->
            val attributes = match.groupValues.getOrNull(1).orEmpty()
            val text = decodeEntities(stripTags(match.groupValues.getOrNull(2).orEmpty())).trim()
            if (text.isBlank()) return@mapNotNull null
            val start = ttmlBegin.find(attributes)?.groupValues?.getOrNull(1)?.let(::parseClock)
            val end = ttmlEnd.find(attributes)?.groupValues?.getOrNull(1)?.let(::parseClock)
            LyricsLine(text = text, startMs = start, endMs = end)
        }.toList()
        if (lines.isEmpty()) return null
        return LyricsDocument(
            lines = lines.sortedBy { it.startMs ?: Long.MAX_VALUE },
            source = source,
            isSynced = lines.any { it.startMs != null },
        )
    }

    private fun parseQrc(input: String, source: String): LyricsDocument? {
        val lines = input.lineSequence().flatMap { rawLine ->
            qrcToken.findAll(rawLine).map { match ->
                val start = match.groupValues[1].toLongOrNull() ?: 0L
                val duration = match.groupValues[2].toLongOrNull() ?: 0L
                LyricsLine(
                    text = decodeEntities(match.groupValues[3]),
                    startMs = start,
                    endMs = (start + duration).takeIf { duration > 0L },
                )
            }
        }.filter { it.text.isNotBlank() }.toList()
        return lines.takeIf { it.isNotEmpty() }?.let {
            LyricsDocument(it.sortedBy { line -> line.startMs }, source, isSynced = true)
        }
    }

    private fun parseClock(value: String): Long? {
        val normalized = value.trim()
        normalized.toLongOrNull()?.let { return it }
        val parts = normalized.removeSuffix("s").split(":")
        if (parts.size == 3) {
            val hours = parts[0].toDoubleOrNull() ?: return null
            val minutes = parts[1].toDoubleOrNull() ?: return null
            val seconds = parts[2].toDoubleOrNull() ?: return null
            return ((hours * 3_600_000) + (minutes * 60_000) + (seconds * 1_000)).toLong()
        }
        return normalized.removeSuffix("ms").toDoubleOrNull()?.toLong()
    }

    private fun stripTags(value: String): String = value.replace(Regex("<[^>]+>"), "")

    private fun decodeEntities(value: String): String = value
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")

    private fun timestampToMillis(match: MatchResult): Long {
        val minutes = match.groupValues[1].toLong()
        val seconds = match.groupValues[2].toLong()
        val fraction = match.groupValues[3]
        val fractionMs = when (fraction.length) {
            1 -> fraction.toLong() * 100L
            2 -> fraction.toLong() * 10L
            else -> fraction.toLongOrNull() ?: 0L
        }
        return (minutes * 60_000L) + (seconds * 1_000L) + fractionMs
    }
}
