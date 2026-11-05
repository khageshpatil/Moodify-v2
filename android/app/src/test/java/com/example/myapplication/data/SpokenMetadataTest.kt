package com.moodify.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpokenMetadataTest {
    @Test
    fun spokenDurationsAndDatesAreNotNames() {
        assertTrue(isSpokenDurationOrDate("3 minutes, 49 seconds"))
        assertTrue(isSpokenDurationOrDate("1 hour, 2 minutes"))
        assertTrue(isSpokenDurationOrDate("45 seconds"))
        assertTrue(isSpokenDurationOrDate("May 31, 2026"))
        assertTrue(isSpokenDurationOrDate("Jan 29, 2024"))
    }

    @Test
    fun realNamesPass() {
        assertFalse(isSpokenDurationOrDate("Arijit Singh"))
        assertFalse(isSpokenDurationOrDate("Madhubanti Bagchi, Jasmine Sandlas"))
        assertFalse(isSpokenDurationOrDate("May"))
        assertFalse(isSpokenDurationOrDate("Maroon 5"))
        assertFalse(isSpokenDurationOrDate("Seconds Before"))
    }
}
