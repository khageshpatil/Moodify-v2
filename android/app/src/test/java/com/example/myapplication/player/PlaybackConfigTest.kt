package com.moodify.app.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackConfigTest {
    @Test
    fun anEmptyConfigChangesNothing() {
        assertEquals(PlaybackConfig(), PlaybackConfig.parse("{}"))
    }

    @Test
    fun readsTheClientOrderAndTiming() {
        val config = PlaybackConfig.parse("""{"clients":["ANDROID_VR","VISIONOS"],"hedgeAfterMs":1200,"budgetMs":12000}""")!!
        assertEquals(listOf("ANDROID_VR", "VISIONOS"), config.clients)
        // perClientMs left out: the default stays.
        assertEquals(InnerTubeStreamResolver.Timing(hedgeAfterMs = 1_200, perClientMs = 5_000, budgetMs = 12_000), config.timing)
    }

    @Test
    fun ignoresWhatItCantUse() {
        val config = PlaybackConfig.parse("""{"clients":[1,"",null,"IOS"],"perClientMs":"soon","budgetMs":-3}""")!!
        assertEquals(listOf("IOS"), config.clients)
        assertEquals(InnerTubeStreamResolver.Timing(), config.timing)
        assertNull(PlaybackConfig.parse("not json"))
        assertNull(PlaybackConfig.parse(null))
    }
}
