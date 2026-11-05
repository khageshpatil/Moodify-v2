package com.moodify.app.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InnerTubeCatalogLiveTest {
    @Volatile
    private var lastInitialBrowseShape = "not captured"

    @Volatile
    private var lastContinuationShape = "not captured"

    private fun client(): InnerTubeCatalogClient {
        val httpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                val requestBody = Buffer().apply { request.body?.writeTo(this) }.readUtf8()
                val response = chain.proceed(request)
                if (request.url.encodedPath.endsWith("/browse") &&
                    requestBody.contains("\"continuation\"")
                ) {
                    val root = JSONObject(response.peekBody(8L * 1024 * 1024).string())
                    lastContinuationShape = continuationShape(root)
                } else if (request.url.encodedPath.endsWith("/browse") &&
                    requestBody.contains("\"browseId\"")
                ) {
                    val root = JSONObject(response.peekBody(8L * 1024 * 1024).string())
                    lastInitialBrowseShape = initialBrowseShape(root)
                }
                response
            }
            .build()
        return InnerTubeCatalogClient(
            httpClient = httpClient,
            localeProvider = { CatalogLocale(language = "en", region = "IN") },
        )
    }

    private fun continuationShape(root: JSONObject): String {
        val continuationContents = root.optJSONObject("continuationContents")
        val continuationKeys = continuationContents?.keys()?.asSequence()?.toList().orEmpty()
        val actions = root.optJSONArray("onResponseReceivedActions")
        val appendActions = (0 until (actions?.length() ?: 0)).mapNotNull { index ->
            actions?.optJSONObject(index)?.optJSONObject("appendContinuationItemsAction")
        }
        val appendedItems = appendActions.sumOf { it.optJSONArray("continuationItems")?.length() ?: 0 }
        val firstAppendedKeys = appendActions.firstOrNull()
            ?.optJSONArray("continuationItems")
            ?.optJSONObject(0)
            ?.keys()
            ?.asSequence()
            ?.toList()
            .orEmpty()
        return "continuationContents=$continuationKeys, appendActions=${appendActions.size}, " +
            "appendedItems=$appendedItems, firstAppendedKeys=$firstAppendedKeys"
    }

    private fun initialBrowseShape(root: JSONObject): String {
        val shelves = mutableListOf<String>()
        fun collect(value: Any?, path: String) {
            when (value) {
                is org.json.JSONArray -> for (index in 0 until value.length()) {
                    collect(value.opt(index), "$path[$index]")
                }
                is JSONObject -> {
                    val keys = value.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val child = value.opt(key)
                        if (key == "musicPlaylistShelfRenderer" || key == "musicShelfRenderer") {
                            val shelf = child as? JSONObject
                            val contents = shelf?.optJSONArray("contents")
                            val lastRowKeys = contents
                                ?.optJSONObject((contents.length() - 1).coerceAtLeast(0))
                                ?.keys()
                                ?.asSequence()
                                ?.toList()
                                .orEmpty()
                            shelves += "$path/$key rows=${contents?.length() ?: 0} " +
                                "hasContinuations=${(shelf?.optJSONArray("continuations")?.length() ?: 0) > 0} " +
                                "lastRowKeys=$lastRowKeys"
                        }
                        collect(child, "$path/$key")
                    }
                }
            }
        }
        collect(root, "root")
        return shelves.take(20).joinToString(" | ").ifBlank { "no music shelf renderers" }
    }

    private fun requireLiveCatalogFlag() {
        assumeTrue(
            "Pass moodifyLiveYouTubeCatalog=true to enable live catalog requests",
            InstrumentationRegistry.getArguments().getString("moodifyLiveYouTubeCatalog") == "true",
        )
    }

    @Test
    fun liveHomeExploreAndMoodsPagesHaveRenderableSections() = runBlocking {
        requireLiveCatalogFlag()
        val catalog = client()

        val pages = listOf(
            "home" to catalog.home(),
            "explore" to catalog.explore(),
            "moods" to catalog.moodsAndGenres(),
        )

        pages.forEach { (surface, page) ->
            assertTrue("$surface should contain sections", page.sections.isNotEmpty())
            val hasTitledItem = page.sections.any { section ->
                section.title.isNotBlank() && section.items.any { it.title.isNotBlank() }
            }
            assertTrue(
                "$surface should contain at least one titled item",
                hasTitledItem,
            )
        }
    }

    @Test
    fun liveTypedArtistAndAlbumSearchOpenNativeDetails() = runBlocking {
        requireLiveCatalogFlag()
        val catalog = client()

        val artistResults = catalog.search("Adele", type = "artists")
        val artist = artistResults.sections
            .flatMap { it.items }
            .firstOrNull { it.type == "artist" && !it.endpoint?.browseId.isNullOrBlank() }
        assertTrue("Artist-filtered search should return a browseable artist", artist != null)
        val artistEndpoint = requireNotNull(artist?.endpoint)
        val artistPage = catalog.browse(
            browseId = requireNotNull(artistEndpoint.browseId),
            params = artistEndpoint.params,
            surface = "artist",
            title = requireNotNull(artist).title,
        )
        assertTrue("Artist browse should return sections", artistPage.sections.isNotEmpty())
        assertTrue(
            "Artist browse should include music items",
            artistPage.sections.any { it.items.isNotEmpty() },
        )

        val albumResults = catalog.search("Adele 25", type = "albums")
        val album = albumResults.sections
            .flatMap { it.items }
            .firstOrNull { it.type == "album" && !it.endpoint?.browseId.isNullOrBlank() }
        assertTrue("Album-filtered search should return a browseable album", album != null)
        val albumEndpoint = requireNotNull(album?.endpoint)
        val albumPage = catalog.browse(
            browseId = requireNotNull(albumEndpoint.browseId),
            params = albumEndpoint.params,
            surface = "album",
            title = requireNotNull(album).title,
        )
        assertTrue("Album browse should return sections", albumPage.sections.isNotEmpty())
        assertTrue(
            "Album browse should include track rows",
            albumPage.sections.any { section -> section.items.any { it.track != null } },
        )
    }

    @Test
    fun livePlaylistBrowseFollowsARealContinuation() = runBlocking {
        requireLiveCatalogFlag()
        val catalog = client()
        val query = "lofi hip hop beats"
        val results = catalog.search(query, type = "playlists")
        val playlists = results.sections
            .flatMap { it.items }
            .filter { item ->
                item.type == "playlist" && !item.endpoint?.browseId.isNullOrBlank()
            }
            .distinctBy { it.endpoint?.browseId }
            .take(12)

        assertFalse("Playlist search should return a browseable playlist", playlists.isEmpty())

        var followedContinuation = false
        for (playlist in playlists) {
            val endpoint = requireNotNull(playlist.endpoint)
            val browseId = requireNotNull(endpoint.browseId)
            val detail = catalog.browse(
                browseId = browseId,
                params = endpoint.params,
                surface = "playlist",
                title = playlist.title,
            )
            val firstPageIds = detail.sections
                .flatMap { it.items }
                .mapNotNull { it.track?.id }
                .toSet()
            println(
                "Live playlist page candidate: title=${playlist.title.take(80)} " +
                    "tracks=${firstPageIds.size} continuationPresent=${!detail.nextCursor.isNullOrBlank()} " +
                    "shape=$lastInitialBrowseShape",
            )
            if (firstPageIds.isEmpty()) continue

            val continuation = detail.nextCursor ?: continue
            val nextPage = catalog.browse(
                browseId = "",
                continuation = continuation,
                surface = "playlist",
                title = playlist.title,
            )
            val nextPageIds = nextPage.sections
                .flatMap { it.items }
                .mapNotNull { it.track?.id }

            val renderedSummary = nextPage.sections.joinToString { section ->
                "${section.title}:${section.items.joinToString { it.type }}"
            }
            assertTrue(
                "Playlist continuation should return tracks; sections=$renderedSummary; " +
                    "initialBrowseShape=$lastInitialBrowseShape; " +
                    "responseShape=$lastContinuationShape",
                nextPageIds.isNotEmpty(),
            )
            assertTrue(
                "Playlist continuation should add at least one new track",
                nextPageIds.any { it !in firstPageIds },
            )
            followedContinuation = true
            break
        }

        assertTrue(
            "None of ${playlists.size} live playlists exposed a usable continuation; " +
                "lastInitialBrowseShape=$lastInitialBrowseShape",
            followedContinuation,
        )
    }
}
