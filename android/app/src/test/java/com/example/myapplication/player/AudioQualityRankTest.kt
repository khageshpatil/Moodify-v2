package com.moodify.app.player

import com.moodify.app.data.AudioQuality
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioQualityRankTest {
    /** Formats a typical song offers: itag to MIME type. */
    private val offered = listOf(139 to "audio/mp4", 140 to "audio/mp4", 249 to "audio/webm", 250 to "audio/webm", 251 to "audio/webm")

    private fun pick(quality: AudioQuality, formats: List<Pair<Int, String>> = offered) =
        formats.maxBy { (itag, mime) -> InnerTubeStreamResolver.formatRank(itag, mime, quality) }.first

    @Test
    fun dataSaverPicksTheSmallestOpusStream() = assertEquals(249, pick(AudioQuality.LOW))

    @Test
    fun normalPicksTheMiddleOpusStream() = assertEquals(250, pick(AudioQuality.NORMAL))

    @Test
    fun highPicksTheBestStreamOffered() {
        assertEquals(251, pick(AudioQuality.HIGH))
        assertEquals(774, pick(AudioQuality.HIGH, offered + (774 to "audio/webm")))
    }

    @Test
    fun eachLevelFallsBackSensiblyWhenItsFirstChoiceIsMissing() {
        val noOpus = listOf(139 to "audio/mp4", 140 to "audio/mp4")
        assertEquals(139, pick(AudioQuality.LOW, noOpus))
        assertEquals(140, pick(AudioQuality.NORMAL, noOpus))
        assertEquals(140, pick(AudioQuality.HIGH, noOpus))
        assertEquals(251, pick(AudioQuality.NORMAL, listOf(251 to "audio/webm", 140 to "audio/mp4")))
    }

    @Test
    fun aPartlySavedCopyKeepsItsFormatWhenOffered() {
        val rank = { itag: Int, mime: String -> InnerTubeStreamResolver.formatRank(itag, mime, AudioQuality.LOW, keepItag = 251) }
        assertEquals(251, offered.maxBy { (itag, mime) -> rank(itag, mime) }.first)
        // The saved format is gone (another client): fall back to the chosen quality.
        assertEquals(249, offered.filter { it.first != 251 }.maxBy { (itag, mime) -> rank(itag, mime) }.first)
    }

    @Test
    fun unknownFormatsStillRankBelowKnownOnesButAreKept() {
        val unknown = listOf(999 to "audio/webm", 998 to "audio/mp4")
        assertEquals(999, pick(AudioQuality.HIGH, unknown))
        assertEquals(251, pick(AudioQuality.LOW, unknown + (251 to "audio/webm")))
    }
}
