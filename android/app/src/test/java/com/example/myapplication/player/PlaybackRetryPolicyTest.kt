package com.moodify.app.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackRetryPolicyTest {
    @Test
    fun firstHttp410RefreshesTheSameProfile() {
        assertEquals(
            6,
            PlaybackRetryPolicy.profileOffsetAfterFailure(
                httpStatusCode = 410,
                contractExpiresAtMs = 900_000L,
                nowMs = 100_000L,
                priorRetryCount = 0,
                currentProfileOffset = 6,
                nextProfileOffset = 7,
                fallbackOffset = 1,
            ),
        )
    }

    @Test
    fun aSecondExpiredUrlFailureRotatesToTheNextProfile() {
        assertEquals(
            7,
            PlaybackRetryPolicy.profileOffsetAfterFailure(
                httpStatusCode = 410,
                contractExpiresAtMs = 900_000L,
                nowMs = 100_000L,
                priorRetryCount = 1,
                currentProfileOffset = 6,
                nextProfileOffset = 7,
                fallbackOffset = 2,
            ),
        )
    }

    @Test
    fun firstFailureNearKnownExpiryRefreshesTheSameProfile() {
        assertEquals(
            3,
            PlaybackRetryPolicy.profileOffsetAfterFailure(
                httpStatusCode = 403,
                contractExpiresAtMs = 140_000L,
                nowMs = 100_000L,
                priorRetryCount = 0,
                currentProfileOffset = 3,
                nextProfileOffset = 4,
                fallbackOffset = 1,
            ),
        )
    }

    @Test
    fun ordinary403RotatesImmediately() {
        assertEquals(
            4,
            PlaybackRetryPolicy.profileOffsetAfterFailure(
                httpStatusCode = 403,
                contractExpiresAtMs = 900_000L,
                nowMs = 100_000L,
                priorRetryCount = 0,
                currentProfileOffset = 3,
                nextProfileOffset = 4,
                fallbackOffset = 1,
            ),
        )
    }
}
