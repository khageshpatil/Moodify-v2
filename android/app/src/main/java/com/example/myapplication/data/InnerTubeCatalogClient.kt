package com.moodify.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Native YouTube Music InnerTube catalog transport for Android.
 *
 * This owns the Android network boundary. The web app keeps its own Node
 * provider, while Android receives typed Moodify catalog models directly from
 * the device request.
 */
class InnerTubeCatalogClient(
    private val httpClient: OkHttpClient = defaultHttpClient(),
    private val localeProvider: () -> CatalogLocale = { CatalogLocale.fromDefault() },
) {
    suspend fun search(
        query: String,
        type: String? = null,
        continuation: String? = null,
    ): CatalogPage = withContext(Dispatchers.IO) {
        val normalized = query.trim()
        if (normalized.isBlank() && continuation.isNullOrBlank()) {
            return@withContext CatalogPage(surface = "search", title = "Search")
        }
        val body = contextBody().apply {
            if (continuation.isNullOrBlank()) {
                put("query", normalized)
                searchParams(type)?.let { put("params", it) }
            } else {
                put("continuation", continuation)
            }
        }
        execute("search", body, surface = "search", title = normalized.ifBlank { "Search" })
    }

    suspend fun searchSuggestions(input: String): SearchSuggestions = withContext(Dispatchers.IO) {
        val normalized = input.trim()
        if (normalized.isBlank()) return@withContext SearchSuggestions()
        val body = contextBody().put("input", normalized)
        val request = Request.Builder()
            .url("$BASE_URL/music/get_search_suggestions?key=$API_KEY&prettyPrint=false")
            .headers(requestHeaders())
            .post(body.toString().toRequestBody(JSON))
            .build()
        httpClient.newCall(request).await().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw CatalogException(
                    "suggestions failed with HTTP ${response.code}: ${shorten(raw)}",
                    response.code,
                )
            }
            val root = runCatching { JSONObject(raw) }.getOrElse {
                throw CatalogException("suggestions returned invalid JSON", cause = it)
            }
            SearchSuggestions(CatalogRendererParser.parseSuggestions(root).take(8))
        }
    }

    suspend fun browse(
        browseId: String,
        params: String? = null,
        continuation: String? = null,
        surface: String = "browse",
        title: String = "Browse",
        visitor: String? = null,
    ): CatalogPage = withContext(Dispatchers.IO) {
        require(browseId.isNotBlank() || !continuation.isNullOrBlank()) {
            "browseId or continuation must be provided"
        }
        val body = contextBody(visitor).apply {
            if (continuation.isNullOrBlank()) {
                put("browseId", browseId)
                params?.takeIf { it.isNotBlank() }?.let { put("params", it) }
            } else {
                put("continuation", continuation)
            }
        }
        execute("browse", body, surface, title, visitor)
    }

    /**
     * The "Up next" queue YouTube Music builds for a song. With
     * [playlistId] = `RDAMVM<videoId>` this is the song's endless radio mix;
     * page further with [continuation].
     */
    suspend fun upNext(
        videoId: String?,
        playlistId: String? = null,
        continuation: String? = null,
    ): UpNextPage = withContext(Dispatchers.IO) {
        val body = contextBody().apply {
            if (continuation.isNullOrBlank()) {
                videoId?.let { put("videoId", it) }
                playlistId?.let { put("playlistId", it) }
                put("isAudioOnly", true)
                put("enablePersistentPlaylistPanel", true)
                put("tunerSettingValue", "AUTOMIX_SETTING_NORMAL")
            } else {
                put("continuation", continuation)
            }
        }
        val request = Request.Builder()
            .url("$BASE_URL/next?key=$API_KEY&prettyPrint=false")
            .headers(requestHeaders())
            .post(body.toString().toRequestBody(JSON))
            .build()
        httpClient.newCall(request).await().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw CatalogException("next failed with HTTP ${response.code}: ${shorten(raw)}", response.code)
            }
            val root = runCatching { JSONObject(raw) }.getOrElse {
                throw CatalogException("next returned invalid JSON", cause = it)
            }
            rememberVisitor(root)
            CatalogRendererParser.parseUpNext(root)
        }
    }

    /**
     * Title, artist, length and cover for one song (from its player details),
     * for links opened in Moodify. Null when YouTube doesn't know the id.
     */
    suspend fun songDetails(videoId: String): Track? = withContext(Dispatchers.IO) {
        val body = contextBody().put("videoId", videoId)
        val request = Request.Builder()
            .url("$BASE_URL/player?key=$API_KEY&prettyPrint=false")
            .headers(requestHeaders())
            .post(body.toString().toRequestBody(JSON))
            .build()
        httpClient.newCall(request).await().use { response ->
            if (!response.isSuccessful) return@use null
            val root = runCatching { JSONObject(response.body?.string().orEmpty()) }.getOrNull() ?: return@use null
            CatalogRendererParser.parseSongDetails(root)?.takeIf { it.id == videoId }
        }
    }

    /**
     * YouTube Music's own (unsynced) lyrics for a song, from the Lyrics tab of
     * its watch page. Returns null when YouTube has none.
     */
    suspend fun youTubeLyrics(lyricsBrowseId: String): String? = withContext(Dispatchers.IO) {
        val body = contextBody().put("browseId", lyricsBrowseId)
        val request = Request.Builder()
            .url("$BASE_URL/browse?key=$API_KEY&prettyPrint=false")
            .headers(requestHeaders())
            .post(body.toString().toRequestBody(JSON))
            .build()
        httpClient.newCall(request).await().use { response ->
            if (!response.isSuccessful) return@withContext null
            val root = runCatching { JSONObject(response.body?.string().orEmpty()) }.getOrNull()
                ?: return@withContext null
            CatalogRendererParser.parseDescriptionText(root)
        }
    }

    /** Home filtered by one of YouTube's chips (empty [chipParams] = unfiltered). */
    suspend fun homeForChip(chipParams: String?): CatalogPage =
        browse("FEmusic_home", params = chipParams, surface = "home", title = "Home")

    /** Further Home shelves, loaded as the listener scrolls. */
    suspend fun homeContinuation(cursor: String, visitor: String?): CatalogPage =
        browse("FEmusic_home", continuation = cursor, surface = "home", title = "Home", visitor = visitor)

    suspend fun home(): CatalogPage =
        browse("FEmusic_home", surface = "home", title = "Home")

    suspend fun explore(): CatalogPage =
        browse("FEmusic_explore", surface = "explore", title = "Explore")

    suspend fun moodsAndGenres(): CatalogPage =
        browse(
            browseId = MOODS_AND_GENRES_BROWSE_ID,
            surface = "moods",
            title = "Moods & genres",
        )

    suspend fun charts(): CatalogPage =
        browse(
            browseId = "FEmusic_charts",
            params = CHARTS_PARAMS,
            surface = "charts",
            title = "Charts",
        )

    private suspend fun execute(
        operation: String,
        body: JSONObject,
        surface: String,
        title: String,
        visitor: String? = null,
    ): CatalogPage {
        val request = Request.Builder()
            .url("$BASE_URL/$operation?key=$API_KEY&prettyPrint=false")
            .headers(requestHeaders(visitor))
            .post(body.toString().toRequestBody(JSON))
            .build()

        var lastFailure: Throwable? = null
        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                httpClient.newCall(request).await().use { response ->
                    val raw = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        throw CatalogException(
                            "${operation} failed with HTTP ${response.code}: ${shorten(raw)}",
                            response.code,
                        )
                    }
                    val root = runCatching { JSONObject(raw) }.getOrElse {
                        throw CatalogException("${operation} returned invalid JSON", cause = it)
                    }
                    rememberVisitor(root)
                    val servedBy = root.optJSONObject("responseContext")?.optString("visitorData")
                        ?.takeIf { it.isNotBlank() } ?: visitor ?: visitorData
                    val page = CatalogRendererParser.parse(root, surface, title, localeProvider())
                        .copy(visitorData = servedBy)
                    if (page.sections.isEmpty() && page.actions.isEmpty()) {
                        throw CatalogException("${operation} returned no renderable catalog sections")
                    }
                    return page
                }
            } catch (failure: CatalogException) {
                lastFailure = failure
                if (attempt + 1 < MAX_ATTEMPTS) delay(RETRY_DELAYS_MS[attempt])
            } catch (failure: IOException) {
                // A cancelled coroutine surfaces as an IOException from the
                // cancelled call; rethrow cancellation instead of retrying.
                currentCoroutineContext().ensureActive()
                lastFailure = failure
                if (attempt + 1 < MAX_ATTEMPTS) delay(RETRY_DELAYS_MS[attempt])
            }
        }
        throw (lastFailure as? Exception ?: CatalogException("$operation failed"))
    }

    /**
     * YouTube's anonymous session id. Continuation tokens (more Home shelves,
     * radio pages) only work when sent back with the session that issued them,
     * so it is captured from the first response and echoed on every request.
     */
    @Volatile
    private var visitorData: String? = null

    private fun rememberVisitor(root: JSONObject) {
        root.optJSONObject("responseContext")?.optString("visitorData")
            ?.takeIf { it.isNotBlank() }
            ?.let { visitorData = it }
    }

    private fun contextBody(visitorOverride: String? = null): JSONObject {
        val locale = localeProvider()
        val client = JSONObject()
            .put("clientName", CLIENT_NAME)
            .put("clientVersion", CLIENT_VERSION)
            .put("hl", locale.language)
            .put("gl", locale.region)
            .put("platform", "DESKTOP")
        (visitorOverride ?: visitorData)?.let { client.put("visitorData", it) }
        return JSONObject().put("context", JSONObject().put("client", client))
    }

    private fun requestHeaders(visitorOverride: String? = null): Headers = Headers.Builder()
        .add("Accept", "application/json")
        .add("Accept-Language", localeProvider().acceptLanguage)
        .add("Content-Type", "application/json")
        .add("Origin", "https://music.youtube.com")
        .add("Referer", "https://music.youtube.com/")
        .add("X-YouTube-Client-Name", CLIENT_ID)
        .add("X-YouTube-Client-Version", CLIENT_VERSION)
        .add("User-Agent", USER_AGENT)
        .apply { (visitorOverride ?: visitorData)?.let { add("X-Goog-Visitor-Id", it) } }
        .build()

    private fun searchParams(type: String?): String? = when (type?.lowercase()) {
        // These are the current WEB_REMIX filter values used by native
        // YouTube Music clients. They are intentionally percent-encoded as
        // part of the InnerTube params value, matching ArchiveTune/YouTube's
        // own request contract.
        "songs", "song", "tracks" -> "EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D"
        "videos", "video" -> "EgWKAQIQAWoKEAkQChAFEAMQBA%3D%3D"
        "albums", "album" -> "EgWKAQIYAWoKEAkQChAFEAMQBA%3D%3D"
        "artists", "artist" -> "EgWKAQIgAWoKEAkQChAFEAMQBA%3D%3D"
        "playlists", "playlist" -> "EgeKAQQoADgBagwQDhAKEAMQBRAJEAQ%3D"
        else -> null
    }

    private fun shorten(value: String): String = value
        .replace(Regex("\\s+"), " ")
        .take(180)

    companion object {
        private const val API_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
        private const val BASE_URL = "https://music.youtube.com/youtubei/v1"
        private const val CLIENT_NAME = "WEB_REMIX"
        private const val CLIENT_ID = "67"
        private const val CLIENT_VERSION = "1.20260707.12.00"
        private const val MOODS_AND_GENRES_BROWSE_ID = "FEmusic_moods_and_genres"
        // WEB_REMIX charts request parameter used by ArchiveTune's InnerTube
        // client; preserve it as part of the endpoint contract.
        private const val CHARTS_PARAMS = "ggMGCgQIgAQ%3D"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"
        private const val MAX_ATTEMPTS = 2
        private val RETRY_DELAYS_MS = longArrayOf(350L, 900L)
        private val JSON = "application/json; charset=utf-8".toMediaType()

        private fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .callTimeout(35, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }
}

