package com.example.myapplication.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.data.Mood
import com.example.myapplication.data.MoodifyApiException
import com.example.myapplication.data.MusicRepository
import com.example.myapplication.data.CatalogPage
import com.example.myapplication.data.BrowseEndpoint
import com.example.myapplication.data.LyricsDocument
import com.example.myapplication.data.LocalPlaylist
import com.example.myapplication.data.LyricsStore
import com.example.myapplication.data.PlaylistStore
import com.example.myapplication.data.Track
import com.example.myapplication.data.VibeWorld
import com.example.myapplication.player.PlaybackManager
import com.example.myapplication.player.PlaybackPersistence
import com.example.myapplication.player.PlaybackState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

@OptIn(FlowPreview::class)
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MusicRepository()
    private val persistence = PlaybackPersistence(application)
    private val playlistStore = PlaylistStore(application)
    private val lyricsStore = LyricsStore(application)

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

    private val _openedCatalog = MutableStateFlow<CatalogPage?>(null)
    val openedCatalog: StateFlow<CatalogPage?> = _openedCatalog.asStateFlow()

    private val _favoriteTrackIds = MutableStateFlow(persistence.loadFavoriteIds())

    // Keep metadata for every catalog track visited during this session. The
    // favorite ids are persisted separately, so a favorite from Home/Explore
    // remains useful in Library after its shelf leaves composition.
    private val _knownTracks = MutableStateFlow<Map<String, Track>>(emptyMap())

    private val _historyTracks = MutableStateFlow(playbackManager.listeningHistory().map { it.track })
    val historyTracks: StateFlow<List<Track>> = _historyTracks.asStateFlow()

    private val _playlists = MutableStateFlow(playlistStore.list())
    val playlists: StateFlow<List<LocalPlaylist>> = _playlists.asStateFlow()

    val favoriteTracks: StateFlow<List<Track>> = combine(
        _knownTracks,
        _favoriteTrackIds
    ) { known, favIds ->
        known.values
            .filter { favIds.contains(it.id) }
            .sortedBy { it.title.lowercase() }
            .map { it.copy(isFavorite = true) }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _lyrics = MutableStateFlow<LyricsDocument?>(null)
    val lyrics: StateFlow<LyricsDocument?> = _lyrics.asStateFlow()

    private val _isLoadingLyrics = MutableStateFlow(false)
    val isLoadingLyrics: StateFlow<Boolean> = _isLoadingLyrics.asStateFlow()
    private val lyricsCache = mutableMapOf<String, LyricsDocument?>()
    private var lyricsJob: Job? = null

    init {
        loadTrendingTracks()
        loadCatalogSurfaces()

        viewModelScope.launch {
            playbackState
                .map { it.currentTrack?.id }
                .distinctUntilChanged()
                .collect {
                    lyricsJob?.cancel()
                    _lyrics.value = null
                    _isLoadingLyrics.value = false
                    _historyTracks.value = playbackManager.listeningHistory().map { entry -> entry.track }
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
    }

    private fun loadCatalogSurfaces() {
        viewModelScope.launch {
            val explore = async { runCatching { repository.getExploreCatalog() }.getOrNull() }
            val home = async { runCatching { repository.getHomeCatalog() }.getOrNull() }
            val charts = async { runCatching { repository.getChartsCatalog() }.getOrNull() }
            _exploreCatalog.value = explore.await()?.also(::rememberCatalogTracks)
            _homeCatalog.value = home.await()?.also(::rememberCatalogTracks)
            _chartsCatalog.value = charts.await()?.also(::rememberCatalogTracks)
        }
    }

    fun openBrowse(endpoint: BrowseEndpoint) {
        val browseId = endpoint.browseId ?: return
        viewModelScope.launch {
            runCatching { repository.browseCatalog(browseId, endpoint.params) }
                .onSuccess {
                    rememberCatalogTracks(it)
                    _openedCatalog.value = it
                }
                .onFailure { _errorMessage.value = readableError(it as? Exception ?: Exception(it)) }
        }
    }

    fun closeCatalog() {
        _openedCatalog.value = null
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
                _errorMessage.value = null
            } catch (e: Exception) {
                _trendingTracks.value = emptyList()
                _errorMessage.value = readableError(e)
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

    fun selectMood(mood: Mood) {
        viewModelScope.launch {
            _isSearching.value = true
            _searchQuery.value = mood.name
            try {
                val results = repository.searchSongs(mood.searchQuery)
                _searchResults.value = results.map { t ->
                    t.copy(isFavorite = _favoriteTrackIds.value.contains(t.id))
                }
                rememberTracks(results)
                _errorMessage.value = null
            } catch (e: Exception) {
                _searchResults.value = emptyList()
                _errorMessage.value = readableError(e)
            } finally {
                _isSearching.value = false
            }
        }
    }

    fun selectVibe(vibe: VibeWorld) {
        viewModelScope.launch {
            _isSearching.value = true
            _searchQuery.value = vibe.title
            try {
                val results = repository.searchSongs(vibe.searchQuery)
                _searchResults.value = results.map { t ->
                    t.copy(isFavorite = _favoriteTrackIds.value.contains(t.id))
                }
                rememberTracks(results)
                _errorMessage.value = null
            } catch (e: Exception) {
                _searchResults.value = emptyList()
                _errorMessage.value = readableError(e)
            } finally {
                _isSearching.value = false
            }
        }
    }

    fun toggleFavorite(track: Track) {
        _favoriteTrackIds.update { set ->
            val updated = if (set.contains(track.id)) set - track.id else set + track.id
            persistence.saveFavoriteIds(updated)
            updated
        }
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
        playbackManager.clearListeningHistory()
        _historyTracks.value = emptyList()
    }

    fun createPlaylist(name: String): LocalPlaylist? {
        val normalized = name.trim()
        if (normalized.isBlank()) return null
        return playlistStore.create(normalized).also { _playlists.value = playlistStore.list() }
    }

    fun deletePlaylist(id: String) {
        playlistStore.delete(id)
        _playlists.value = playlistStore.list()
    }

    fun addToPlaylist(playlistId: String, track: Track) {
        playlistStore.addTrack(playlistId, track)
        _playlists.value = playlistStore.list()
    }

    fun removeFromPlaylist(playlistId: String, trackId: String) {
        playlistStore.removeTrack(playlistId, trackId)
        _playlists.value = playlistStore.list()
    }

    fun playTrack(track: Track, queue: List<Track>) {
        playbackManager.playTrack(track, queue)
    }

    fun loadLyrics(track: Track) {
        lyricsCache[track.id]?.let {
            _lyrics.value = it
            _isLoadingLyrics.value = false
            return
        }
        if (lyricsCache.containsKey(track.id)) {
            _lyrics.value = null
            _isLoadingLyrics.value = false
            return
        }

        lyricsJob?.cancel()
        lyricsJob = viewModelScope.launch {
            _isLoadingLyrics.value = true
            _lyrics.value = null
            try {
                lyricsStore.read(track.id)?.let { cached ->
                    lyricsCache[track.id] = cached
                    if (playbackState.value.currentTrack?.id == track.id) {
                        _lyrics.value = cached
                    }
                    return@launch
                }
                val document = repository.getLyrics(track)
                lyricsCache[track.id] = document
                document?.let { lyricsStore.write(track.id, it) }
                if (playbackState.value.currentTrack?.id == track.id) {
                    _lyrics.value = document
                }
            } catch (e: Exception) {
                if (playbackState.value.currentTrack?.id == track.id) {
                    _errorMessage.value = readableError(e)
                }
            } finally {
                if (playbackState.value.currentTrack?.id == track.id) {
                    _isLoadingLyrics.value = false
                }
            }
        }
    }

    private fun readableError(e: Exception): String = when (e) {
        is MoodifyApiException -> e.message ?: "Moodify music service is unavailable."
        else -> "Could not reach Moodify music service. Check the server connection and try again."
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

    private fun mergeCatalogPages(current: CatalogPage, next: CatalogPage): CatalogPage {
        val mergedSections = (current.sections + next.sections)
            .groupBy { it.id }
            .values
            .map { sections ->
                val first = sections.first()
                first.copy(items = sections.flatMap { it.items }.distinctBy { it.id })
            }
        return current.copy(
            sections = mergedSections,
            actions = (current.actions + next.actions).distinctBy { it.id },
            nextCursor = next.nextCursor,
        )
    }

    // ViewModel is not responsible for releasing PlaybackManager —
    // it is a singleton tied to the process lifetime.
    override fun onCleared() {
        super.onCleared()
    }
}
