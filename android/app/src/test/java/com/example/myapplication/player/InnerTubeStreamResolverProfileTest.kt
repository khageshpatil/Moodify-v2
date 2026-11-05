package com.moodify.app.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InnerTubeStreamResolverProfileTest {
    private val resolver = InnerTubeStreamResolver()

    @Test
    fun nextProfileOffsetSkipsTheRejectedClient() {
        assertEquals(2, resolver.nextProfileOffset("ANDROID_VR"))
    }

    @Test
    fun validatedDirectAudioClientIsTheFirstPlaybackAttempt() {
        assertEquals(1, resolver.nextProfileOffset("VISIONOS"))
    }

    @Test
    fun nextProfileOffsetReturnsEndAfterLastClient() {
        assertEquals(resolver.profileCount(), resolver.nextProfileOffset("TVHTML5_SIMPLY_EMBEDDED_PLAYER"))
    }

    @Test
    fun nextProfileOffsetRejectsUnknownClient() {
        assertNull(resolver.nextProfileOffset("UNKNOWN"))
    }

    @Test
    fun parsesVisitorDataFromXssiPrefixedServiceWorkerPayload() {
        val visitorData = "CgtMoodifyVisitorTestData_0123456789abcdef"
        val payload = ")]}'\n[\"yt.sw.adr\",null,[[\"en-US\",\"IN\",\"$visitorData\"]]]"

        assertEquals(visitorData, InnerTubeStreamResolver.parseVisitorDataFromSwJsData(payload))
    }

    @Test
    fun malformedServiceWorkerPayloadHasNoVisitorData() {
        assertNull(InnerTubeStreamResolver.parseVisitorDataFromSwJsData("not-json"))
    }
}
