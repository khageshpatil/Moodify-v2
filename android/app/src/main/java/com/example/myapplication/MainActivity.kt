package com.example.myapplication

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myapplication.player.MoodifyPlaybackService
import com.example.myapplication.player.PlaybackManager
import com.example.myapplication.ui.MainViewModel
import com.example.myapplication.ui.components.BottomNavBar
import com.example.myapplication.ui.components.FullScreenPlayerSheet
import com.example.myapplication.ui.components.MiniPlayerBar
import com.example.myapplication.ui.components.NavTab
import com.example.myapplication.ui.components.TopHeader
import com.example.myapplication.ui.screens.DiscoverScreen
import com.example.myapplication.ui.screens.AboutScreen
import com.example.myapplication.ui.screens.CatalogDetailScreen
import com.example.myapplication.ui.screens.ModernHomeScreen
import com.example.myapplication.ui.screens.ModernLibraryScreen
import com.example.myapplication.ui.theme.MoodifyTheme
import androidx.core.view.WindowCompat

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ── Initialize singleton PlaybackManager with Application context ──
        // Must happen before ViewModel accesses PlaybackManager.instance.
        val playbackManager = PlaybackManager.getInstance(applicationContext)

        // ── Wire the foreground service starter ───────────────────────────
        // PlaybackManager calls this when playback begins so the foreground
        // service keeps the process alive when the app is backgrounded.
        playbackManager.serviceStarter = {
            val serviceIntent = Intent(this, MoodifyPlaybackService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        }

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        )

        // ── Android 13+ runtime notification permission ────────────────────
        // Without this the foreground service notification is silently dropped.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
            }
        }

        setContent {
            val preferences = remember { getSharedPreferences("moodify-ui", MODE_PRIVATE) }
            val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
            var darkTheme by rememberSaveable { mutableStateOf(preferences.getBoolean("dark-theme", systemDark)) }
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }
            MoodifyTheme(darkTheme = darkTheme) {
                MoodifyApp(
                    darkTheme = darkTheme,
                    onDarkThemeChange = { next ->
                        darkTheme = next
                        preferences.edit().putBoolean("dark-theme", next).apply()
                    }
                )
            }
        }
    }
}

