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
import com.example.myapplication.ui.screens.ModernHomeScreen
import com.example.myapplication.ui.screens.ModernLibraryScreen
import com.example.myapplication.ui.theme.DarkBackground
import com.example.myapplication.ui.theme.MoodifyTheme

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
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
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
            MoodifyTheme {
                MoodifyApp()
            }
        }
    }
}

@Composable
fun MoodifyApp(
    viewModel: MainViewModel = viewModel()
) {
    var currentRoute by remember { mutableStateOf(NavTab.Home.route) }
    var isPlayerExpanded by remember { mutableStateOf(false) }
    val playerScope = rememberCoroutineScope()

    val playbackState by viewModel.playbackState.collectAsState()
    val trendingTracks by viewModel.trendingTracks.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val favoriteTracks by viewModel.favoriteTracks.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val lyrics by viewModel.lyrics.collectAsState()
    val isLoadingLyrics by viewModel.isLoadingLyrics.collectAsState()

    Scaffold(
        containerColor = DarkBackground,
        topBar = {
            TopHeader(
                onSearchClick = { currentRoute = NavTab.Discover.route },
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
            when (currentRoute) {
                NavTab.Home.route -> {
                    ModernHomeScreen(
                        onIntent = { intent ->
                            viewModel.startIntent(intent)
                            currentRoute = NavTab.Discover.route
                        },
                        onVibeSelect = { vibe ->
                            viewModel.selectVibe(vibe)
                            currentRoute = NavTab.Discover.route
                        }
                    )
                }

                NavTab.Discover.route -> {
                    DiscoverScreen(
                        searchQuery = searchQuery,
                        searchResults = searchResults.ifEmpty { trendingTracks },
                        currentTrack = playbackState.currentTrack,
                        isSearching = isSearching,
                        errorMessage = errorMessage,
                        onQueryChange = viewModel::onSearchQueryChange,
                        onTrackSelect = { track ->
                            viewModel.playTrack(track, searchResults.ifEmpty { trendingTracks })
                            isPlayerExpanded = true
                        },
                        onFavoriteToggle = viewModel::toggleFavorite
                    )
                }

                NavTab.Library.route -> {
                    ModernLibraryScreen(
                        favoriteTracks = favoriteTracks,
                        currentTrack = playbackState.currentTrack,
                        onTrackSelect = { track ->
                            viewModel.playTrack(track, favoriteTracks)
                            isPlayerExpanded = true
                        },
                        onFavoriteToggle = viewModel::toggleFavorite
                    )
                }

                "about" -> {
                    AboutScreen(
                        onBack = { currentRoute = NavTab.Home.route }
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
                    onSetSpeed = { speed -> viewModel.playbackManager.setPlaybackSpeed(speed) },
                    onSetPitch = { pitch -> viewModel.playbackManager.setPitch(pitch) },
                    onDownload = {
                        playbackState.currentTrack?.let { track ->
                            playerScope.launch {
                                runCatching { viewModel.playbackManager.downloadTrack(track) }
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
