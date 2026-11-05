package com.moodify.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import com.moodify.app.data.MoodifyApiException
import com.moodify.app.data.MusicRepository
import com.moodify.app.data.InnerTubeCatalogClient
import com.moodify.app.data.CatalogLocale
import com.moodify.app.data.CatalogCacheStore
import com.moodify.app.data.SettingsStore
import com.moodify.app.data.AppSettings
import com.moodify.app.data.SearchSuggestions
import com.moodify.app.data.CatalogPage
import com.moodify.app.data.BrowseEndpoint
import com.moodify.app.data.mergeCatalogPages
import com.moodify.app.data.LyricsDocument
import com.moodify.app.data.LyricsParser
import com.moodify.app.data.LyricsSearchResult
import com.moodify.app.data.LocalPlaylist
import com.moodify.app.data.LyricsStore
import com.moodify.app.data.PlaylistStore
import com.moodify.app.data.Track
import com.moodify.app.player.PlaybackManager
import com.moodify.app.player.FavoriteRecord
import com.moodify.app.player.PlaybackPersistence
import com.moodify.app.player.PlaybackState
import com.moodify.app.AppGraph
import com.moodify.app.data.ThemeMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** A snackbar message with an optional action (e.g. Undo). */
data class UiMessage(val text: String, val actionLabel: String? = null, val action: (() -> Unit)? = null)