data class CatalogLocale(
    val language: String,
    val region: String,
) {
    val acceptLanguage: String
        get() = "$language-$region,$language;q=0.9"

    companion object {
        fun fromDefault(): CatalogLocale {
            val locale = Locale.getDefault()
            return CatalogLocale(
                language = locale.language.ifBlank { "en" },
                region = locale.country.ifBlank { "US" },
            ).normalized()
        }
    }

    fun normalized(): CatalogLocale = CatalogLocale(
        language = language.lowercase().take(8).ifBlank { "en" },
        region = region.uppercase().take(2).ifBlank { "US" },
    )
}

class CatalogException(message: String, val statusCode: Int? = null, cause: Throwable? = null) :
    IOException(message, cause)

/**
 * Tolerant parser for the renderer families used by YouTube Music. Optional
 * badges and wrappers are ignored; canonical IDs, text, artwork, durations,
 * endpoints, and continuations are preserved.
 */
internal object CatalogRendererParser {
    /**
     * Parses a WEB_REMIX `next` response (the "Up next" panel). Works for both
     * the first page (`playlistPanelRenderer`) and continuation pages
     * (`playlistPanelContinuation`).
     */
    /** A song from a `player` response's videoDetails. */
    fun parseSongDetails(root: JSONObject): Track? {
        val details = root.optJSONObject("videoDetails") ?: return null
        val id = details.optString("videoId").takeIf { it.isNotBlank() } ?: return null
        val title = details.optString("title").takeIf { it.isNotBlank() } ?: return null
        // Official audio uploads come from "<Artist> - Topic" channels; only those
        // channels are artist pages (others are labels or uploaders).
        val rawAuthor = details.optString("author")
        val isTopic = rawAuthor.endsWith(" - Topic")
        val author = rawAuthor.removeSuffix(" - Topic").trim()
        return Track(
            id = id,
            title = title,
            artist = author.ifBlank { "Unknown artist" },
            albumArtUrl = thumbnailUrl(details.opt("thumbnail")),
            durationSeconds = details.optString("lengthSeconds").toIntOrNull() ?: 0,
            artistId = details.optString("channelId").takeIf { isTopic && it.startsWith("UC") },
        )
    }

