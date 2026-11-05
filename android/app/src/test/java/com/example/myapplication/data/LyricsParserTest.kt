package com.moodify.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsParserTest {
    @Test
    fun parsesLineSyncedLrcAndCalculatesEndTimes() {
        val document = LyricsParser.parse(
            "[00:01.00]First line\n[00:03.50]Second line",
            source = "lrclib",
        )

        requireNotNull(document)
        assertTrue(document.isSynced)
        assertEquals(2, document.lines.size)
        assertEquals(1_000L, document.lines[0].startMs)
        assertEquals(3_500L, document.lines[0].endMs)
        assertEquals("Second line", document.lines[1].text)
    }

    @Test
    fun keepsPlainLyricsReadableAndUnsynced() {
        val document = LyricsParser.parse("First line\n\nSecond line", source = "youtube-music")

        requireNotNull(document)
        assertTrue(!document.isSynced)
        assertEquals(listOf("First line", "Second line"), document.lines.map { it.text })
    }

    @Test
    fun parsesTtmlClockTimesAndStripsMarkup() {
        val document = LyricsParser.parse(
            "<tt><body><p begin=\"00:00:01.250\" end=\"00:00:03.000\">&amp; hello</p></body></tt>",
            source = "provider",
            format = "ttml",
        )

        requireNotNull(document)
        assertTrue(document.isSynced)
        assertEquals("& hello", document.lines.single().text)
        assertEquals(1_250L, document.lines.single().startMs)
        assertEquals(3_000L, document.lines.single().endMs)
    }

    @Test
    fun preservesTtmlRomanizationAndTranslationTracks() {
        val document = LyricsParser.parse(
            """<tt><body><p begin="00:00:01.250"><span>नमस्ते</span><span ttm:role="x-roman"><span>Nam</span><span>aste</span></span><span ttm:role="x-translation">Hello</span></p></body></tt>""",
            source = "BetterLyrics",
            format = "ttml",
        )

        requireNotNull(document)
        assertEquals("नमस्ते", document.lines.single().text)
        assertEquals("Namaste", document.lines.single().romanizedText)
        assertEquals("Hello", document.lines.single().translation)
        assertEquals(1_250L, document.lines.single().startMs)
    }

    @Test
    fun parsesQrcWordTiming() {
        val document = LyricsParser.parse(
            "[100,500](Hello)[600,400](world)",
            source = "qrc",
            format = "qrc",
        )

        requireNotNull(document)
        assertTrue(document.isSynced)
        assertEquals(listOf("Hello", "world"), document.lines.map { it.text })
        assertEquals(600L, document.lines[1].startMs)
        assertEquals(1_000L, document.lines[1].endMs)
    }
}