@Composable
fun MoodifyApp(
    viewModel: MainViewModel = viewModel(),
    darkTheme: Boolean = true,
    onDarkThemeChange: (Boolean) -> Unit = {}
) {
    var currentRoute by remember { mutableStateOf(NavTab.Home.route) }
    var isPlayerExpanded by remember { mutableStateOf(false) }
    val playerScope = rememberCoroutineScope()

    val playbackState by viewModel.playbackState.collectAsState()
    val trendingTracks by viewModel.trendingTracks.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val searchType by viewModel.searchType.collectAsState()
    val searchCatalog by viewModel.searchCatalog.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val favoriteTracks by viewModel.favoriteTracks.collectAsState()
    val historyTracks by viewModel.historyTracks.collectAsState()
    val playlists by viewModel.playlists.collectAsState()
    val isLoadingCatalog by viewModel.isLoading.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()
    val isLoadingMore by viewModel.isLoadingMore.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val exploreCatalog by viewModel.exploreCatalog.collectAsState()
    val homeCatalog by viewModel.homeCatalog.collectAsState()
    val chartsCatalog by viewModel.chartsCatalog.collectAsState()
    val openedCatalog by viewModel.openedCatalog.collectAsState()
    val lyrics by viewModel.lyrics.collectAsState()
    val isLoadingLyrics by viewModel.isLoadingLyrics.collectAsState()

    val catalogTracks = remember(exploreCatalog, homeCatalog, chartsCatalog, searchResults, trendingTracks) {
        (searchResults + trendingTracks +
            exploreCatalog?.sections.orEmpty().flatMap { it.items.mapNotNull { item -> item.track } } +
            homeCatalog?.sections.orEmpty().flatMap { it.items.mapNotNull { item -> item.track } } +
            chartsCatalog?.sections.orEmpty().flatMap { it.items.mapNotNull { item -> item.track } } +
            openedCatalog?.sections.orEmpty().flatMap { it.items.mapNotNull { item -> item.track } })
            .distinctBy { it.id }
    }

    Scaffold(
        containerColor = androidx.compose.material3.MaterialTheme.colorScheme.background,
        topBar = {
            TopHeader(
                onSearchClick = { currentRoute = NavTab.Discover.route },
                onHistoryClick = { currentRoute = NavTab.Library.route },
                onAboutClick = { currentRoute = "about" }
            )
        },
        bottomBar = {
            BottomNavBar(
                currentRoute = currentRoute,
                onTabSelected = { currentRoute = it.route }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (openedCatalog != null) {
                CatalogDetailScreen(
                    page = openedCatalog!!,
                    currentTrack = playbackState.currentTrack,
                    onTrackSelect = { track ->
                        viewModel.playTrack(track, catalogTracks.ifEmpty { listOf(track) })
                        isPlayerExpanded = true
                    },
                    onBrowseClick = viewModel::openBrowse,
                    onBack = viewModel::closeCatalog
                )
            } else when (currentRoute) {
                NavTab.Home.route -> {
                    ModernHomeScreen(
                        onIntent = { intent ->
                            viewModel.startIntent(intent)
                            currentRoute = NavTab.Discover.route
                        },
                        catalogSections = homeCatalog?.sections.orEmpty(),
                        currentTrack = playbackState.currentTrack,
                        isLoadingCatalog = isLoadingCatalog,
                        onTrackSelect = { track ->
                            viewModel.playTrack(track, catalogTracks.ifEmpty { listOf(track) })
                            isPlayerExpanded = true
                        },
                        onBrowseClick = viewModel::openBrowse
                    )
                }

                NavTab.Discover.route -> {
                    DiscoverScreen(
                        searchQuery = searchQuery,
                        searchResults = searchResults.ifEmpty { trendingTracks },
                        currentTrack = playbackState.currentTrack,
                        isSearching = isSearching,
                        errorMessage = errorMessage,
                        catalogSections = if (searchQuery.isBlank()) {
                            (exploreCatalog?.sections.orEmpty() + chartsCatalog?.sections.orEmpty()).take(10)
                        } else {
                            searchCatalog?.sections.orEmpty()
                        },
                        exploreSections = exploreCatalog?.sections.orEmpty(),
                        chartsSections = chartsCatalog?.sections.orEmpty(),
                        homeSections = homeCatalog?.sections.orEmpty(),
                        onBrowseClick = viewModel::openBrowse,
                        searchType = searchType,
                        onSearchTypeChange = viewModel::onSearchTypeChange,
                        canLoadMore = !searchCatalog?.nextCursor.isNullOrBlank(),
                        isLoadingMore = isLoadingMore,
                        onLoadMore = viewModel::loadMoreSearchResults,
                        onQueryChange = viewModel::onSearchQueryChange,
                        onTrackSelect = { track ->
                            viewModel.playTrack(track, catalogTracks.ifEmpty { listOf(track) })
                            isPlayerExpanded = true
                        },
                        onFavoriteToggle = viewModel::toggleFavorite
                    )
                }

                NavTab.Library.route -> {
                    ModernLibraryScreen(
                        favoriteTracks = favoriteTracks,
                        historyTracks = historyTracks,
                        playlists = playlists,
                        queueTracks = playbackState.queue,
                        currentTrack = playbackState.currentTrack,
                        onTrackSelect = { track ->
                            viewModel.playTrack(track, favoriteTracks)
                            isPlayerExpanded = true
                        },
                        onFavoriteToggle = viewModel::toggleFavorite,
                        onClearHistory = viewModel::clearHistory,
                        onCreatePlaylist = viewModel::createPlaylist,
                        onDeletePlaylist = viewModel::deletePlaylist,
                        onPlaylistTrackSelect = { playlist, track ->
                            viewModel.playTrack(track, playlist.tracks)
                            isPlayerExpanded = true
                        }
                    )
                }

                "about" -> {
                    AboutScreen(
                        onBack = { currentRoute = NavTab.Home.route },
                        darkTheme = darkTheme,
                        onDarkThemeChange = onDarkThemeChange
                    )
                }
            }

            // Sticky bottom mini-player
            if (playbackState.currentTrack != null) {
                Box(modifier = Modifier.align(Alignment.BottomCenter)) {
                    MiniPlayerBar(
                        state = playbackState,
                        onTogglePlayPause = { viewModel.playbackManager.togglePlayPause() },
                        onNext = { viewModel.playbackManager.playNext() },
                        onExpandPlayer = { isPlayerExpanded = true }
                    )
                }
            }

            // Full-screen player sheet
            if (isPlayerExpanded) {
                FullScreenPlayerSheet(
                    state = playbackState,
                    onDismiss = { isPlayerExpanded = false },
                    onTogglePlayPause = { viewModel.playbackManager.togglePlayPause() },
                    onNext = { viewModel.playbackManager.playNext() },
                    onPrevious = { viewModel.playbackManager.playPrevious() },
                    onSeekTo = { pos -> viewModel.playbackManager.seekTo(pos) },
                    onToggleShuffle = { viewModel.playbackManager.toggleShuffle() },
                    onToggleRepeat = { viewModel.playbackManager.toggleRepeat() },
                    onRetry = { viewModel.playbackManager.retryCurrent() },
                    onQueueItemClick = { track ->
                        viewModel.playbackManager.playTrack(track, playbackState.queue)
                    },
                    onRemoveFromQueue = { index -> viewModel.playbackManager.removeFromQueue(index) },
                    onMoveQueueItem = { from, to -> viewModel.playbackManager.moveQueueItem(from, to) },
                    onClearQueue = { viewModel.playbackManager.clearQueue() },
                    onSetSpeed = { speed -> viewModel.playbackManager.setPlaybackSpeed(speed) },
                    onSetPitch = { pitch -> viewModel.playbackManager.setPitch(pitch) },
                    onDownload = {
                        playbackState.currentTrack?.let { track ->
                            playerScope.launch {
                                runCatching { viewModel.playbackManager.downloadTrack(track) }
                            }
                        }
                    },
                    playlists = playlists,
                    onAddToPlaylist = { playlistId ->
                        playbackState.currentTrack?.let { viewModel.addToPlaylist(playlistId, it) }
                    },
                    onCreatePlaylistAndAdd = { name ->
                        playbackState.currentTrack?.let { track ->
                            viewModel.createPlaylist(name)?.let { playlist ->
                                viewModel.addToPlaylist(playlist.id, track)
                            }
                        }
                    },
                    lyrics = lyrics,
                    isLoadingLyrics = isLoadingLyrics,
                    onLoadLyrics = {
                        playbackState.currentTrack?.let(viewModel::loadLyrics)
                    }
                )
            }
        }
    }
}