    fun parseUpNext(root: JSONObject): UpNextPage {
        val panel = findRenderer(root, "playlistPanelContinuation")
            ?: findRenderer(root, "playlistPanelRenderer")
            ?: return UpNextPage(emptyList(), null, null, null)
        val tracks = mutableListOf<Track>()
        var automixPlaylistId: String? = null
        val contents = panel.optJSONArray("contents") ?: JSONArray()
        for (index in 0 until contents.length()) {
            val entry = contents.optJSONObject(index) ?: continue
            val renderer = entry.optJSONObject("playlistPanelVideoRenderer")
                ?: entry.optJSONObject("playlistPanelVideoWrapperRenderer")
                    ?.optJSONObject("primaryRenderer")
                    ?.optJSONObject("playlistPanelVideoRenderer")
            if (renderer != null) {
                upNextTrack(renderer)?.let(tracks::add)
                continue
            }
            // The panel can end with an "Automix" preview that points at an
            // endless radio playlist; remember it so callers can follow it.
            entry.optJSONObject("automixPreviewVideoRenderer")
                ?.let { findRenderer(it, "watchPlaylistEndpoint") }
                ?.optString("playlistId")
                ?.takeIf { it.isNotBlank() }
                ?.let { automixPlaylistId = it }
        }
        val continuations = panel.optJSONArray("continuations")
        var continuation: String? = null
        if (continuations != null) {
            for (index in 0 until continuations.length()) {
                val item = continuations.optJSONObject(index) ?: continue
                continuation = firstNonBlank(
                    item.optJSONObject("nextRadioContinuationData")?.optString("continuation"),
                    item.optJSONObject("nextContinuationData")?.optString("continuation"),
                )
                if (continuation != null) break
            }
        }
        // The watch-next tabs are [Up next, Lyrics, Related]; Related is a browse page.
        val relatedBrowseId = findRenderer(root, "watchNextTabbedResultsRenderer")
            ?.optJSONArray("tabs")
            ?.let { tabs ->
                (0 until tabs.length()).asSequence()
                    .mapNotNull { tabs.optJSONObject(it)?.optJSONObject("tabRenderer") }
                    .mapNotNull { it.optJSONObject("endpoint")?.optJSONObject("browseEndpoint")?.optString("browseId") }
                    .firstOrNull { it.startsWith("MPTRt") }
            }
        val lyricsBrowseId = findRenderer(root, "watchNextTabbedResultsRenderer")
            ?.optJSONArray("tabs")
            ?.let { tabs ->
                (0 until tabs.length()).asSequence()
                    .mapNotNull { tabs.optJSONObject(it)?.optJSONObject("tabRenderer") }
                    .mapNotNull { it.optJSONObject("endpoint")?.optJSONObject("browseEndpoint")?.optString("browseId") }
                    .firstOrNull { it.startsWith("MPLYt") }
            }
        return UpNextPage(
            tracks = tracks.distinctBy { it.id },
            continuation = continuation,
            relatedBrowseId = relatedBrowseId,
            automixPlaylistId = automixPlaylistId,
            lyricsBrowseId = lyricsBrowseId,
        )
    }

    private fun upNextTrack(renderer: JSONObject): Track? {
        val videoId = renderer.optString("videoId").takeIf { it.isNotBlank() } ?: return null
        // Radio mixes can include fan uploads (covers, mashups) and podcast
        // episodes; keep songs and official music videos only.
        val videoType = findRenderer(renderer.opt("navigationEndpoint"), "watchEndpointMusicConfig")
            ?.optString("musicVideoType").orEmpty()
        if (videoType == "MUSIC_VIDEO_TYPE_UGC" || videoType == "MUSIC_VIDEO_TYPE_PODCAST_EPISODE") return null
        val title = text(renderer.opt("title")) ?: return null
        val byline = text(renderer.opt("longBylineText")) ?: text(renderer.opt("shortBylineText")).orEmpty()
        val parts = byline.split("•").map(String::trim).filter(String::isNotBlank)
        val artist = parts.firstOrNull { !isTypeOrDuration(it) && !looksLikeStats(it) && !isSpokenDurationOrDate(it) }
            ?: "Unknown artist"
        val album = parts.drop(1).firstOrNull { part ->
            part != artist && !isTypeOrDuration(part) && !looksLikeStats(part) &&
                !part.matches(Regex("\\d{4}")) && !isSpokenDurationOrDate(part)
        }.orEmpty()
        val explicit = renderer.optJSONArray("badges")?.toString()?.contains("MUSIC_EXPLICIT_BADGE") == true
        val (artistId, albumId) = bylineLinks(renderer)
        return Track(
            id = videoId,
            title = title,
            artist = artist,
            album = album,
            albumArtUrl = thumbnailUrl(renderer.opt("thumbnail")),
            durationSeconds = parseDuration(text(renderer.opt("lengthText"))),
            explicit = explicit,
            artistId = artistId,
            albumId = albumId,
        )
    }

    /** Text of the first description shelf (used for YouTube Music lyrics). */
    fun parseDescriptionText(root: JSONObject): String? {
        val shelf = findRenderer(root, "musicDescriptionShelfRenderer") ?: return null
        return text(shelf.opt("description"))?.takeIf { it.isNotBlank() }
    }

    fun parseSuggestions(root: JSONObject): List<String> {
        val output = linkedSetOf<String>()
        collectSuggestions(root, output)
        return output.toList()
    }

    private fun collectSuggestions(value: Any?, output: MutableSet<String>) {
        when (value) {
            is JSONArray -> for (index in 0 until value.length()) collectSuggestions(value.opt(index), output)
            is JSONObject -> {
                value.optJSONObject("searchSuggestionRenderer")?.let { renderer ->
                    val suggestion = text(renderer.opt("suggestion"))
                        ?: renderer.optJSONObject("navigationEndpoint")
                            ?.optJSONObject("searchEndpoint")
                            ?.optString("query")
                    suggestion?.takeIf { it.isNotBlank() }?.let(output::add)
                }
                val keys = value.keys()
                while (keys.hasNext()) collectSuggestions(value.opt(keys.next()), output)
            }
        }
    }

