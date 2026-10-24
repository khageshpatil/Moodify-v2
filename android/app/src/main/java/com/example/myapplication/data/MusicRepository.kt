package com.example.myapplication.data

import com.example.myapplication.BuildConfig
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.Response
import retrofit2.converter.gson.GsonConverterFactory
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

class MoodifyApiException(message: String, val statusCode: Int? = null) : IOException(message)

class MusicRepository {

    private val serverBaseUrl = BuildConfig.MOODIFY_SERVER_URL.trimEnd('/')

    private val api = Retrofit.Builder()
        .baseUrl("$serverBaseUrl/")
        .client(
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .callTimeout(75, TimeUnit.SECONDS)
                .addInterceptor(HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.BASIC
                })
                .build()
        )
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(MoodifyServerApi::class.java)

    suspend fun searchSongs(query: String): List<Track> {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isBlank()) return emptyList()

        var lastError: Exception? = null
        repeat(3) { attempt ->
            try {
                val response = api.searchSongs(normalizedQuery)
                if (!response.isSuccessful) {
                    throw MoodifyApiException(
                        "Moodify search failed (${response.code()})",
                        response.code()
                    )
                }

                val tracks = response.body()
                    ?.getAsJsonArray("tracks")
                    ?.mapNotNull { it.toTrackOrNull() }
                    ?.distinctBy { it.id }
                    ?.take(20)
                    .orEmpty()

                if (tracks.isEmpty()) throw MoodifyApiException("No songs found for \"$normalizedQuery\"")
                return tracks
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                lastError = error
                if (attempt < 2) delay(700L * (attempt + 1))
            }
        }
        throw lastError ?: MoodifyApiException("Moodify search failed")
    }

    suspend fun getTrendingSongs(): List<Track> = searchSongs("trending")

    suspend fun getLyrics(trackId: String): String? {
        val response = api.getLyrics(trackId)
        if (!response.isSuccessful) {
            if (response.code() == 404) return null
            throw MoodifyApiException("Lyrics request failed (${response.code()})", response.code())
        }
        return response.body()?.get("lyrics")?.takeIf { it.isJsonPrimitive }?.asString
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    // ── JSON helpers ────────────────────────────────────────────────

    suspend fun getExploreCatalog(): CatalogPage = getCatalogPage { api.getExplore() }

    suspend fun getHomeCatalog(): CatalogPage = getCatalogPage { api.getHome() }

    suspend fun getChartsCatalog(): CatalogPage = getCatalogPage { api.getCharts() }

    suspend fun browseCatalog(browseId: String, params: String? = null): CatalogPage =
        getCatalogPage { api.browse(browseId, params) }

    suspend fun searchCatalog(query: String, type: String? = null): CatalogPage =
        getCatalogPage { api.searchDiscovery(query, type) }

    private suspend fun getCatalogPage(request: suspend () -> Response<JsonObject>): CatalogPage {
        val response = request()
        if (!response.isSuccessful) {
            throw MoodifyApiException("Moodify catalog request failed (${response.code()})", response.code())
        }
        return response.body()?.toCatalogPage()
            ?: throw MoodifyApiException("Moodify returned an empty catalog page")
    }

    private fun JsonObject.toCatalogPage(): CatalogPage {
        val catalog = get("catalog")?.takeIf { it.isJsonObject }?.asJsonObject
        val pageInfo = get("pageInfo")?.takeIf { it.isJsonObject }?.asJsonObject
        if (catalog == null) {
            val fallbackTracks = getAsJsonArray("tracks")?.mapNotNull { it.toTrackOrNull() }.orEmpty()
            return CatalogPage(
                surface = stringValue("surface") ?: "discovery",
                title = stringValue("surface") ?: "Discovery",
                sections = if (fallbackTracks.isEmpty()) emptyList() else listOf(
                    CatalogSection("fallback", "Songs", "list", fallbackTracks.map { track ->
                        CatalogItem("track", track.id, track.title, track.albumArtUrl, track = track)
                    })
                ),
                nextCursor = pageInfo?.stringValue("nextCursor")
            )
        }

        fun JsonObject.toEndpoint(): BrowseEndpoint? {
            val endpoint = get("endpoint")?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
            return BrowseEndpoint(
                browseId = endpoint.stringValue("browseId"),
                videoId = endpoint.stringValue("videoId"),
                params = endpoint.stringValue("params")
            )
        }

        fun JsonObject.toCatalogItem(): CatalogItem? {
            val id = stringValue("id") ?: return null
            val title = stringValue("title") ?: id
            val track = get("track")?.takeIf { it.isJsonObject }?.toTrackOrNull()
            return CatalogItem(
                type = stringValue("type") ?: "browse",
                id = id,
                title = title,
                artworkUrl = stringValue("artworkUrl").orEmpty(),
                track = track,
                endpoint = toEndpoint()
            )
        }

        val sections = catalog.getAsJsonArray("sections")?.mapNotNull { sectionElement ->
            if (!sectionElement.isJsonObject) return@mapNotNull null
            val section = sectionElement.asJsonObject
            val items = section.getAsJsonArray("items")?.mapNotNull { itemElement ->
                if (!itemElement.isJsonObject) null else itemElement.asJsonObject.toCatalogItem()
            }.orEmpty()
            CatalogSection(
                id = section.stringValue("id") ?: "section-${items.hashCode()}",
                title = section.stringValue("title") ?: "Discovery",
                layout = section.stringValue("layout") ?: "carousel",
                items = items,
                endpoint = section.toEndpoint()
            )
        }.orEmpty()

        val actions = catalog.getAsJsonArray("actions")?.mapNotNull { itemElement ->
            if (!itemElement.isJsonObject) null else itemElement.asJsonObject.toCatalogItem()
        }.orEmpty()

        return CatalogPage(
            version = catalog.get("version")?.takeIf { it.isJsonPrimitive }?.asInt ?: 1,
            surface = catalog.stringValue("surface") ?: stringValue("surface") ?: "discovery",
            title = catalog.stringValue("title") ?: "Discovery",
            sections = sections,
            actions = actions,
            nextCursor = pageInfo?.stringValue("nextCursor")
        )
    }

    private fun JsonElement.stringValue(): String? {
        if (!isJsonPrimitive || !asJsonPrimitive.isString) return null
        return asString.trim().takeIf { it.isNotEmpty() }
    }

    private fun JsonObject.stringValue(name: String): String? = get(name)?.stringValue()

    private fun JsonElement.toTrackOrNull(): Track? {
        if (!isJsonObject) return null
        val json = asJsonObject

        // The Moodify server normalizeTrack emits `id` as the YouTube video ID.
        val id = json.stringValue("id") ?: return null
        val title = json.stringValue("title") ?: return null

        val artist = json.stringValue("artist")
            ?: json.getAsJsonArray("artists")
                ?.firstOrNull()
                ?.asJsonObject
                ?.stringValue("name")
            ?: "Unknown artist"

        val albumObject = json.get("album")?.takeIf { it.isJsonObject }?.asJsonObject
        val album = albumObject?.stringValue("title")
            ?: albumObject?.stringValue("name")
            ?: ""

        // Artwork: the server emits { artwork: { url: "..." } }
        val artwork = json.get("artwork")
            ?.takeIf { it.isJsonObject }
            ?.asJsonObject
            ?.stringValue("url")
            .orEmpty()

        // Duration: server normalizeTrack returns `durationSeconds` (already in seconds).
        // Fall back to `durationMs` divided by 1000 for older server builds.
        val durationSeconds: Int = run {
            val secs = json.get("durationSeconds")
                ?.takeIf { it.isJsonPrimitive }
                ?.asInt
            if (secs != null && secs > 0) return@run secs

            json.get("durationMs")
                ?.takeIf { it.isJsonPrimitive }
                ?.asLong
                ?.div(1000L)
                ?.toInt()
                ?: 0
        }

        return Track(
            id = id,
            title = title,
            artist = artist,
            album = album,
            albumArtUrl = artwork,
            durationSeconds = durationSeconds,
            // Playback resolves this YouTube video ID natively through InnerTube.
            audioUrl = ""
        )
    }
}
