package com.moodify.app.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.IOException

/**
 * Android catalog repository.
 *
 * The repository is provider-shaped at the boundary and
 * Moodify-shaped everywhere else: UI code receives Track/CatalogPage and
 * never sees Retrofit, server DTOs, cookies, or raw InnerTube JSON.
 */
class MusicRepository(
    private val catalogClient: InnerTubeCatalogClient = InnerTubeCatalogClient(),
    private val lyricsRepository: NativeLyricsRepository = NativeLyricsRepository(),
    private val catalogCache: CatalogCacheStore? = null,
    private val localeProvider: () -> CatalogLocale = { CatalogLocale.fromDefault() },
) {
    suspend fun searchSongs(query: String): List<Track> {
        val page = searchCatalog(query)
        val tracks = page.sections
            .flatMap { section -> section.items.mapNotNull { it.track } }
            .distinctBy { it.id }
            .take(30)
        if (tracks.isEmpty()) {
            throw CatalogException("No songs found for \"${query.trim()}\"")
        }
        return tracks
    }

    suspend fun searchSuggestions(query: String): SearchSuggestions =
        catalogClient.searchSuggestions(query)

    suspend fun getTrendingSongs(): List<Track> =
        searchSongs("trending music")

    /**
     * Lyrics are resolved directly on the device and cached by the ViewModel.
     */
    suspend fun getLyrics(trackId: String): String? = null

    suspend fun getLyrics(track: Track): LyricsDocument? = lyricsRepository.get(track)

    suspend fun searchLyrics(track: Track, title: String, artist: String): List<LyricsSearchResult> =
        lyricsRepository.search(title, artist, track.album, track.durationSeconds, track.id)

    suspend fun translateLyrics(document: LyricsDocument, targetLanguage: String): LyricsDocument =
        lyricsRepository.translate(document, targetLanguage)

    suspend fun getExploreCatalog(): CatalogPage = cached("explore") { catalogClient.explore() }

    suspend fun getMoodsAndGenresCatalog(): CatalogPage =
        cached("moods-and-genres") { catalogClient.moodsAndGenres() }

    suspend fun getHomeCatalog(): CatalogPage = cached("home") { catalogClient.home() }

    suspend fun getChartsCatalog(): CatalogPage = cached("charts") { catalogClient.charts() }

    suspend fun homeForChip(chipParams: String?): CatalogPage = catalogClient.homeForChip(chipParams)
    suspend fun homeContinuation(cursor: String, visitor: String?): CatalogPage =
        catalogClient.homeContinuation(cursor, visitor)
    suspend fun upNext(videoId: String, playlistId: String? = null): UpNextPage = catalogClient.upNext(videoId, playlistId)
    suspend fun youTubeLyrics(lyricsBrowseId: String): String? = catalogClient.youTubeLyrics(lyricsBrowseId)

    // Stale-while-revalidate variants for the main surfaces. These emit the
    // cached page immediately (so shelves appear on a cold or offline start),
    // then the fresh network page when it arrives.
    fun homeCatalog(): Flow<CatalogResult> = cacheThenNetwork("home") { catalogClient.home() }
    fun exploreCatalog(): Flow<CatalogResult> = cacheThenNetwork("explore") { catalogClient.explore() }
    fun chartsCatalog(): Flow<CatalogResult> = cacheThenNetwork("charts") { catalogClient.charts() }
    fun moodsAndGenresCatalog(): Flow<CatalogResult> =
        cacheThenNetwork("moods-and-genres") { catalogClient.moodsAndGenres() }

    suspend fun browseCatalog(
        browseId: String,
        params: String? = null,
        surface: String = "browse",
        title: String = "Browse",
        continuation: String? = null,
    ): CatalogPage = cached(
        "browse:$surface:$browseId:${params.orEmpty()}:${continuation.orEmpty()}",
    ) {
        catalogClient.browse(
            browseId = browseId,
            params = params,
            continuation = continuation,
            surface = surface,
            title = title,
        )
    }

    suspend fun searchCatalog(
        query: String,
        type: String? = null,
        cursor: String? = null,
    ): CatalogPage = cached(
        "search:${query.trim().lowercase()}:${type.orEmpty()}:${cursor.orEmpty()}",
    ) { catalogClient.search(query, type, cursor) }

    /**
     * Emits the cached page first (marked [CatalogResult.isStale]) when one
     * exists for this locale, then the network page. A network failure is
     * thrown only when there was nothing cached to show; otherwise the
     * flow completes with the stale page as its last value.
     */
    private fun cacheThenNetwork(key: String, fetch: suspend () -> CatalogPage): Flow<CatalogResult> = flow {
        val cache = catalogCache
        val locale = localeProvider().normalized()
        val stale = cache?.read(key, locale)
        if (stale != null) emit(CatalogResult(stale, isStale = true))
        val fresh = try {
            fetch()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (stale == null) throw failure
            null
        }
        if (fresh != null) {
            cache?.write(key, locale, fresh)
            emit(CatalogResult(fresh, isStale = false))
        }
    }

    private suspend fun cached(key: String, fetch: suspend () -> CatalogPage): CatalogPage {
        val cache = catalogCache
        val locale = localeProvider().normalized()
        return try {
            fetch().also { page -> cache?.write(key, locale, page) }
        } catch (failure: Exception) {
            cache?.read(key, locale)?.let { stale ->
                return stale
            }
            throw failure
        }
    }
}

/**
 * Kept as a compatibility name for the existing ViewModel error mapping.
 * New Android provider failures should use CatalogException instead.
 */
class MoodifyApiException(message: String, val statusCode: Int? = null) : IOException(message)

/** A catalog page plus whether it came from the offline cache rather than the network. */
data class CatalogResult(val page: CatalogPage, val isStale: Boolean)