    fun parse(
        root: JSONObject,
        surface: String,
        fallbackTitle: String,
        locale: CatalogLocale,
    ): CatalogPage {
        val sections = mutableListOf<CatalogSection>()
        collectShelves(root, sections, locale, surface, fallbackTitle)
        appendContinuationActionItems(root, sections, locale, surface, fallbackTitle)

        if (sections.isEmpty()) {
            val items = mutableListOf<CatalogItem>()
            collectItems(root, items, locale)
            if (items.isNotEmpty()) {
                sections += CatalogSection(
                    id = "$surface-items",
                    title = fallbackTitle.ifBlank { "Songs" },
                    layout = "list",
                    items = items.distinctBy { "${it.type}:${it.id}" },
                )
            }
        }

        val pageArtwork = findPageArtwork(root)
        val sectionsWithArtwork = if (pageArtwork.isBlank()) {
            sections
        } else {
            sections.map { section ->
                section.copy(
                    items = section.items.map { item ->
                        if (item.artworkUrl.isNotBlank()) item
                        else item.copy(
                            artworkUrl = pageArtwork,
                            track = item.track?.copy(albumArtUrl = pageArtwork),
                        )
                    },
                )
            }
        }

        return CatalogPage(
            version = 1,
            surface = surface,
            title = findPageTitle(root) ?: fallbackTitle,
            sections = sectionsWithArtwork,
            artworkUrl = findHeaderArtwork(root).takeIf { it.isNotBlank() },
            chips = parseChips(root).takeIf { it.isNotEmpty() },
            nextCursor = when (surface.lowercase(Locale.ROOT)) {
                "playlist" -> findPlaylistContinuation(root)
                "artist" -> findArtistItemsContinuation(root, locale)
                // Home pages through its section list; other continuations in the
                // response (inside carousels) would load the wrong thing.
                "home" -> findSectionListContinuation(root)
                else -> findContinuation(root)
            },
        )
    }

    private fun parseChips(root: JSONObject): List<CatalogChip> {
        val chips = findRenderer(root, "chipCloudRenderer")?.optJSONArray("chips") ?: return emptyList()
        return (0 until chips.length()).mapNotNull { index ->
            val chip = chips.optJSONObject(index)?.optJSONObject("chipCloudChipRenderer") ?: return@mapNotNull null
            val title = text(chip.opt("text")) ?: return@mapNotNull null
            val params = findRenderer(chip.opt("navigationEndpoint"), "browseEndpoint")?.optString("params")
                ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            CatalogChip(title = title, params = params, selected = chip.optBoolean("isSelected"))
        }
    }

