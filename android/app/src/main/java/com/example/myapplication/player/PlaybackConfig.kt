package com.moodify.app.player

import org.json.JSONObject

/**
 * Playback settings from the social server (docs/social-protocol/playback.md): the order to
 * ask YouTube clients in, and how long to wait. Lets a YouTube change be answered without
 * a new APK. Anything missing or malformed keeps the app's own default.
 */
internal data class PlaybackConfig(
    val clients: List<String>? = null,
    val timing: InnerTubeStreamResolver.Timing? = null,
) {
    companion object {
        private val TIMING_KEYS = listOf("hedgeAfterMs", "perClientMs", "budgetMs")

        /** The config in [json], or null when it isn't a JSON object. */
        fun parse(json: String?): PlaybackConfig? {
            val root = runCatching { JSONObject(json.orEmpty()) }.getOrNull() ?: return null
            val clients = root.optJSONArray("clients")?.let { array ->
                (0 until array.length()).mapNotNull { array.opt(it) as? String }.filter(String::isNotBlank)
            }?.takeIf { it.isNotEmpty() }
            val defaults = InnerTubeStreamResolver.Timing()
            val timing = if (TIMING_KEYS.none(root::has)) null else InnerTubeStreamResolver.Timing(
                hedgeAfterMs = root.positiveLong("hedgeAfterMs") ?: defaults.hedgeAfterMs,
                perClientMs = root.positiveLong("perClientMs") ?: defaults.perClientMs,
                budgetMs = root.positiveLong("budgetMs") ?: defaults.budgetMs,
            )
            return PlaybackConfig(clients, timing)
        }

        private fun JSONObject.positiveLong(key: String): Long? =
            (opt(key) as? Number)?.toLong()?.takeIf { it > 0 }
    }
}
