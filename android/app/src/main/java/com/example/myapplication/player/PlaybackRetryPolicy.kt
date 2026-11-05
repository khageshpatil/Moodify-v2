package com.moodify.app.player

/** Chooses bounded recovery for signed CDN URLs rejected after stream resolution. */
internal object PlaybackRetryPolicy {
    const val EXPIRY_RETRY_WINDOW_MS = 45_000L

    fun profileOffsetAfterFailure(
        httpStatusCode: Int?,
        contractExpiresAtMs: Long?,
        nowMs: Long,
        priorRetryCount: Int,
        currentProfileOffset: Int?,
        nextProfileOffset: Int?,
        fallbackOffset: Int,
    ): Int {
        val contractNearExpiry = contractExpiresAtMs?.let {
            it <= nowMs + EXPIRY_RETRY_WINDOW_MS
        } == true
        val shouldRefreshSameProfile = priorRetryCount == 0 &&
            (httpStatusCode == HTTP_GONE || contractNearExpiry)

        return if (shouldRefreshSameProfile) {
            currentProfileOffset ?: fallbackOffset
        } else {
            nextProfileOffset ?: fallbackOffset
        }
    }

    private const val HTTP_GONE = 410
}