    private fun findSectionListContinuation(root: JSONObject): String? {
        val list = root.optJSONObject("continuationContents")?.optJSONObject("sectionListContinuation")
            ?: findRenderer(root, "sectionListRenderer")
            ?: return null
        val continuations = list.optJSONArray("continuations") ?: return null
        for (index in 0 until continuations.length()) {
            val entry = continuations.optJSONObject(index) ?: continue
            entry.optJSONObject("nextContinuationData")?.optString("continuation")
                ?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
    }

    private fun findPageArtwork(root: JSONObject): String {
        val header = findRenderer(root, "musicResponsiveHeaderRenderer") ?: return ""
        return thumbnailUrl(header.opt("thumbnail"))
            .ifBlank { thumbnailUrl(header.opt("straplineThumbnail")) }
    }

    /** The header image of a browse page: an artist's photo, or an album/playlist cover. */
    private fun findHeaderArtwork(root: JSONObject): String {
        val header = root.optJSONObject("header") ?: return findPageArtwork(root)
        val renderer = listOf(
            "musicImmersiveHeaderRenderer",
            "musicVisualHeaderRenderer",
            "musicResponsiveHeaderRenderer",
            "musicDetailHeaderRenderer",
        ).firstNotNullOfOrNull { header.optJSONObject(it) } ?: return findPageArtwork(root)
        return thumbnailUrl(renderer.opt("thumbnail"))
            .ifBlank { thumbnailUrl(renderer.opt("foregroundThumbnail")) }
            .ifBlank { findPageArtwork(root) }
    }

    /**
     * Browse ids of the first artist and album linked in an item's byline
     * runs (e.g. "Arijit Singh • Kesariya (From "Brahmastra")").
     */
    internal fun bylineLinks(renderer: JSONObject): Pair<String?, String?> {
        var artistId: String? = null
        var albumId: String? = null
        val sources = buildList {
            renderer.optJSONArray("flexColumns")?.let { columns ->
                for (index in 1 until columns.length()) {
                    add(columns.optJSONObject(index)?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")?.opt("text"))
                }
            }
            add(renderer.opt("subtitle"))
            add(renderer.opt("longBylineText"))
            add(renderer.opt("shortBylineText"))
        }
        for (source in sources) {
            val runs = (source as? JSONObject)?.optJSONArray("runs") ?: continue
            for (index in 0 until runs.length()) {
                val browse = runs.optJSONObject(index)
                    ?.optJSONObject("navigationEndpoint")
                    ?.optJSONObject("browseEndpoint") ?: continue
                val id = browse.optString("browseId").takeIf { it.isNotBlank() } ?: continue
                val pageType = browse.optJSONObject("browseEndpointContextSupportedConfigs")
                    ?.optJSONObject("browseEndpointContextMusicConfig")
                    ?.optString("pageType").orEmpty()
                val isArtist = pageType == "MUSIC_PAGE_TYPE_ARTIST" || (pageType.isBlank() && id.startsWith("UC"))
                val isAlbum = pageType == "MUSIC_PAGE_TYPE_ALBUM" || (pageType.isBlank() && id.startsWith("MPRE"))
                if (artistId == null && isArtist) artistId = id
                if (albumId == null && isAlbum) albumId = id
            }
            if (artistId != null && albumId != null) break
        }
        return artistId to albumId
    }

    private fun findRenderer(value: Any?, rendererKey: String): JSONObject? {
        when (value) {
            is JSONArray -> for (index in 0 until value.length()) {
                findRenderer(value.opt(index), rendererKey)?.let { return it }
            }
            is JSONObject -> {
                value.optJSONObject(rendererKey)?.let { return it }
                val keys = value.keys()
                while (keys.hasNext()) {
                    findRenderer(value.opt(keys.next()), rendererKey)?.let { return it }
                }
            }
        }
        return null
    }

    private fun collectShelves(
        value: Any?,
        output: MutableList<CatalogSection>,
        locale: CatalogLocale,
        surface: String,
        fallbackTitle: String,
    ) {
        when (value) {
            is JSONArray -> for (index in 0 until value.length()) {
                collectShelves(value.opt(index), output, locale, surface, fallbackTitle)
            }
            is JSONObject -> {
                when {
                    value.has("musicPlaylistShelfRenderer") -> {
                        parseShelf(
                            value.optJSONObject("musicPlaylistShelfRenderer"),
                            "list",
                            output,
                            locale,
                            fallbackTitle,
                        )
                        return
                    }
                    value.has("musicPlaylistShelfContinuation") -> {
                        parseContinuationShelf(
                            value.optJSONObject("musicPlaylistShelfContinuation"),
                            output,
                            locale,
                            surface,
                            fallbackTitle,
                        )
                        return
                    }
                    value.has("musicShelfContinuation") -> {
                        parseContinuationShelf(
                            value.optJSONObject("musicShelfContinuation"),
                            output,
                            locale,
                            surface,
                            fallbackTitle,
                        )
                        return
                    }
                    value.has("musicShelfRenderer") -> {
                        parseShelf(value.optJSONObject("musicShelfRenderer"), "list", output, locale)
                        return
                    }
                    value.has("musicCarouselShelfRenderer") -> {
                        parseShelf(value.optJSONObject("musicCarouselShelfRenderer"), "carousel", output, locale)
                        return
                    }
                    value.has("musicImmersiveCarouselShelfRenderer") -> {
                        parseShelf(value.optJSONObject("musicImmersiveCarouselShelfRenderer"), "immersive", output, locale)
                        return
                    }
                    value.has("musicTastebuilderShelfRenderer") -> {
                        parseShelf(value.optJSONObject("musicTastebuilderShelfRenderer"), "taste", output, locale)
                        return
                    }
                    value.has("gridRenderer") -> {
                        parseGrid(value.optJSONObject("gridRenderer"), output, locale)
                        return
                    }
                }
                val keys = value.keys()
                while (keys.hasNext()) {
                    collectShelves(value.opt(keys.next()), output, locale, surface, fallbackTitle)
                }
            }
        }
    }

    private fun parseContinuationShelf(
        shelf: JSONObject?,
        output: MutableList<CatalogSection>,
        locale: CatalogLocale,
        surface: String,
        fallbackTitle: String,
    ) {
        val sectionStart = output.size
        parseShelf(shelf, "list", output, locale, fallbackTitle)
        if (output.size > sectionStart) {
            val lastIndex = output.lastIndex
            output[lastIndex] = output[lastIndex].copy(
                id = "$surface-items",
                title = fallbackTitle.ifBlank { "Music" },
            )
        }
    }

    private fun parseShelf(
        shelf: JSONObject?,
        layout: String,
        output: MutableList<CatalogSection>,
        locale: CatalogLocale,
        fallbackTitle: String? = null,
    ) {
        if (shelf == null) return
        val title = shelfTitle(shelf) ?: fallbackTitle?.takeIf { it.isNotBlank() } ?: "Music"
        val items = mutableListOf<CatalogItem>()
        val contents = shelf.optJSONArray("contents")
        if (contents != null) {
            for (index in 0 until contents.length()) {
                parseItem(contents.opt(index), locale)?.let(items::add)
            }
        }
        if (items.isEmpty()) return
        val endpoint = parseEndpoint(shelf)
        val itemLayout = if (items.all { it.type == "navigation" }) "mood-carousel" else layout
        output += CatalogSection(
            id = endpoint?.browseId ?: "section-${output.size}-${slug(title)}",
            title = title,
            layout = itemLayout,
            items = items.distinctBy { "${it.type}:${it.id}" },
            endpoint = endpoint,
        )
    }

    private fun shelfTitle(shelf: JSONObject): String? {
        text(shelf.opt("title"))?.let { return it }
        val header = shelf.optJSONObject("header") ?: return null
        val rendererKeys = listOf(
            "musicCarouselShelfBasicHeaderRenderer",
            "musicImmersiveCarouselShelfBasicHeaderRenderer",
            "musicShelfHeaderRenderer",
            "musicCarouselShelfHeaderRenderer",
            "gridHeaderRenderer",
        )
        for (key in rendererKeys) {
            val renderer = header.optJSONObject(key) ?: continue
            text(renderer.opt("title"))?.let { return it }
            text(renderer.opt("strapline"))?.let { return it }
        }
        return text(header.opt("title")) ?: text(header)
    }

    private fun parseGrid(
        grid: JSONObject?,
        output: MutableList<CatalogSection>,
        locale: CatalogLocale,
    ) {
        if (grid == null) return
        val title = text(
            grid.optJSONObject("header")
                ?.optJSONObject("gridHeaderRenderer")
                ?.opt("title"),
        ) ?: "Browse"
        val sourceItems = grid.optJSONArray("items") ?: return
        val items = buildList {
            for (index in 0 until sourceItems.length()) {
                parseItem(sourceItems.opt(index), locale)?.let(::add)
            }
        }
        if (items.isEmpty()) return
        val navigationOnly = items.all { it.type == "navigation" }
        output += CatalogSection(
            id = "section-${output.size}-${slug(title)}",
            title = title,
            layout = if (navigationOnly) "mood-grid" else "grid",
            items = items.distinctBy { "${it.type}:${it.id}" },
        )
    }

    private fun collectItems(
        value: Any?,
        output: MutableList<CatalogItem>,
        locale: CatalogLocale,
    ) {
        when (value) {
            is JSONArray -> for (index in 0 until value.length()) {
                collectItems(value.opt(index), output, locale)
            }
            is JSONObject -> {
                parseItem(value, locale)?.let(output::add)
                val keys = value.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (key !in ITEM_RENDERER_KEYS) collectItems(value.opt(key), output, locale)
                }
            }
        }
    }

    private fun parseItem(value: Any?, locale: CatalogLocale): CatalogItem? {
        val wrapper = value as? JSONObject ?: return null
        wrapper.optJSONObject("musicNavigationButtonRenderer")?.let { renderer ->
            val title = text(renderer.opt("buttonText")) ?: return null
            val endpoint = parseEndpoint(JSONObject().put("clickCommand", renderer.opt("clickCommand")))
                ?: return null
            val browseId = endpoint.browseId ?: return null
            val params = endpoint.params.orEmpty()
            return CatalogItem(
                type = "navigation",
                id = "$browseId:$params:$title",
                title = title,
                endpoint = endpoint,
                accentColor = renderer.optJSONObject("solid")
                    ?.takeIf { it.has("leftStripeColor") }
                    ?.optLong("leftStripeColor"),
            )
        }
        val renderer = when {
            wrapper.has("musicResponsiveListItemRenderer") ->
                wrapper.optJSONObject("musicResponsiveListItemRenderer")
            wrapper.has("musicTwoRowItemRenderer") ->
                wrapper.optJSONObject("musicTwoRowItemRenderer")
            wrapper.has("musicMultiRowListItemRenderer") ->
                wrapper.optJSONObject("musicMultiRowListItemRenderer")
            else -> null
        } ?: return null

        val videoId = firstNonBlank(
            renderer.optJSONObject("playlistItemData")?.optString("videoId"),
            renderer.optString("videoId"),
            renderer.optJSONObject("navigationEndpoint")
                ?.optJSONObject("watchEndpoint")
                ?.optString("videoId"),
            renderer.optJSONObject("playNavigationEndpoint")
                ?.optJSONObject("watchEndpoint")
                ?.optString("videoId"),
        )
        val endpoint = parseEndpoint(renderer)
        val id = videoId ?: endpoint?.browseId ?: endpoint?.videoId ?: return null
        val title = firstNonBlank(
            text(renderer.opt("title")),
            flexColumnText(renderer, 0),
            text(renderer.opt("headline")),
        ) ?: return null
        val metadata = firstNonBlank(
            flexColumnText(renderer, 1),
            text(renderer.opt("subtitle")),
        )
        val artist = firstNonBlank(
            artistFromPlayLabel(renderer, title),
            artistFromMetadata(metadata),
            text(renderer.opt("subtitle")),
            flexColumnText(renderer, 1),
        ) ?: "Unknown artist"
        val album = firstNonBlank(
            flexColumnText(renderer, 2)?.takeUnless { looksLikeStats(it) || isSpokenDurationOrDate(it) },
            albumFromMetadata(metadata, artist),
        ).orEmpty()
        val artwork = thumbnailUrl(
            renderer.opt("thumbnail")
                ?: renderer.opt("thumbnailRenderer")
                ?: renderer.opt("thumbnailOverlay"),
        )
        val duration = parseDuration(
            firstNonBlank(
                text(renderer.opt("lengthText")),
                fixedColumnText(renderer),
                durationFromMetadata(metadata),
            ),
        )
        val type = when {
            videoId != null -> "track"
            endpoint?.pageType?.contains("ARTIST", ignoreCase = true) == true -> "artist"
            endpoint?.pageType?.contains("ALBUM", ignoreCase = true) == true -> "album"
            endpoint?.pageType?.contains("PLAYLIST", ignoreCase = true) == true -> "playlist"
            endpoint?.browseId?.startsWith("MPRE") == true -> "album"
            endpoint?.browseId?.startsWith("VL") == true -> "playlist"
            else -> "browse"
        }
        val track = videoId?.let {
            val (artistId, albumId) = bylineLinks(renderer)
            Track(
                id = it,
                title = title,
                artist = artist,
                album = album,
                albumArtUrl = artwork,
                durationSeconds = duration,
                artistId = artistId,
                albumId = albumId,
            )
        }
        return CatalogItem(
            type = type,
            id = id,
            title = title,
            artworkUrl = artwork,
            track = track,
            endpoint = endpoint,
        )
    }

    private fun parseEndpoint(value: JSONObject): BrowseEndpoint? {
        val navigation = value.optJSONObject("navigationEndpoint")
            ?: value.optJSONObject("onTap")
            ?: value.optJSONObject("playNavigationEndpoint")
            ?: value.optJSONObject("clickCommand")
        val browse = navigation?.optJSONObject("browseEndpoint")
        val musicPageType = browse
            ?.optJSONObject("browseEndpointContextSupportedConfigs")
            ?.optJSONObject("browseEndpointContextMusicConfig")
            ?.optString("pageType")
            ?.takeIf { it.isNotBlank() && it != "null" }
        val watch = navigation?.optJSONObject("watchEndpoint")
        val command = navigation?.optJSONObject("commandMetadata")
            ?.optJSONObject("webCommandMetadata")
        val browseId = firstNonBlank(
            browse?.optString("browseId"),
            command?.optString("browseId"),
        )
        val videoId = firstNonBlank(
            watch?.optString("videoId"),
            value.optJSONObject("playlistItemData")?.optString("videoId"),
        )
        val params = firstNonBlank(
            browse?.optString("params"),
            watch?.optString("params"),
        )
        if (browseId == null && videoId == null && params == null) return null
        return BrowseEndpoint(
            browseId = browseId,
            videoId = videoId,
            params = params,
            pageType = musicPageType,
        )
    }

    private fun appendContinuationActionItems(
        root: JSONObject,
        sections: MutableList<CatalogSection>,
        locale: CatalogLocale,
        surface: String,
        fallbackTitle: String,
    ) {
        val actions = root.optJSONArray("onResponseReceivedActions") ?: return
        val appendedItems = buildList {
            for (index in 0 until actions.length()) {
                val continuationItems = actions.optJSONObject(index)
                    ?.optJSONObject("appendContinuationItemsAction")
                    ?.optJSONArray("continuationItems")
                    ?: continue
                for (itemIndex in 0 until continuationItems.length()) {
                    parseItem(continuationItems.opt(itemIndex), locale)?.let(::add)
                }
            }
        }.distinctBy { "${it.type}:${it.id}" }
        if (appendedItems.isEmpty()) return

        val sectionId = "$surface-items"
        val existingIndex = sections.indexOfFirst { it.id == sectionId }
        if (existingIndex >= 0) {
            val existing = sections[existingIndex]
            sections[existingIndex] = existing.copy(
                items = (existing.items + appendedItems).distinctBy { "${it.type}:${it.id}" },
            )
        } else {
            sections += CatalogSection(
                id = sectionId,
                title = fallbackTitle.ifBlank { "Music" },
                layout = "list",
                items = appendedItems,
            )
        }
    }

    /**
     * Artist browse responses can contain independent continuations for the
     * primary song list and secondary related shelves. The page-level Load
     * more action belongs to the first track shelf in the selected artist
     * tab, matching the typed-page behavior used by native YouTube Music
     * clients. Never let a related-playlist cursor page the artist songs.
     */
    private fun findArtistItemsContinuation(root: JSONObject, locale: CatalogLocale): String? {
        val contents = root.optJSONObject("contents")
        val twoColumn = contents?.optJSONObject("twoColumnBrowseResultsRenderer")
        val singleColumn = contents?.optJSONObject("singleColumnBrowseResultsRenderer")
        val primaryContent = when {
            twoColumn != null -> twoColumn.optJSONObject("primaryContents")
                ?: selectedTabContent(twoColumn)
            singleColumn != null -> selectedTabContent(singleColumn) ?: singleColumn
            else -> contents ?: root
        }
        val trackShelf = findArtistTrackShelf(primaryContent, locale) ?: return null
        return findContinuation(trackShelf.opt("contents"))
            ?: findContinuation(trackShelf.opt("continuations"))
    }

    private fun selectedTabContent(container: JSONObject): Any? =
        container.optJSONArray("tabs")
            ?.let { tabs ->
                (0 until tabs.length())
                    .asSequence()
                    .mapNotNull { tabs.optJSONObject(it)?.optJSONObject("tabRenderer") }
                    .firstOrNull { it.optBoolean("selected") }
                    ?.opt("content")
            }

    private fun findArtistTrackShelf(value: Any?, locale: CatalogLocale): JSONObject? = when (value) {
        is JSONArray -> {
            var result: JSONObject? = null
            for (index in 0 until value.length()) {
                result = findArtistTrackShelf(value.opt(index), locale)
                if (result != null) break
            }
            result
        }
        is JSONObject -> {
            val shelf = value.optJSONObject("musicShelfRenderer")
            if (shelf != null && shelf.optJSONArray("contents")?.let { contents ->
                    (0 until contents.length()).any { index ->
                        parseItem(contents.opt(index), locale)?.track != null
                    }
                } == true
            ) {
                shelf
            } else {
                listOf("sectionListRenderer", "contents", "content", "tabRenderer")
                    .firstNotNullOfOrNull { key -> findArtistTrackShelf(value.opt(key), locale) }
            }
        }
        else -> null
    }

    private fun findPlaylistContinuation(root: JSONObject): String? {
        val continuationContents = root.optJSONObject("continuationContents")
        continuationContents?.optJSONObject("musicPlaylistShelfContinuation")
            ?.let(::playlistShelfContinuation)
            ?.let { return it }
        continuationContents?.optJSONObject("musicShelfContinuation")
            ?.let(::playlistShelfContinuation)
            ?.let { return it }

        val actions = root.optJSONArray("onResponseReceivedActions")
        if (actions != null) {
            for (index in 0 until actions.length()) {
                val continuationItems = actions.optJSONObject(index)
                    ?.optJSONObject("appendContinuationItemsAction")
                    ?.optJSONArray("continuationItems")
                    ?: continue
                findContinuation(continuationItems)?.let { return it }
            }
        }

        val twoColumn = root.optJSONObject("contents")
            ?.optJSONObject("twoColumnBrowseResultsRenderer")
        if (twoColumn != null) {
            val secondary = twoColumn.optJSONObject("secondaryContents")
                ?.optJSONObject("sectionListRenderer")
                ?.optJSONArray("contents")
            val primary = twoColumn.optJSONObject("primaryContents")
                ?.optJSONObject("sectionListRenderer")
                ?.optJSONArray("contents")
            return findPlaylistSongContinuation(secondary)
                ?: findPlaylistSongContinuation(primary)
        }
        return findPlaylistSongContinuation(root.opt("contents"))
    }

    private fun playlistShelfContinuation(shelf: JSONObject): String? =
        findContinuation(shelf.opt("contents"))
            ?: findContinuation(shelf.opt("continuations"))

    private fun findPlaylistSongContinuation(value: Any?): String? = when (value) {
        is JSONArray -> {
            var continuation: String? = null
            for (index in 0 until value.length()) {
                continuation = findPlaylistSongContinuation(value.opt(index))
                if (!continuation.isNullOrBlank()) break
            }
            continuation
        }
        is JSONObject -> {
            val playlistShelf = value.optJSONObject("musicPlaylistShelfRenderer")
                ?: value.optJSONObject("musicShelfRenderer")
            if (playlistShelf != null) {
                playlistShelfContinuation(playlistShelf)
            } else {
                val sectionList = value.optJSONObject("sectionListRenderer")
                val itemSection = value.optJSONObject("itemSectionRenderer")
                val twoColumn = value.optJSONObject("twoColumnBrowseResultsRenderer")
                when {
                    twoColumn != null -> {
                        val secondary = twoColumn.optJSONObject("secondaryContents")
                            ?.optJSONObject("sectionListRenderer")
                            ?.optJSONArray("contents")
                        val primary = twoColumn.optJSONObject("primaryContents")
                            ?.optJSONObject("sectionListRenderer")
                            ?.optJSONArray("contents")
                        findPlaylistSongContinuation(secondary)
                            ?: findPlaylistSongContinuation(primary)
                    }
                    sectionList != null -> findPlaylistSongContinuation(sectionList.opt("contents"))
                    itemSection != null -> findPlaylistSongContinuation(itemSection.opt("contents"))
                    else -> {
                        val allowedContainers = listOf(
                            "contents",
                            "primaryContents",
                            "secondaryContents",
                            "tabs",
                            "tabRenderer",
                        )
                        allowedContainers.firstNotNullOfOrNull { key ->
                            findPlaylistSongContinuation(value.opt(key))
                        }
                    }
                }
            }
        }
        else -> null
    }

    private fun findContinuation(value: Any?): String? {
        when (value) {
            is JSONArray -> for (index in 0 until value.length()) {
                findContinuation(value.opt(index))?.let { return it }
            }
            is JSONObject -> {
                value.optJSONObject("continuationCommand")
                    ?.optString("token")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { return it }
                value.optJSONObject("nextContinuationData")
                    ?.optString("continuation")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { return it }
                val keys = value.keys()
                while (keys.hasNext()) {
                    findContinuation(value.opt(keys.next()))?.let { return it }
                }
            }
        }
        return null
    }

    private fun findPageTitle(root: JSONObject): String? {
        val header = findRenderer(root, "musicImmersiveHeaderRenderer")
            ?: findRenderer(root, "musicResponsiveHeaderRenderer")
            ?: findRenderer(root, "musicVisualHeaderRenderer")
            ?: findRenderer(root, "musicDetailHeaderRenderer")
            ?: findRenderer(root, "musicHeaderRenderer")
        return text(header?.opt("title"))
            ?: text(root.optJSONObject("contents")?.opt("title"))
    }

    private fun flexColumnText(renderer: JSONObject, index: Int): String? {
        val columns = renderer.optJSONArray("flexColumns") ?: return null
        val column = columns.optJSONObject(index)
            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
        return text(column?.opt("text"))
    }

    private fun fixedColumnText(renderer: JSONObject): String? {
        val columns = renderer.optJSONArray("fixedColumns") ?: return null
        return text(
            columns.optJSONObject(0)
                ?.optJSONObject("musicResponsiveListItemFixedColumnRenderer")
                ?.opt("text"),
        )
    }

    private fun artistFromPlayLabel(renderer: JSONObject, title: String): String? {
        val label = renderer
            .optJSONObject("overlay")
            ?.optJSONObject("musicItemThumbnailOverlayRenderer")
            ?.optJSONObject("content")
            ?.optJSONObject("musicPlayButtonRenderer")
            ?.optJSONObject("accessibilityPlayData")
            ?.optJSONObject("accessibilityData")
            ?.optString("label")
            ?.trim()
            .orEmpty()
        if (label.isBlank()) return null
        // Labels look like "Play <title> - <artist>", but YouTube Music also
        // emits "Play <title> - 3 minutes, 49 seconds"; never treat that as an artist.
        // Strip the known title rather than splitting on the last " - ": titles like
        // "Veezha Veera (From "OM: Udhiram - The Blood Wood")" contain " - " themselves.
        val knownPrefix = "Play $title - "
        val artist = if (label.startsWith(knownPrefix, ignoreCase = true)) {
            label.substring(knownPrefix.length)
        } else {
            // A tail that is really the end of the title is not an artist.
            label.substringAfterLast(" - ", "").takeUnless { tail -> tail.isNotBlank() && title.endsWith(tail.trim()) }.orEmpty()
        }
        // Playlist rows add the length too: "Play <title> - <artist> - 4 minutes, 27 seconds".
        var cleaned = artist.trim()
        while (true) {
            val tail = cleaned.substringAfterLast(" - ", "").trim()
            if (tail.isNotBlank() && isSpokenDurationOrDate(tail)) cleaned = cleaned.substringBeforeLast(" - ").trim() else break
        }
        return cleaned.takeIf { it.isNotBlank() && !isSpokenDurationOrDate(it) }
    }

    private fun artistFromMetadata(metadata: String?): String? {
        val parts = metadata
            ?.split("•")
            ?.map(String::trim)
            ?.filter(String::isNotBlank)
            .orEmpty()
        return parts.firstOrNull { part ->
            !isTypeOrDuration(part) && !looksLikeStats(part)
        }
    }

    private fun albumFromMetadata(metadata: String?, artist: String): String? {
        val parts = metadata
            ?.split("•")
            ?.map(String::trim)
            ?.filter(String::isNotBlank)
            .orEmpty()
        return parts.firstOrNull { part ->
            part != artist && !isTypeOrDuration(part) && !looksLikeStats(part)
        }
    }

    private fun durationFromMetadata(metadata: String?): String? =
        metadata
            ?.split("•")
            ?.map(String::trim)
            ?.firstOrNull { it.matches(Regex("\\d{1,2}:\\d{2}(?::\\d{2})?")) }

    private fun isTypeOrDuration(value: String): Boolean =
        value.equals("song", ignoreCase = true) ||
            value.equals("video", ignoreCase = true) ||
            value.matches(Regex("\\d{1,2}:\\d{2}(?::\\d{2})?"))

    private fun looksLikeStats(value: String): Boolean =
        value.contains("plays", ignoreCase = true) ||
            value.contains("views", ignoreCase = true) ||
        value.contains("likes", ignoreCase = true) ||
        value.contains("streams", ignoreCase = true) ||
            value.contains("audience", ignoreCase = true) ||
            value.contains("subscribers", ignoreCase = true) ||
            value.matches(Regex("[\\d.,]+[KMB]?"))

    private fun text(value: Any?): String? {
        val objectValue = value as? JSONObject ?: return when (value) {
            is String -> value.trim().takeIf { it.isNotBlank() }
            else -> null
        }
        objectValue.optString("simpleText")
            .trim()
            .takeIf { it.isNotBlank() }
            ?.let { return it }
        val runs = objectValue.optJSONArray("runs") ?: return null
        return buildString {
            for (index in 0 until runs.length()) {
                append(runs.optJSONObject(index)?.optString("text").orEmpty())
            }
        }.trim().takeIf { it.isNotBlank() }
    }

    /**
     * YouTube Music wraps artwork differently by renderer. A responsive item
     * normally has thumbnail.musicThumbnailRenderer.thumbnail.thumbnails,
     * while older/search fixtures may expose thumbnail.thumbnails directly.
     * Only reading the outer object silently produced valid tracks with empty
     * artwork URLs, which made every catalog image appear blank.
     */
    private fun thumbnailUrl(value: Any?): String {
        val objectValue = value as? JSONObject ?: return ""
        val thumbnails = objectValue.optJSONArray("thumbnails")
        if (thumbnails != null) {
            var best = ""
            for (index in 0 until thumbnails.length()) {
                val url = thumbnails.optJSONObject(index)?.optString("url").orEmpty()
                if (url.isNotBlank()) best = url
            }
            if (best.isNotBlank()) return best
        }

        val nestedKeys = listOf(
            "thumbnail",
            "musicThumbnailRenderer",
            "thumbnailRenderer",
            "croppedSquareThumbnailRenderer",
            "playlistVideoThumbnailRenderer",
        )
        nestedKeys.forEach { key ->
            val nested = objectValue.opt(key)
            val result = thumbnailUrl(nested)
            if (result.isNotBlank()) return result
        }
        return ""
    }

    private fun parseDuration(value: String?): Int {
        val parts = value?.split(":") ?: return 0
        if (parts.isEmpty() || parts.any { it.toIntOrNull() == null }) return 0
        return parts.fold(0) { total, part -> total * 60 + part.toInt() }
    }

    private fun slug(value: String): String = value.lowercase()
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
        .take(40)
        .ifBlank { "section" }

    private fun firstNonBlank(vararg values: String?): String? =
        values.firstOrNull { !it.isNullOrBlank() }?.trim()

    private fun shorten(value: String): String = value
        .replace(Regex("\\s+"), " ")
        .take(180)

    private val ITEM_RENDERER_KEYS = setOf(
        "musicResponsiveListItemRenderer",
        "musicTwoRowItemRenderer",
        "musicMultiRowListItemRenderer",
    )
}

private val SPOKEN_DURATION = Regex(
    "^\\d+\\s+(hours?|minutes?|seconds?)(,?\\s+\\d+\\s+(hours?|minutes?|seconds?))*$",
    RegexOption.IGNORE_CASE,
)
private val DISPLAY_DATE = Regex(
    "^(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\\.?\\s+\\d{1,2},\\s+\\d{4}$",
    RegexOption.IGNORE_CASE,
)

/**
 * True for metadata fragments that are not names: spoken durations from
 * accessibility labels ("3 minutes, 49 seconds") and "added on" dates
 * ("May 31, 2026") that playlist rows place in artist/album columns.
 */
internal fun isSpokenDurationOrDate(value: String): Boolean {
    val text = value.trim()
    return SPOKEN_DURATION.matches(text) || DISPLAY_DATE.matches(text)
}

/** One page of a song's Up next / radio queue. */
data class UpNextPage(
    val tracks: List<Track>,
    val continuation: String?,
    /** Browse id of the "Related" tab (similar songs, artists, albums). */
    val relatedBrowseId: String?,
    /** Endless automix playlist the panel offers after its own items. */
    val automixPlaylistId: String?,
    /** Browse id of YouTube Music's own lyrics for the first track, when it has them. */
    val lyricsBrowseId: String? = null,
)
