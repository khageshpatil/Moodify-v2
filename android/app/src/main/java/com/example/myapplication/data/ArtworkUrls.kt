package com.moodify.app.data

/**
 * Rewrites YouTube / YouTube Music artwork URLs to request a specific size.
 *
 * InnerTube returns a list of thumbnails and the catalog parser keeps one of
 * them, which is often 60–226 px. Drawn full-width in the player that looks
 * pixelated. Google's image CDNs accept a size in the URL, so we ask for what
 * the UI will actually draw (rounded up to a small set of buckets so the
 * image cache gets reused across screens).
 */
object ArtworkUrls {
    private val BUCKETS = intArrayOf(120, 226, 360, 544, 720, 1080)

    // lh3.googleusercontent.com/...=w120-h120-l90-rj  (album/song art)
    // yt3.ggpht.com/...=s88-c-k-c0x00ffffff-no-rj     (artist avatars)
    private val GOOGLE_SIZE_SUFFIX = Regex("=(?:w\\d+-h\\d+|s\\d+)(?:-[A-Za-z0-9_-]*)?$")
    private val GOOGLE_HOSTS = listOf("googleusercontent.com", "ggpht.com")

    // i.ytimg.com/vi/<id>/<name>.jpg?sqp=...&rs=...  (video thumbnails)
    private val YTIMG = Regex("^https?://i\\d?\\.ytimg\\.com/vi(?:_webp)?/([A-Za-z0-9_-]{11})/[^/?#]+")

    /** Smallest bucket that is at least [px] wide; never exceeds the largest bucket. */
    fun bucket(px: Int): Int = BUCKETS.firstOrNull { it >= px } ?: BUCKETS.last()

    /**
     * Returns [url] rewritten to request roughly [px] square pixels, or [url]
     * unchanged when it is not a recognised Google artwork URL.
     */
    fun sized(url: String, px: Int): String {
        if (url.isBlank() || px <= 0) return url
        val size = bucket(px)
        val host = hostOf(url) ?: return url

        if (GOOGLE_HOSTS.any { host.endsWith(it) }) {
            val match = GOOGLE_SIZE_SUFFIX.find(url) ?: return url
            val isAvatar = match.value.startsWith("=s")
            // Artist avatars keep the circle-crop friendly "-c" square form.
            val replacement = if (isAvatar) "=s$size-c" else "=w$size-h$size-l90-rj"
            return url.substring(0, match.range.first) + replacement
        }

        YTIMG.find(url)?.let { match ->
            val videoId = match.groupValues[1]
            // hqdefault (480x360) always exists; sd/maxres can 404 for older
            // uploads. Signed query params are dropped because they are bound
            // to the original file name.
            val name = if (size <= 120) "mqdefault" else "hqdefault"
            return "https://i.ytimg.com/vi/$videoId/$name.jpg"
        }
        return url
    }

    private fun hostOf(url: String): String? {
        val start = url.indexOf("://").takeIf { it >= 0 }?.plus(3) ?: return null
        val end = url.indexOfAny(charArrayOf('/', '?', '#'), start).takeIf { it >= 0 } ?: url.length
        return url.substring(start, end).lowercase()
    }
}