@OptIn(FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@UnstableApi
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsStore = AppGraph.settingsStore
    private val _appSettings = MutableStateFlow(AppSettings())
    val appSettings: StateFlow<AppSettings> = _appSettings.asStateFlow()
    private val _settingsReady = MutableStateFlow(false)
    /** False until stored settings are read, so the first frame uses the user's real theme. */
    val settingsReady: StateFlow<Boolean> = _settingsReady.asStateFlow()

    init {
        // Assign the value before flipping ready, so ready never races ahead of real settings.
        viewModelScope.launch {
            // Before the first read, so the first catalogue load already uses the right country.
            runCatching { settingsStore.migrateRegionToAutomatic() }
            settingsStore.settings.collect { settings ->
                _appSettings.value = settings
                _settingsReady.value = true
            }
        }
    }

    private val repository = AppGraph.repository
    private val persistence = AppGraph.persistence
    private val playlistStore = AppGraph.playlistStore
    private val lyricsStore = AppGraph.lyricsStore

    /** One-shot feedback for the snackbar host (confirmations, errors, undo). */
    private val _messages = MutableSharedFlow<UiMessage>(extraBufferCapacity = 8)
    val messages: SharedFlow<UiMessage> = _messages.asSharedFlow()

    private fun say(text: String, actionLabel: String? = null, action: (() -> Unit)? = null) {
        _messages.tryEmit(UiMessage(text, actionLabel, action))
    }

    /** Downloaded tracks with metadata, refreshed whenever the set of downloads changes. */
    val downloadedTracks: StateFlow<List<Track>> = playbackManagerState()
        .map { it.downloadedTrackIds }
        .distinctUntilChanged()
        .mapLatest { runCatching { AppGraph.playbackManager.downloadedTracks() }.getOrDefault(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private fun playbackManagerState() = AppGraph.playbackManager.state

    // Use the singleton. getInstance(context) ensures it is initialized with
    // an Application context before the ExoPlayer is accessed.
    val playbackManager: PlaybackManager = PlaybackManager.getInstance(application)

    val playbackState: StateFlow<PlaybackState> = playbackManager.state

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _searchType = MutableStateFlow<String?>(null)
    val searchType: StateFlow<String?> = _searchType.asStateFlow()

    private val _searchResults = MutableStateFlow<List<Track>>(emptyList())
    val searchResults: StateFlow<List<Track>> = _searchResults.asStateFlow()

    private val _searchSuggestions = MutableStateFlow<List<String>>(emptyList())
    val searchSuggestions: StateFlow<List<String>> = _searchSuggestions.asStateFlow()

    private val _searchCatalog = MutableStateFlow<CatalogPage?>(null)
    val searchCatalog: StateFlow<CatalogPage?> = _searchCatalog.asStateFlow()

    private val _trendingTracks = MutableStateFlow<List<Track>>(emptyList())
    val trendingTracks: StateFlow<List<Track>> = _trendingTracks.asStateFlow()

    private val _exploreCatalog = MutableStateFlow<CatalogPage?>(null)
    val exploreCatalog: StateFlow<CatalogPage?> = _exploreCatalog.asStateFlow()

    private val _homeCatalog = MutableStateFlow<CatalogPage?>(null)
    val homeCatalog: StateFlow<CatalogPage?> = _homeCatalog.asStateFlow()

    private val _chartsCatalog = MutableStateFlow<CatalogPage?>(null)
    val chartsCatalog: StateFlow<CatalogPage?> = _chartsCatalog.asStateFlow()

    private val _moodsCatalog = MutableStateFlow<CatalogPage?>(null)
    val moodsCatalog: StateFlow<CatalogPage?> = _moodsCatalog.asStateFlow()

    private val _openedCatalog = MutableStateFlow<CatalogPage?>(null)
    val openedCatalog: StateFlow<CatalogPage?> = _openedCatalog.asStateFlow()

    private val _openedBrowseEndpoint = MutableStateFlow<BrowseEndpoint?>(null)
    private val _isLoadingBrowseMore = MutableStateFlow(false)
    val isLoadingBrowseMore: StateFlow<Boolean> = _isLoadingBrowseMore.asStateFlow()

    // Favorites are persisted with their track metadata (schema v6), so the
    // Library list survives restarts. The id set is also updated
    // optimistically on toggle so hearts flip instantly.
    private val _favoriteRecords = MutableStateFlow<List<FavoriteRecord>>(emptyList())
    private val _favoriteTrackIds = MutableStateFlow<Set<String>>(emptySet())

    // Metadata for catalog tracks seen this session. Only used to fill in
    // favorites saved before track metadata was persisted.
    private val _knownTracks = MutableStateFlow<Map<String, Track>>(emptyMap())

    val historyTracks: StateFlow<List<Track>> = playbackManager.observeListeningHistory()
        .map { entries -> entries.map { it.track } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val playlists: StateFlow<List<LocalPlaylist>> = playlistStore.observe()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // ── For you ───────────────────────────────────────────────────────────
    private val recommender = com.moodify.app.data.QuickPicksRecommender(
        com.moodify.app.data.InnerTubeCatalogClient(localeProvider = { AppGraph.catalogLocale }),
    )
    private val _forYouTracks = MutableStateFlow<List<Track>>(emptyList())
    /** Personal discoveries from the radios of the listener's most-played songs. */
    val forYouTracks: StateFlow<List<Track>> = _forYouTracks.asStateFlow()
    private var forYouJob: Job? = null

    fun refreshForYou() {
        forYouJob?.cancel()
        forYouJob = viewModelScope.launch {
            val mostPlayed = runCatching { playbackManager.mostPlayedTracks(12) }.getOrDefault(emptyList())
            val seeds = com.moodify.app.data.QuickPicksRecommender.pickSeeds(
                mostPlayed = mostPlayed,
                recent = historyTracks.value,
                liked = favoriteTracks.value,
            )
            launch { loadPersonalShelves() }
            if (seeds.isEmpty()) return@launch
            val known = historyTracks.value.mapTo(HashSet()) { it.id } + _favoriteTrackIds.value
            val picks = runCatching { recommender.load(seeds, known) }.getOrDefault(emptyList())
            if (picks.isNotEmpty()) {
                rememberTracks(picks)
                _forYouTracks.value = picks
            }
        }
    }

    /** Liked songs, most recently liked first. */
    val favoriteTracks: StateFlow<List<Track>> = combine(
        _favoriteRecords,
        _knownTracks,
    ) { records, known ->
        records.mapNotNull { record -> (record.track ?: known[record.trackId])?.copy(isFavorite = true) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isLoadingCatalog = MutableStateFlow(true)
    val isLoadingCatalog: StateFlow<Boolean> = _isLoadingCatalog.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore.asStateFlow()

    /** Search failures only; other surfaces report through their own state. */
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    /** Set when neither Home nor Explore could be loaded from network or cache. */
    private val _catalogError = MutableStateFlow<String?>(null)
    val catalogError: StateFlow<String?> = _catalogError.asStateFlow()

    /** True while at least one visible shelf is a cached copy because the network failed. */
    private val _catalogIsStale = MutableStateFlow(false)
    val catalogIsStale: StateFlow<Boolean> = _catalogIsStale.asStateFlow()

    private val _isOpeningBrowse = MutableStateFlow(false)
    val isOpeningBrowse: StateFlow<Boolean> = _isOpeningBrowse.asStateFlow()

    /** Failure opening or paging a browse page (album, artist, playlist). */
    private val _browseError = MutableStateFlow<String?>(null)
    val browseError: StateFlow<String?> = _browseError.asStateFlow()

    private val _lyrics = MutableStateFlow<LyricsDocument?>(null)
    val lyrics: StateFlow<LyricsDocument?> = _lyrics.asStateFlow()

    private val _lyricsErrorMessage = MutableStateFlow<String?>(null)
    val lyricsErrorMessage: StateFlow<String?> = _lyricsErrorMessage.asStateFlow()

    private val _isLoadingLyrics = MutableStateFlow(false)
    val isLoadingLyrics: StateFlow<Boolean> = _isLoadingLyrics.asStateFlow()
    private val _lyricsSearchResults = MutableStateFlow<List<LyricsSearchResult>>(emptyList())
    val lyricsSearchResults: StateFlow<List<LyricsSearchResult>> = _lyricsSearchResults.asStateFlow()
    private val _isSearchingLyrics = MutableStateFlow(false)
    val isSearchingLyrics: StateFlow<Boolean> = _isSearchingLyrics.asStateFlow()
    private val _lyricsSearchErrorMessage = MutableStateFlow<String?>(null)
    val lyricsSearchErrorMessage: StateFlow<String?> = _lyricsSearchErrorMessage.asStateFlow()
    private val _isTranslatingLyrics = MutableStateFlow(false)
    val isTranslatingLyrics: StateFlow<Boolean> = _isTranslatingLyrics.asStateFlow()
    private val _lyricsActionErrorMessage = MutableStateFlow<String?>(null)
    val lyricsActionErrorMessage: StateFlow<String?> = _lyricsActionErrorMessage.asStateFlow()
    private val lyricsCache = mutableMapOf<String, LyricsDocument?>()
    private val lyricsSyncUpgradeAttempted = mutableSetOf<String>()
    private var lyricsJob: Job? = null
    private var lyricsSearchJob: Job? = null
    private var lyricsTranslationJob: Job? = null

    init {
        viewModelScope.launch {
            persistence.observeFavorites().collect { records ->
                _favoriteRecords.value = records
                _favoriteTrackIds.value = records.mapTo(mutableSetOf()) { it.trackId }
            }
        }
        // Wait for stored settings so the first fetch/cache read uses the user's locale.
        viewModelScope.launch {
            settingsReady.first { it }
            val settings = appSettings.value
            AppGraph.catalogLocale = CatalogLocale(settings.language, settings.region).normalized()
            loadTrendingTracks()
            loadCatalogSurfaces()
        }

        // Build For-you picks once there is something to base them on.
        viewModelScope.launch {
            historyTracks.first { it.isNotEmpty() }
            refreshForYou()
        }

        viewModelScope.launch {
            appSettings.collect { settings ->
                AppGraph.catalogLocale = CatalogLocale(settings.language, settings.region).normalized()
                playbackManager.autoplayEnabled = settings.autoplay
                playbackManager.normalizeVolume = settings.normalizeVolume
                playbackManager.setLocale(settings.language, settings.region)
            }
        }

        viewModelScope.launch {
            playbackState
                .map { it.currentTrack?.id }
                .distinctUntilChanged()
                .collect {
                    lyricsJob?.cancel()
                    lyricsSearchJob?.cancel()
                    lyricsTranslationJob?.cancel()
                    _lyrics.value = null
                    _lyricsErrorMessage.value = null
                    _lyricsActionErrorMessage.value = null
                    _lyricsSearchResults.value = emptyList()
                    _lyricsSearchErrorMessage.value = null
                    _isSearchingLyrics.value = false
                    _isTranslatingLyrics.value = false
                    _isLoadingLyrics.value = false
                }
        }

        // A locale change is a catalog boundary, not a cosmetic preference:
        // reload region-sensitive shelves and let the Room page cache select
        // only entries written for the new language/country.
        viewModelScope.launch {
            settingsReady.first { it }
            appSettings
                .map { it.language to it.region }
                .distinctUntilChanged()
                .drop(1)
                .collect {
                    _searchResults.value = emptyList()
                    _searchCatalog.value = null
                    _exploreCatalog.value = null
                    _homeCatalog.value = null
                    _chartsCatalog.value = null
                    _moodsCatalog.value = null
                    _openedCatalog.value = null
                    _openedBrowseEndpoint.value = null
                    _isLoadingBrowseMore.value = false
                    loadTrendingTracks()
                    loadCatalogSurfaces()
                }
        }

        viewModelScope.launch {
            combine(_searchQuery, _searchType) { query, type -> query to type }
                .debounce(400)
                .collectLatest { (query, type) ->
                    if (query.isBlank()) {
                        _searchResults.value = emptyList()
                        _searchCatalog.value = null
                        _isSearching.value = false
                    } else {
                        _isSearching.value = true
                        try {
                            val page = repository.searchCatalog(query, type)
                            _searchCatalog.value = page
                            val results = page.sections
                                .flatMap { section -> section.items.mapNotNull { it.track } }
                                .distinctBy { it.id }
                                .take(30)
                            _searchResults.value = results.map { track ->
                                track.copy(isFavorite = _favoriteTrackIds.value.contains(track.id))
                            }
                            rememberTracks(results)
                            _errorMessage.value = null
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            _searchResults.value = emptyList()
                            _errorMessage.value = readableError(e)
                        } finally {
                            _isSearching.value = false
                        }
                    }
                }
        }

        viewModelScope.launch {
            _searchQuery
                .debounce(220)
                .distinctUntilChanged()
                .collectLatest { query ->
                    if (query.isBlank()) {
                        _searchSuggestions.value = emptyList()
                    } else {
                        _searchSuggestions.value = runCatching {
                            repository.searchSuggestions(query)
                        }.getOrDefault(SearchSuggestions()).queries
                    }
                }
        }
    }

    private var catalogJob: Job? = null

    private fun loadCatalogSurfaces() {
        catalogJob?.cancel()
        catalogJob = viewModelScope.launch {
            _isLoadingCatalog.value = true
            _catalogError.value = null
            // Each surface shows its cached page immediately, then refreshes
            // from the network. Network calls are cancellable, so the timeout
            // really bounds how long a slow connection can hold a refresh.
            val surfaces = listOf(
                repository.homeCatalog() to _homeCatalog,
                repository.exploreCatalog() to _exploreCatalog,
                repository.chartsCatalog() to _chartsCatalog,
                repository.moodsAndGenresCatalog() to _moodsCatalog,
            )
            val staleFlags = BooleanArray(surfaces.size)
            val failures = surfaces.mapIndexed { index, (source, target) ->
                async {
                    val outcome = withTimeoutOrNull(CATALOG_SURFACE_TIMEOUT_MS) {
                        runCatching {
                            source.collect { result ->
                                rememberCatalogTracks(result.page)
                                // Do not overwrite a chip-filtered Home with the unfiltered page.
                                if (target === _homeCatalog && _selectedHomeChip.value != null) return@collect
                                // A cached page's paging token belongs to an old YouTube session.
                                target.value = if (result.isStale) result.page.copy(nextCursor = null) else result.page
                                if (target === _homeCatalog) result.page.chips?.takeIf { it.isNotEmpty() }?.let { _homeChips.value = it }
                                staleFlags[index] = result.isStale
                                _catalogIsStale.value = staleFlags.any { it }
                            }
                        }
                    }
                    // A timeout leaves any cached page on screen but counts as stale.
                    if (outcome == null && target.value != null) staleFlags[index] = true
                    outcome?.exceptionOrNull() ?: if (outcome == null && target.value == null) {
                        java.util.concurrent.TimeoutException("Catalog request timed out")
                    } else null
                }
            }.awaitAll()
            _catalogIsStale.value = staleFlags.any { it }
            if (_homeCatalog.value == null && _exploreCatalog.value == null) {
                _catalogError.value = failures.filterNotNull().firstOrNull()
                    ?.let { readableError(it as? Exception ?: Exception(it)) }
                    ?: "Couldn't load music right now."
            }
            _isLoadingCatalog.value = false
        }
    }

    fun refreshCatalog() {
        loadCatalogSurfaces()
        refreshForYou()
    }

    fun openBrowse(endpoint: BrowseEndpoint) {
        val browseId = endpoint.browseId ?: return
        val pageType = endpoint.pageType.orEmpty()
        val surfaceAndTitle = when {
            pageType.contains("ARTIST", ignoreCase = true) -> "artist" to "Artist"
            pageType.contains("ALBUM", ignoreCase = true) || browseId.startsWith("MPRE") -> "album" to "Album"
            pageType.contains("PLAYLIST", ignoreCase = true) || browseId.startsWith("VL") -> "playlist" to "Playlist"
            else -> "browse" to "Browse"
        }
        viewModelScope.launch {
            _isOpeningBrowse.value = true
            _browseError.value = null
            try {
                val page = repository.browseCatalog(
                    browseId = browseId,
                    params = endpoint.params,
                    surface = surfaceAndTitle.first,
                    title = surfaceAndTitle.second,
                )
                rememberCatalogTracks(page)
                _openedBrowseEndpoint.value = endpoint
                _openedCatalog.value = page
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _browseError.value = readableError(e)
            } finally {
                _isOpeningBrowse.value = false
            }
        }
    }

    fun clearBrowseError() {
        _browseError.value = null
    }

    fun closeCatalog() {
        _browseError.value = null
        _openedCatalog.value = null
        _openedBrowseEndpoint.value = null
        _isLoadingBrowseMore.value = false
    }

    fun loadMoreBrowseCatalog() {
        val current = _openedCatalog.value ?: return
        val endpoint = _openedBrowseEndpoint.value ?: return
        val browseId = endpoint.browseId ?: return
        val continuation = current.nextCursor ?: return
        if (_isLoadingBrowseMore.value) return

        viewModelScope.launch {
            _isLoadingBrowseMore.value = true
            try {
                val next = repository.browseCatalog(
                    browseId = browseId,
                    params = endpoint.params,
                    surface = current.surface,
                    title = current.title,
                    continuation = continuation,
                )
                val merged = mergeCatalogPages(current, next)
                _openedCatalog.value = merged
                rememberCatalogTracks(merged)
                _browseError.value = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _browseError.value = readableError(e)
            } finally {
                _isLoadingBrowseMore.value = false
            }
        }
    }

    private fun loadTrendingTracks() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val tracks = repository.getTrendingSongs()
                _trendingTracks.value = tracks.map { track ->
                    track.copy(isFavorite = _favoriteTrackIds.value.contains(track.id))
                }
                rememberTracks(tracks)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Trending only seeds Quick picks; a failure must not surface
                // as a search error on another screen.
                _trendingTracks.value = emptyList()
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
        if (query.isBlank()) _errorMessage.value = null
    }

    fun onSearchTypeChange(type: String?) {
        _searchType.value = type
    }

    fun applySearchSuggestion(suggestion: String) = submitSearch(suggestion)

    fun setAutoplay(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setAutoplay(enabled) }
    }

    fun setNormalizeVolume(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setNormalizeVolume(enabled) }
    }

    fun completeOnboarding(language: String, region: String, moods: List<String>) {
        viewModelScope.launch { settingsStore.completeOnboarding(language, region, moods) }
    }

    /** Plays [track] and keeps going with its radio mix. */
    fun startRadio(track: Track) {
        playbackManager.startRadio(track)
        say("Starting radio from ${track.title}")
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { settingsStore.setThemeMode(mode) }
    }

    /** Called when the user commits a search (keyboard action or tapping a suggestion/recent). */
    fun submitSearch(query: String) {
        val normalized = query.trim()
        if (normalized.isBlank()) return
        _searchQuery.value = normalized
        viewModelScope.launch { settingsStore.addRecentSearch(normalized) }
    }

    fun removeRecentSearch(query: String) {
        viewModelScope.launch { settingsStore.removeRecentSearch(query) }
    }

    fun clearRecentSearches() {
        viewModelScope.launch { settingsStore.clearRecentSearches() }
    }

    fun setLocale(language: String, region: String) {
        viewModelScope.launch {
            settingsStore.setLocale(language, region)
        }
    }

    fun loadMoreSearchResults() {
        val query = _searchQuery.value.trim()
        val current = _searchCatalog.value
        val cursor = current?.nextCursor
        if (query.isBlank() || cursor.isNullOrBlank() || _isLoadingMore.value) return

        viewModelScope.launch {
            _isLoadingMore.value = true
            try {
                val next = repository.searchCatalog(query, _searchType.value, cursor)
                val merged = mergeCatalogPages(current, next)
                _searchCatalog.value = merged
                val tracks = merged.sections
                    .flatMap { section -> section.items.mapNotNull { it.track } }
                    .distinctBy { it.id }
                _searchResults.value = tracks.map { track ->
                    track.copy(isFavorite = _favoriteTrackIds.value.contains(track.id))
                }
                rememberTracks(tracks)
                _errorMessage.value = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _errorMessage.value = readableError(e)
            } finally {
                _isLoadingMore.value = false
            }
        }
    }

    fun startIntent(intent: String) {
        _searchQuery.value = intent.trim()
    }

    val favoriteIds: StateFlow<Set<String>> get() = _favoriteTrackIds

    fun toggleFavorite(track: Track) {
        val makeFavorite = !_favoriteTrackIds.value.contains(track.id)
        _favoriteTrackIds.update { set -> if (makeFavorite) set + track.id else set - track.id }
        val metadata = _knownTracks.value[track.id] ?: track
        viewModelScope.launch { persistence.setFavorite(metadata, makeFavorite) }
        if (makeFavorite) say("Added to Liked songs")
        else say("Removed from Liked songs", "Undo") { toggleFavorite(metadata) }
        _trendingTracks.update { list ->
            list.map { if (it.id == track.id) it.copy(isFavorite = !it.isFavorite) else it }
        }
        _searchResults.update { list ->
            list.map { if (it.id == track.id) it.copy(isFavorite = !it.isFavorite) else it }
        }
        _knownTracks.update { known ->
            val existing = known[track.id] ?: track
            known + (track.id to existing.copy(isFavorite = _favoriteTrackIds.value.contains(track.id)))
        }
    }

    fun clearHistory() {
        viewModelScope.launch { playbackManager.clearListeningHistory() }
    }

    /** Creates a playlist and, when [addTrack] is given, saves that track into it. */
    fun createPlaylist(name: String, addTrack: Track? = null) {
        val normalized = name.trim()
        if (normalized.isBlank()) return
        viewModelScope.launch {
            val playlist = playlistStore.create(normalized)
            addTrack?.let { playlistStore.addTrack(playlist.id, it) }
            say(if (addTrack != null) "Saved to $normalized" else "Created $normalized")
        }
    }

    /** Deletes a playlist, offering Undo that restores its name, songs and pin. */
    fun deletePlaylist(id: String) {
        val snapshot = playlists.value.firstOrNull { it.id == id }
        viewModelScope.launch { playlistStore.delete(id) }
        if (snapshot != null) {
            say("Deleted ${snapshot.name}", "Undo") {
                viewModelScope.launch {
                    val restored = playlistStore.create(snapshot.name)
                    snapshot.tracks.forEach { playlistStore.addTrack(restored.id, it) }
                    if (snapshot.isPinned) playlistStore.setPinned(restored.id, true)
                }
            }
        }
    }

    fun renamePlaylist(id: String, name: String) {
        val normalized = name.trim()
        if (normalized.isBlank()) return
        viewModelScope.launch { playlistStore.rename(id, normalized) }
    }

    fun setPlaylistPinned(id: String, isPinned: Boolean) {
        viewModelScope.launch { playlistStore.setPinned(id, isPinned) }
        say(if (isPinned) "Pinned to Home" else "Unpinned from Home")
    }

    fun addToPlaylist(playlistId: String, track: Track) {
        val name = playlists.value.firstOrNull { it.id == playlistId }?.name ?: "playlist"
        viewModelScope.launch { playlistStore.addTrack(playlistId, track) }
        say("Saved to $name")
    }

    fun removeFromPlaylist(playlistId: String, track: Track) {
        viewModelScope.launch { playlistStore.removeTrack(playlistId, track.id) }
        say("Removed from playlist", "Undo") { addToPlaylist(playlistId, track) }
    }

    fun removeFromQueue(index: Int) {
        val track = playbackState.value.queue.getOrNull(index) ?: return
        playbackManager.removeFromQueue(index)
        say("Removed ${track.title}", "Undo") { playbackManager.insertIntoQueue(track, index) }
    }

    /** Saves the whole queue as a new playlist. */
    fun saveQueueAsPlaylist(name: String) {
        val queue = playbackState.value.queue
        val normalized = name.trim()
        if (queue.isEmpty() || normalized.isBlank()) return
        viewModelScope.launch {
            val playlist = playlistStore.create(normalized)
            queue.forEach { playlistStore.addTrack(playlist.id, it) }
            say("Saved ${queue.size} songs to $normalized")
        }
    }

    /** Queued and running background downloads. */
    val activeDownloads: StateFlow<List<com.moodify.app.player.ActiveDownload>> =
        com.moodify.app.player.DownloadWorker.observeActive(getApplication())
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun downloadTrack(track: Track) {
        if (track.id in playbackState.value.downloadedTrackIds) return
        com.moodify.app.player.DownloadWorker.enqueue(getApplication(), listOf(track))
        say("Downloading ${track.title}")
    }

    /** Downloads every song in [tracks] that is not on the device yet. */
    fun downloadAll(tracks: List<Track>, label: String) {
        val missing = tracks.filterNot { it.id in playbackState.value.downloadedTrackIds }.distinctBy { it.id }
        if (missing.isEmpty()) {
            say("$label is already available offline")
            return
        }
        com.moodify.app.player.DownloadWorker.enqueue(getApplication(), missing)
        say("Downloading ${missing.size} songs from $label")
    }

    fun deleteDownload(track: Track) {
        com.moodify.app.player.DownloadWorker.cancel(getApplication(), track.id)
        playbackManager.deleteDownload(track.id)
        say("Removed download")
    }

    // ── Home: YouTube chips, infinite shelves, personal shelves ─────────────

    private val _homeChips = MutableStateFlow<List<com.moodify.app.data.CatalogChip>>(emptyList())
    val homeChips: StateFlow<List<com.moodify.app.data.CatalogChip>> = _homeChips.asStateFlow()
    private val _selectedHomeChip = MutableStateFlow<com.moodify.app.data.CatalogChip?>(null)
    val selectedHomeChip: StateFlow<com.moodify.app.data.CatalogChip?> = _selectedHomeChip.asStateFlow()
    private val _isLoadingHomeMore = MutableStateFlow(false)
    val isLoadingHomeMore: StateFlow<Boolean> = _isLoadingHomeMore.asStateFlow()
    private var homeChipJob: Job? = null

    /** Reloads Home for one of YouTube's chips; selecting the active chip again clears it. */
    fun selectHomeChip(chip: com.moodify.app.data.CatalogChip?) {
        val target = if (chip != null && chip.params == _selectedHomeChip.value?.params) null else chip
        _selectedHomeChip.value = target
        homeChipJob?.cancel()
        homeChipJob = viewModelScope.launch {
            _isLoadingCatalog.value = true
            try {
                val page = repository.homeForChip(target?.params)
                rememberCatalogTracks(page)
                _homeCatalog.value = page
                page.chips?.takeIf { it.isNotEmpty() }?.let { _homeChips.value = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                say("Couldn't load ${target?.title ?: "Home"}", "Retry") { selectHomeChip(target) }
            } finally {
                _isLoadingCatalog.value = false
            }
        }
    }

    /** Appends the next page of Home shelves (YouTube serves Home in pages). */
    fun loadMoreHome() {
        val current = _homeCatalog.value ?: return
        val cursor = current.nextCursor?.takeIf { it.isNotBlank() } ?: return
        if (_isLoadingHomeMore.value) return
        viewModelScope.launch {
            _isLoadingHomeMore.value = true
            try {
                val next = repository.homeContinuation(cursor, current.visitorData)
                rememberCatalogTracks(next)
                // A continuation only adds shelves; keep the chips and title.
                _homeCatalog.value = current.copy(
                    sections = (current.sections + next.sections).distinctBy { "${it.id}:${it.title}" },
                    nextCursor = next.nextCursor,
                    visitorData = current.visitorData,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("MoodifyHome", "continuation failed: ${e.message}")
                // Leave the cursor so the next scroll retries.
            } finally {
                _isLoadingHomeMore.value = false
            }
        }
    }

    /** Personal Home shelves built from the listener's own play history. */
    data class PersonalShelves(
        val keepListening: List<Track> = emptyList(),
        val forgotten: List<Track> = emptyList(),
        val becauseArtist: String? = null,
        val becauseTracks: List<Track> = emptyList(),
    )

    private val _personalShelves = MutableStateFlow(PersonalShelves())
    val personalShelves: StateFlow<PersonalShelves> = _personalShelves.asStateFlow()

    private suspend fun loadPersonalShelves() {
        val mostPlayed = runCatching { playbackManager.mostPlayedTracks(40) }.getOrDefault(emptyList())
        val threeWeeksAgo = System.currentTimeMillis() - 21L * 24 * 60 * 60 * 1000
        val forgotten = runCatching { playbackManager.forgottenTracks(threeWeeksAgo, minPlays = 2, limit = 12) }
            .getOrDefault(emptyList())
        // The artist the listener plays most (by first-billed name).
        val topArtist = mostPlayed
            .mapNotNull { track -> com.moodify.app.data.QuickPicksRanker.artistKeys(track.artist).firstOrNull()?.let { it to track } }
            .groupBy({ it.first }, { it.second })
            .maxByOrNull { it.value.size }
            ?.takeIf { it.value.size >= 2 }
        var becauseTracks = emptyList<Track>()
        var becauseArtist: String? = null
        if (topArtist != null) {
            val seed = topArtist.value.first()
            val radio = runCatching { repository.upNext(seed.id, "RDAMVM${seed.id}").tracks }.getOrDefault(emptyList())
            becauseTracks = radio.filterNot { candidate ->
                com.moodify.app.data.QuickPicksRanker.artistKeys(candidate.artist).contains(topArtist.key)
            }.take(15)
            becauseArtist = seed.artist.split(",", "&").first().trim()
            rememberTracks(becauseTracks)
        }
        _personalShelves.value = PersonalShelves(
            keepListening = mostPlayed.take(12),
            forgotten = forgotten,
            becauseArtist = becauseArtist.takeIf { becauseTracks.isNotEmpty() },
            becauseTracks = becauseTracks,
        )
    }

    // ── Related music for the playing song ─────────────────────────────────

    data class RelatedState(val trackId: String? = null, val page: CatalogPage? = null, val isLoading: Boolean = false)

    private val _related = MutableStateFlow(RelatedState())
    val related: StateFlow<RelatedState> = _related.asStateFlow()

    /** Loads YouTube Music's "Related" page (similar songs, artists, albums) for [track]. */
    fun loadRelated(track: Track) {
        if (_related.value.trackId == track.id && (_related.value.page != null || _related.value.isLoading)) return
        _related.value = RelatedState(trackId = track.id, isLoading = true)
        viewModelScope.launch {
            val page = runCatching {
                val relatedId = repository.upNext(track.id).relatedBrowseId ?: return@runCatching null
                repository.browseCatalog(browseId = relatedId, surface = "browse", title = "Related")
            }.getOrNull()
            page?.let(::rememberCatalogTracks)
            if (_related.value.trackId == track.id) _related.value = RelatedState(track.id, page, isLoading = false)
        }
    }

    fun setSleepTimer(minutes: Int?) {
        playbackManager.setSleepTimer(minutes)
        say(
            when (minutes) {
                null -> "Sleep timer off"
                0 -> "Pausing at the end of this song"
                else -> "Pausing in $minutes min"
            },
        )
    }

    fun playTrack(track: Track, queue: List<Track>) {
        playbackManager.playTrack(track, queue)
    }

    fun playNext(track: Track) {
        if (playbackState.value.currentTrack == null) {
            playbackManager.playTrack(track, listOf(track))
            return
        }
        playbackManager.addToQueue(track, playNext = true)
        say("Playing next: ${track.title}")
    }

    fun addToQueue(track: Track) {
        if (playbackState.value.currentTrack == null) {
            playbackManager.playTrack(track, listOf(track))
            return
        }
        playbackManager.addToQueue(track, playNext = false)
        say("Added to queue")
    }

    fun loadLyrics(track: Track, forceRefresh: Boolean = false) {
        if (forceRefresh) {
            lyricsCache.remove(track.id)
            lyricsSyncUpgradeAttempted.remove(track.id)
            lyricsJob?.cancel()
        }
        var fallbackDocument = lyricsCache[track.id]
        var upgradeAlreadyReserved = false
        if (lyricsCache.containsKey(track.id)) {
            _lyrics.value = fallbackDocument
            _lyricsErrorMessage.value = null
            _isLoadingLyrics.value = false
            if (fallbackDocument == null || !shouldAutoUpgradeLyrics(fallbackDocument) ||
                !lyricsSyncUpgradeAttempted.add(track.id)
            ) return
            upgradeAlreadyReserved = true
        }

        lyricsJob?.cancel()
        lyricsJob = viewModelScope.launch {
            _isLoadingLyrics.value = fallbackDocument == null
            if (fallbackDocument == null) _lyrics.value = null
            _lyricsErrorMessage.value = null
            try {
                if (!forceRefresh && fallbackDocument == null) {
                    fallbackDocument = lyricsStore.read(track.id)
                    fallbackDocument?.let { cached ->
                        lyricsCache[track.id] = cached
                        if (playbackState.value.currentTrack?.id == track.id) {
                            _lyrics.value = cached
                            _isLoadingLyrics.value = false
                        }
                    }
                }

                if (fallbackDocument != null) {
                    if (!shouldAutoUpgradeLyrics(fallbackDocument!!)) return@launch
                    if (!upgradeAlreadyReserved && !lyricsSyncUpgradeAttempted.add(track.id)) return@launch
                } else {
                    // A missing or freshly fetched plain result is checked once
                    // per ViewModel session. The explicit Refresh action resets
                    // this marker and lets the user retry immediately.
                    if (!upgradeAlreadyReserved && !lyricsSyncUpgradeAttempted.add(track.id)) return@launch
                }

                val fetched = repository.getLyrics(track) ?: youTubeMusicLyrics(track)
                // Keep already displayed/cacheable lyrics during provider
                // failures or when no better timed candidate exists.
                val document = if (fetched?.isSynced == true || fallbackDocument == null) {
                    fetched
                } else {
                    fallbackDocument
                }
                lyricsCache[track.id] = document
                document?.let { lyricsStore.write(track.id, it) }
                if (playbackState.value.currentTrack?.id == track.id) {
                    _lyrics.value = document
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (fallbackDocument == null && playbackState.value.currentTrack?.id == track.id) {
                    _lyricsErrorMessage.value = "Lyrics are temporarily unavailable. Try again later."
                }
            } finally {
                if (playbackState.value.currentTrack?.id == track.id) {
                    _isLoadingLyrics.value = false
                }
            }
        }
    }

    /** YouTube Music's own (plain) lyrics, used when no lyrics provider has the song. */
    private suspend fun youTubeMusicLyrics(track: Track): LyricsDocument? = runCatching {
        val lyricsId = repository.upNext(track.id).lyricsBrowseId ?: return@runCatching null
        val text = repository.youTubeLyrics(lyricsId) ?: return@runCatching null
        // YouTube appends a "Source: ..." credit line; keep it out of the lyrics.
        val body = text.lines().filterNot { it.trim().startsWith("Source:", ignoreCase = true) }.joinToString("\n")
        LyricsParser.parse(body, source = "YouTube Music")
    }.getOrNull()

    private fun shouldAutoUpgradeLyrics(document: LyricsDocument): Boolean =
        !document.isSynced &&
            !document.source.contains("selected", ignoreCase = true) &&
            !document.source.contains("edited", ignoreCase = true) &&
            !document.source.contains("translated", ignoreCase = true)

    fun refreshLyrics(track: Track) = loadLyrics(track, forceRefresh = true)

    fun searchLyrics(track: Track, title: String, artist: String) {
        lyricsSearchJob?.cancel()
        lyricsSearchJob = viewModelScope.launch {
            _isSearchingLyrics.value = true
            _lyricsSearchErrorMessage.value = null
            _lyricsSearchResults.value = emptyList()
            try {
                val results = repository.searchLyrics(track, title.trim(), artist.trim())
                _lyricsSearchResults.value = results
                if (results.isEmpty()) _lyricsSearchErrorMessage.value = "No matching lyrics found. Try another title or artist."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _lyricsSearchErrorMessage.value = "Lyrics search is temporarily unavailable. Try again."
            } finally {
                _isSearchingLyrics.value = false
            }
        }
    }

    fun selectLyricsSearchResult(track: Track, result: LyricsSearchResult) {
        val document = result.document.copy(source = "${result.document.source} · selected")
        lyricsCache[track.id] = document
        lyricsSyncUpgradeAttempted.add(track.id)
        lyricsStoreWrite(track.id, document)
        if (playbackState.value.currentTrack?.id == track.id) {
            _lyrics.value = document
            _lyricsErrorMessage.value = null
        }
        _lyricsSearchResults.value = emptyList()
        _lyricsSearchErrorMessage.value = null
    }

    fun saveLyrics(track: Track, rawLyrics: String) {
        val document = LyricsParser.parse(rawLyrics, source = "Edited")
        if (document == null) {
            _lyricsActionErrorMessage.value = "Add lyrics text or timed LRC lines before saving."
            return
        }
        lyricsCache[track.id] = document
        lyricsSyncUpgradeAttempted.add(track.id)
        lyricsStoreWrite(track.id, document)
        if (playbackState.value.currentTrack?.id == track.id) {
            _lyrics.value = document
            _lyricsErrorMessage.value = null
            _lyricsActionErrorMessage.value = null
        }
    }

    fun translateLyrics(track: Track, targetLanguage: String) {
        val original = _lyrics.value ?: return
        lyricsTranslationJob?.cancel()
        lyricsTranslationJob = viewModelScope.launch {
            _isTranslatingLyrics.value = true
            _lyricsActionErrorMessage.value = null
            try {
                val translated = repository.translateLyrics(original, targetLanguage)
                lyricsCache[track.id] = translated
                lyricsStore.write(track.id, translated)
                if (playbackState.value.currentTrack?.id == track.id) _lyrics.value = translated
                settingsStore.setShowLyricsTranslation(true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _lyricsActionErrorMessage.value = e.message?.takeIf(String::isNotBlank)
                    ?: "Translation is temporarily unavailable. Try again."
            } finally {
                _isTranslatingLyrics.value = false
            }
        }
    }

    fun setLyricsSyncOffset(offsetMs: Int) {
        viewModelScope.launch { settingsStore.setLyricsSyncOffset(offsetMs) }
    }

    fun setShowLyricsControls(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setShowLyricsControls(enabled) }
    }

    fun setShowLyricsTranslation(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setShowLyricsTranslation(enabled) }
    }

    fun setShowLyricsRomanization(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setShowLyricsRomanization(enabled) }
    }

    private fun lyricsStoreWrite(trackId: String, document: LyricsDocument) {
        viewModelScope.launch { lyricsStore.write(trackId, document) }
    }

    private fun readableError(e: Exception): String = when (e) {
        is MoodifyApiException -> e.message ?: "The music provider is unavailable."
        else -> e.message?.takeIf { it.isNotBlank() } ?: "Could not reach the music provider. Check your connection and try again."
    }

    private companion object {
        const val CATALOG_SURFACE_TIMEOUT_MS = 15_000L
    }

    private fun rememberTracks(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        _knownTracks.update { known ->
            known + tracks.associateBy { it.id }.mapValues { (_, track) ->
                track.copy(isFavorite = _favoriteTrackIds.value.contains(track.id))
            }
        }
    }

    private fun rememberCatalogTracks(page: CatalogPage) {
        rememberTracks(
            page.sections.flatMap { section -> section.items.mapNotNull { it.track } } +
                page.actions.mapNotNull { it.track }
        )
    }

    // ViewModel is not responsible for releasing PlaybackManager —
    // it is a singleton tied to the process lifetime.
    override fun onCleared() {
        super.onCleared()
    }
}
