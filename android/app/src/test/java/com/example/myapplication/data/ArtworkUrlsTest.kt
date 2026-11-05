package com.moodify.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ArtworkUrlsTest {
    @Test
    fun albumArtRequestsTheBucketedSquareSize() {
        val url = "https://lh3.googleusercontent.com/abc123=w120-h120-l90-rj"
        assertEquals(
            "https://lh3.googleusercontent.com/abc123=w1080-h1080-l90-rj",
            ArtworkUrls.sized(url, 900),
        )
    }

    @Test
    fun artistAvatarKeepsSquareCropForm() {
        val url = "https://yt3.ggpht.com/xyz=s88-c-k-c0x00ffffff-no-rj"
        assertEquals("https://yt3.ggpht.com/xyz=s226-c", ArtworkUrls.sized(url, 200))
    }

    @Test
    fun ytimgThumbnailDropsSignedQueryAndUsesHq() {
        val url = "https://i.ytimg.com/vi/dQw4w9WgXcQ/sddefault.jpg?sqp=-oaymwEWCJADEOEB&rs=AOn4CLB"
        assertEquals("https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg", ArtworkUrls.sized(url, 720))
    }

    @Test
    fun smallYtimgUsesMediumQuality() {
        val url = "https://i.ytimg.com/vi_webp/dQw4w9WgXcQ/default.webp"
        assertEquals("https://i.ytimg.com/vi/dQw4w9WgXcQ/mqdefault.jpg", ArtworkUrls.sized(url, 96))
    }

    @Test
    fun unknownHostsAndBlankUrlsAreUntouched() {
        assertEquals("https://example.com/a.jpg", ArtworkUrls.sized("https://example.com/a.jpg", 500))
        assertEquals("", ArtworkUrls.sized("", 500))
        val noSuffix = "https://lh3.googleusercontent.com/plainimage"
        assertEquals(noSuffix, ArtworkUrls.sized(noSuffix, 500))
    }

    @Test
    fun bucketCapsAtLargestSize() {
        assertEquals(120, ArtworkUrls.bucket(1))
        assertEquals(360, ArtworkUrls.bucket(300))
        assertEquals(1080, ArtworkUrls.bucket(4000))
    }
}
