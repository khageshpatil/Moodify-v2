package com.example.myapplication.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.data.Mood
import com.example.myapplication.data.MoodifyApiException
import com.example.myapplication.data.MusicRepository
import com.example.myapplication.data.CatalogPage
import com.example.myapplication.data.BrowseEndpoint
import com.example.myapplication.data.Track
import com.example.myapplication.data.VibeWorld
import com.example.myapplication.player.PlaybackManager
import com.example.myapplication.player.PlaybackPersistence
import com.example.myapplication.player.PlaybackState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

@OptIn(FlowPreview::class)
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MusicRepository()
    private val persistence = PlaybackPersistence(application)

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

    val favoriteTracks: StateFlow<List<Track>> = combine(
        _trendingTracks,
        _searchResults,
        _favoriteTrackIds
    ) { trending, search, favIds ->
        (trending + search).distinctBy { it.id }
            .filter { favIds.contains(it.id) }
            .map { it.copy(isFavorite = true) }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _lyrics = MutableStateFlow<String?>(null)
    val lyrics: StateFlow<String?> = _lyrics.asStateFlow()

    private val _isLoadingLyrics = MutableStateFlow(false)
    val isLoadingLyrics: StateFlow<Boolean> = _isLoadingLyrics.asStateFlow()

    init {
        loadTrendingTracks()
        loadCatalogSurfaces()

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
            _exploreCatalog.value = explore.await()
            _homeCatalog.value = home.await()
            _chartsCatalog.value = charts.await()
        }
    }

    fun openBrowse(endpoint: BrowseEndpoint) {
        val browseId = endpoint.browseId ?: return
        viewModelScope.launch {
            runCatching { repository.browseCatalog(browseId, endpoint.params) }
                .onSuccess { _openedCatalog.value = it }
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
    }

    fun playTrack(track: Track, queue: List<Track>) {
        playbackManager.playTrack(track, queue)
    }

    fun loadLyrics(track: Track) {
        viewModelScope.launch {
            _isLoadingLyrics.value = true
            _lyrics.value = null
            try {
                _lyrics.value = repository.getLyrics(track.id)
            } catch (e: Exception) {
                _errorMessage.value = readableError(e)
            } finally {
                _isLoadingLyrics.value = false
            }
        }
    }

    private fun readableError(e: Exception): String = when (e) {
        is MoodifyApiException -> e.message ?: "Moodify music service is unavailable."
        else -> "Could not reach Moodify music service. Check the server connection and try again."
    }

    // ViewModel is not responsible for releasing PlaybackManager —
    // it is a singleton tied to the process lifetime.
    override fun onCleared() {
        super.onCleared()
    }
}
