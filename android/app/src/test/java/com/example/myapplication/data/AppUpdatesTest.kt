package com.moodify.app.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppUpdatesTest {
    private fun release(tag: String, assets: String = """[{"name":"Moodify.apk","browser_download_url":"https://github.com/x/releases/download/$tag/Moodify.apk"}]""", body: String = "") =
        JSONObject("""{"tag_name":"$tag","draft":false,"prerelease":false,"assets":$assets,"body":${JSONObject.quote(body)}}""")

    @Test
    fun readsTheVersionAndApkFromAGitHubRelease() {
        val r = AppUpdates.parseRelease(release("android-v1.1.17"))!!
        assertEquals(17, r.versionCode)
        assertEquals("1.1.17", r.versionName)
        assertEquals("https://github.com/x/releases/download/android-v1.1.17/Moodify.apk", r.apkUrl)
    }

    @Test
    fun ignoresWhatIsntAnAndroidBuild() {
        assertNull(AppUpdates.parseRelease(release("v2.0.0")))
        assertNull(AppUpdates.parseRelease(release("android-v1.1.17", assets = "[]")))
        assertNull(AppUpdates.parseRelease(JSONObject("""{"tag_name":"android-v1.1.18","draft":true,"assets":[]}""")))
    }

    @Test
    fun keepsAFewReadableNoteLines() {
        val body = """
            ## What's Changed
            * Polish Friends; share your playlists by @khageshpatil in https://github.com/khageshpatil/Moodify-v2/pull/9
            * CI APKs use the live social server by @khageshpatil in https://github.com/khageshpatil/Moodify-v2/pull/8


            **Full Changelog**: https://github.com/khageshpatil/Moodify-v2/compare/a...b
        """.trimIndent()
        assertEquals(listOf("Polish Friends; share your playlists", "CI APKs use the live social server"), AppUpdates.notesFrom(body))
    }
}
