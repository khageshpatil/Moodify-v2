package com.moodify.app.data

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaType
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class InnerTubeCatalogLocaleContractTest {
    @Test
    fun chartsRequestCarriesSelectedLocaleAndArchiveTuneBrowseContract() = runBlocking {
        val fixture = """
            {
              "contents": {"sectionListRenderer": {"contents": [{
                "musicShelfRenderer": {
                  "title": {"runs": [{"text": "Charts"}]},
                  "contents": [{"musicResponsiveListItemRenderer": {
                    "playlistItemData": {"videoId": "chart-track"},
                    "flexColumns": [
                      {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Song"}]}}},
                      {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Artist"}]}}}
                    ]
                  }}]
                }
              }]}}
            }
        """.trimIndent()
        val locales = listOf(CatalogLocale("en", "US"), CatalogLocale("en", "IN"))

        locales.forEach { locale ->
            val capturedRequest = AtomicReference<okhttp3.Request>()
            val capturedBody = AtomicReference<JSONObject>()
            val client = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    capturedRequest.set(request)
                    val buffer = Buffer()
                    request.body?.writeTo(buffer)
                    capturedBody.set(JSONObject(buffer.readUtf8()))
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(fixture.toResponseBody("application/json".toMediaType()))
                        .build()
                }
                .build()

            val page = InnerTubeCatalogClient(client) { locale }.charts()
            val request = requireNotNull(capturedRequest.get())
            val body = requireNotNull(capturedBody.get())
            val contextClient = requireNotNull(
                body.optJSONObject("context")?.optJSONObject("client"),
            )

            assertEquals("charts", page.surface)
            assertNotNull(page.sections.single().items.single().track)
            assertEquals(locale.region, contextClient.optString("gl"))
            assertEquals(locale.language, contextClient.optString("hl"))
            assertEquals("FEmusic_charts", body.optString("browseId"))
            assertEquals("ggMGCgQIgAQ%3D", body.optString("params"))
            assertEquals(locale.acceptLanguage, request.header("Accept-Language"))
        }
    }
}
