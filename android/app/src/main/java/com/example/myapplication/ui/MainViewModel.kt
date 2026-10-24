package com.example.myapplication.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.data.Mood
import com.example.myapplication.data.MoodifyApiException
import com.example.myapplication.data.MusicRepository
import com.example.myapplication.data.Track
import com.example.myapplication.data.VibeWorld
import com.example.myapplication.player.PlaybackManager
import com.example.myapplication.player.PlaybackState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

@OptIn(FlowPreview::class)
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MusicRepository()

    // Use the singleton. getInstance(context) ensures it is initialized with
    // an Application context before the ExoPlayer is accessed.
    val playbackManager: PlaybackManager = PlaybackManager.getInstance(application)

    val playbackState: StateFlow<PlaybackState> = playbackManager.state

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _searchResults = MutableStateFlow<List<Track>>(emptyList())
    val searchResults: StateFlow<List<Track>> = _searchResults.asStateFlow()

    private val _trendingTracks = MutableStateFlow<List<Track>>(emptyList())
    val trendingTracks: StateFlow<List<Track>> = _trendingTracks.asStateFlow()

    private val _favoriteTrackIds = MutableStateFlow<Set<String>>(emptySet())

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

    init {
        loadTrendingTracks()

        viewModelScope.launch {
            _searchQuery
                .debounce(400)
                .collectLatest { query ->
                    if (query.isBlank()) {
                        _searchResults.value = emptyList()
                        _isSearching.value = false
                    } else {
                        _isSearching.value = true
                        try {
                            val results = repository.searchSongs(query)
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
            if (set.contains(track.id)) set - track.id else set + track.id
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
