package com.moodify.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.add
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.moodify.app.data.BrowseEndpoint
import com.moodify.app.data.ThemeMode
import com.moodify.app.data.Track
import com.moodify.app.player.MoodifyPlaybackService
import com.moodify.app.player.PlaybackManager
import com.moodify.app.ui.MainViewModel
import com.moodify.app.ui.components.FullScreenLyricsScreen
import com.moodify.app.ui.designsystem.LocalDownloadedIds
import com.moodify.app.ui.designsystem.LocalFavoriteIds
import com.moodify.app.ui.designsystem.LocalMoodifyActions
import com.moodify.app.ui.designsystem.LocalMoodifyIsDark
import com.moodify.app.ui.designsystem.LocalNowPlaying
import com.moodify.app.ui.designsystem.MoodifyActions
import com.moodify.app.ui.designsystem.NowPlaying
import com.moodify.app.ui.navigation.BrowseRoute
import com.moodify.app.ui.navigation.DownloadsRoute
import com.moodify.app.ui.navigation.HistoryRoute
import com.moodify.app.ui.navigation.HomeRoute
import com.moodify.app.ui.navigation.LibraryRoute
import com.moodify.app.ui.navigation.LikedRoute
import com.moodify.app.ui.navigation.PlaylistRoute
import com.moodify.app.ui.navigation.SearchRoute
import com.moodify.app.ui.navigation.SettingsRoute
import com.moodify.app.ui.player.FullPlayer
import com.moodify.app.ui.player.FullPlayerActions
import com.moodify.app.ui.player.MainTab
import com.moodify.app.ui.player.MiniPlayer
import com.moodify.app.ui.player.MiniPlayerHeight
import com.moodify.app.ui.player.MoodifyNavBar
import com.moodify.app.ui.player.NavBarHeight
import com.moodify.app.ui.player.PlayerMoreSheet
import com.moodify.app.ui.player.PlaylistPickerSheet
import com.moodify.app.ui.player.QueueSheet
import com.moodify.app.ui.player.SleepTimerSheet
import com.moodify.app.ui.player.TrackActionsSheet
import com.moodify.app.ui.player.rememberPlayerSheetState
import com.moodify.app.ui.screens.BrowseScreen
import com.moodify.app.ui.screens.BrowseViewModel
import com.moodify.app.ui.screens.HomeScreen
import com.moodify.app.ui.screens.HomeUiState
import com.moodify.app.ui.screens.LibraryScreen
import com.moodify.app.ui.screens.PlaylistScreen
import com.moodify.app.ui.screens.SearchScreen
import com.moodify.app.ui.screens.SearchUiState
import com.moodify.app.ui.screens.SettingsActions
import com.moodify.app.ui.screens.SettingsScreen
import com.moodify.app.ui.screens.TrackCollection
import com.moodify.app.ui.screens.TrackCollectionScreen
import com.moodify.app.ui.theme.MoodifyTheme
import com.moodify.app.ui.theme.Motion
import com.moodify.app.ui.theme.PlayerTheme
import com.moodify.app.ui.theme.Spacing
import com.moodify.app.ui.theme.rememberArtworkColors
import kotlinx.coroutines.launch

@UnstableApi
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        // Hold the splash until stored settings load, so light-theme users
        // never see a flash of the default dark theme.
        val mainViewModel: MainViewModel by viewModels()
        splash.setKeepOnScreenCondition { !mainViewModel.settingsReady.value }

        val playbackManager = PlaybackManager.getInstance(applicationContext)
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
            navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )

        setContent {
            val viewModel = mainViewModel
            val settings by viewModel.appSettings.collectAsStateWithLifecycle()
            val playback by viewModel.playbackState.collectAsStateWithLifecycle()
            val darkTheme = when (settings.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }
            val artwork = rememberArtworkColors(playback.currentTrack?.albumArtUrl, darkTheme)
            MoodifyTheme(darkTheme = darkTheme, artwork = artwork) {
                CompositionLocalProvider(LocalMoodifyIsDark provides darkTheme) {
                    androidx.compose.animation.Crossfade(targetState = settings.onboardingDone, label = "onboarding") { done ->
                        if (done) {
                            MoodifyApp(viewModel)
                        } else {
                            com.moodify.app.ui.screens.OnboardingScreen(onFinish = viewModel::completeOnboarding)
                        }
                    }
                }
            }
        }
    }
}

/** Remembers whether we have already asked for notification permission. */
private const val PREFS = "moodify_ui"
private const val KEY_ASKED_NOTIFICATIONS = "asked_notifications"

@UnstableApi
@Composable
fun MoodifyApp(viewModel: MainViewModel) {
    val context = LocalContext.current
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()
    val sheet = rememberPlayerSheetState()
    val snackbar = remember { SnackbarHostState() }
    val manager = viewModel.playbackManager

    val settings by viewModel.appSettings.collectAsStateWithLifecycle()
    val playback by viewModel.playbackState.collectAsStateWithLifecycle()
    val diagnostics by manager.diagnostics.collectAsStateWithLifecycle()
    val favoriteIds by viewModel.favoriteIds.collectAsStateWithLifecycle()
    val favorites by viewModel.favoriteTracks.collectAsStateWithLifecycle()
    val history by viewModel.historyTracks.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val downloads by viewModel.downloadedTracks.collectAsStateWithLifecycle()
    val trending by viewModel.trendingTracks.collectAsStateWithLifecycle()
    val forYou by viewModel.forYouTracks.collectAsStateWithLifecycle()
    val personal by viewModel.personalShelves.collectAsStateWithLifecycle()
    val homeChips by viewModel.homeChips.collectAsStateWithLifecycle()
    val selectedChip by viewModel.selectedHomeChip.collectAsStateWithLifecycle()
    val isLoadingHomeMore by viewModel.isLoadingHomeMore.collectAsStateWithLifecycle()
    val activeDownloads by viewModel.activeDownloads.collectAsStateWithLifecycle()
    val related by viewModel.related.collectAsStateWithLifecycle()
    val homeCatalog by viewModel.homeCatalog.collectAsStateWithLifecycle()
    val chartsCatalog by viewModel.chartsCatalog.collectAsStateWithLifecycle()
    val moodsCatalog by viewModel.moodsCatalog.collectAsStateWithLifecycle()
    val exploreCatalog by viewModel.exploreCatalog.collectAsStateWithLifecycle()
    val isLoadingCatalog by viewModel.isLoadingCatalog.collectAsStateWithLifecycle()
    val catalogStale by viewModel.catalogIsStale.collectAsStateWithLifecycle()
    val catalogError by viewModel.catalogError.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val searchType by viewModel.searchType.collectAsStateWithLifecycle()
    val searchCatalog by viewModel.searchCatalog.collectAsStateWithLifecycle()
    val suggestions by viewModel.searchSuggestions.collectAsStateWithLifecycle()
    val isSearching by viewModel.isSearching.collectAsStateWithLifecycle()
    val isLoadingMore by viewModel.isLoadingMore.collectAsStateWithLifecycle()
    val searchError by viewModel.errorMessage.collectAsStateWithLifecycle()

    // ── Sheets & overlays ──────────────────────────────────────────────────
    var menuTrack by remember { mutableStateOf<Track?>(null) }
    var menuPlaylistId by remember { mutableStateOf<String?>(null) }
    var pickerTrack by remember { mutableStateOf<Track?>(null) }
    var showQueue by remember { mutableStateOf(false) }
    var showMore by remember { mutableStateOf(false) }
    var showSleep by remember { mutableStateOf(false) }
    var showRelated by remember { mutableStateOf(false) }
    var showLyrics by remember { mutableStateOf(false) }
    var searchFocusKey by rememberSaveable { mutableIntStateOf(0) }
    var selectedTab by rememberSaveable { mutableStateOf(MainTab.HOME) }

    // ── Notification permission, asked on first play rather than at launch ─
    val prefs = remember { context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun maybeAskForNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (prefs.getBoolean(KEY_ASKED_NOTIFICATIONS, false)) return
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        prefs.edit().putBoolean(KEY_ASKED_NOTIFICATIONS, true).apply()
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    // ── Snackbar messages from the ViewModel ───────────────────────────────
    LaunchedEffect(Unit) {
        viewModel.messages.collect { message ->
            snackbar.currentSnackbarData?.dismiss()
            launch {
                val result = snackbar.showSnackbar(
                    message = message.text,
                    actionLabel = message.actionLabel,
                    duration = if (message.actionLabel != null) SnackbarDuration.Long else SnackbarDuration.Short,
                )
                if (result == SnackbarResult.ActionPerformed) message.action?.invoke()
            }
        }
    }
    val browseError by viewModel.browseError.collectAsState()
    LaunchedEffect(browseError) {
        val message = browseError ?: return@LaunchedEffect
        snackbar.showSnackbar(message)
        viewModel.clearBrowseError()
    }

    // ── Navigation helpers ─────────────────────────────────────────────────
    fun selectTab(tab: MainTab) {
        selectedTab = tab
        val route: Any = when (tab) {
            MainTab.HOME -> HomeRoute
            MainTab.SEARCH -> SearchRoute
            MainTab.LIBRARY -> LibraryRoute
        }
        val alreadyOnRoot = navController.currentDestination?.let { dest ->
            (tab == MainTab.HOME && dest.hasRoute(HomeRoute::class)) ||
                (tab == MainTab.SEARCH && dest.hasRoute(SearchRoute::class)) ||
                (tab == MainTab.LIBRARY && dest.hasRoute(LibraryRoute::class))
        } == true
        if (alreadyOnRoot) return
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
    fun collapsePlayer() { scope.launch { sheet.collapse() } }

    val actions = remember(viewModel, navController) {
        MoodifyActions(
            play = { track, queue ->
                viewModel.playTrack(track, queue.ifEmpty { listOf(track) })
                maybeAskForNotifications()
            },
            playNext = viewModel::playNext,
            addToQueue = viewModel::addToQueue,
            toggleFavorite = viewModel::toggleFavorite,
            openTrackMenu = { menuPlaylistId = null; menuTrack = it },
            openBrowse = { endpoint: BrowseEndpoint, title: String ->
                val browseId = endpoint.browseId
                if (!browseId.isNullOrBlank()) {
                    collapsePlayer()
                    navController.navigate(BrowseRoute(browseId, endpoint.params, endpoint.pageType, title))
                }
            },
            searchFor = { query ->
                collapsePlayer()
                selectTab(MainTab.SEARCH)
                if (query.isBlank()) searchFocusKey++ else viewModel.submitSearch(query)
            },
            openTrackMenuInPlaylist = { track, playlistId -> menuPlaylistId = playlistId; menuTrack = track },
        )
    }

    // Keep the selected tab in sync with Back navigation.
    val backStackEntry by navController.currentBackStackEntryAsState()
    LaunchedEffect(backStackEntry) {
        val destination = backStackEntry?.destination ?: return@LaunchedEffect
        when {
            destination.hasRoute(HomeRoute::class) -> selectedTab = MainTab.HOME
            destination.hasRoute(SearchRoute::class) -> selectedTab = MainTab.SEARCH
            destination.hasRoute(LibraryRoute::class) -> selectedTab = MainTab.LIBRARY
        }
    }

    BackHandler(enabled = sheet.isExpanded) { collapsePlayer() }

    val hasTrack = playback.currentTrack != null
    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val chromeHeight = NavBarHeight + Spacing.md + (if (hasTrack) MiniPlayerHeight + Spacing.sm else 0.dp)
    val bottomPadding = chromeHeight + navInset + Spacing.sm

    val homeState = remember(homeCatalog, history, favorites, trending, forYou, playback.currentTrack, playlists, isLoadingCatalog, catalogStale, catalogError, settings.favoriteMoods, personal, homeChips, selectedChip, isLoadingHomeMore, downloads) {
        val jump = playback.currentTrack ?: history.firstOrNull()
        HomeUiState(
            sections = homeCatalog?.sections.orEmpty().ifEmpty { exploreCatalog?.sections.orEmpty() },
            quickPicks = forYou.filterNot { it.id == jump?.id }.ifEmpty {
                (history.drop(if (playback.currentTrack == null) 1 else 0) + favorites + trending)
                    .distinctBy { it.id }
                    .filterNot { it.id == jump?.id }
                    .take(16)
            },
            quickPicksArePersonal = forYou.isNotEmpty(),
            jumpBackIn = jump,
            pinnedPlaylists = playlists.filter { it.isPinned && it.tracks.isNotEmpty() },
            isLoading = isLoadingCatalog,
            isStale = catalogStale,
            error = catalogError,
            favoriteMoods = settings.favoriteMoods,
            keepListening = personal.keepListening.filterNot { it.id == jump?.id },
            forgotten = personal.forgotten,
            becauseArtist = personal.becauseArtist,
            becauseTracks = personal.becauseTracks,
            offlineTracks = downloads,
            chips = homeChips,
            selectedChip = selectedChip,
            canLoadMore = !homeCatalog?.nextCursor.isNullOrBlank(),
            isLoadingMore = isLoadingHomeMore,
        )
    }

    CompositionLocalProvider(
        LocalMoodifyActions provides actions,
        LocalNowPlaying provides NowPlaying(playback.currentTrack?.id, playback.isPlaying),
        LocalFavoriteIds provides favoriteIds,
        LocalDownloadedIds provides playback.downloadedTrackIds,
        com.moodify.app.ui.designsystem.LocalAppSnackbar provides snackbar,
    ) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        ) {
            val screenHeightPx = with(LocalDensity.current) { maxHeight.toPx() }
            sheet.dragRangePx = screenHeightPx

            androidx.compose.animation.SharedTransitionLayout {
            CompositionLocalProvider(com.moodify.app.ui.designsystem.LocalSharedTransitionScope provides this) {
            NavHost(
                navController = navController,
                startDestination = HomeRoute,
                modifier = Modifier.fillMaxSize(),
                enterTransition = { fadeIn(tween(Motion.MEDIUM)) + slideInHorizontally(tween(Motion.MEDIUM, easing = Motion.EmphasizedDecelerate)) { it / 10 } },
                exitTransition = { fadeOut(tween(Motion.SHORT)) },
                popEnterTransition = { fadeIn(tween(Motion.MEDIUM)) },
                popExitTransition = { fadeOut(tween(Motion.SHORT)) + slideOutHorizontally(tween(Motion.MEDIUM)) { it / 10 } },
            ) {
                screen<HomeRoute>(
                    enterTransition = { fadeIn(tween(Motion.MEDIUM)) },
                    exitTransition = { fadeOut(tween(Motion.SHORT)) },
                ) {
                    HomeScreen(
                        state = homeState,
                        bottomPadding = bottomPadding,
                        onRefresh = viewModel::refreshCatalog,
                        onOpenSearch = { actions.searchFor("") },
                        onOpenSettings = { navController.navigate(SettingsRoute) },
                        onOpenPlaylist = { navController.navigate(PlaylistRoute(it.id)) },
                        onSelectChip = viewModel::selectHomeChip,
                        onLoadMore = viewModel::loadMoreHome,
                        onRefreshDiscover = viewModel::refreshForYou,
                        onResume = { track ->
                            if (playback.currentTrack?.id == track.id) manager.togglePlayPause()
                            else actions.play(track, history.ifEmpty { listOf(track) })
                        },
                    )
                }
                screen<SearchRoute>(
                    enterTransition = { fadeIn(tween(Motion.MEDIUM)) },
                    exitTransition = { fadeOut(tween(Motion.SHORT)) },
                ) {
                    SearchScreen(
                        state = SearchUiState(
                            query = searchQuery,
                            type = searchType,
                            isSearching = isSearching,
                            resultSections = searchCatalog?.sections.orEmpty(),
                            suggestions = suggestions,
                            recentSearches = settings.recentSearches,
                            browseSections = moodsCatalog?.sections.orEmpty(),
                            chartSections = chartsCatalog?.sections.orEmpty(),
                            error = searchError,
                            canLoadMore = !searchCatalog?.nextCursor.isNullOrBlank(),
                            isLoadingMore = isLoadingMore,
                        ),
                        bottomPadding = bottomPadding,
                        focusRequestKey = searchFocusKey,
                        onQueryChange = viewModel::onSearchQueryChange,
                        onSubmit = viewModel::submitSearch,
                        onTypeChange = viewModel::onSearchTypeChange,
                        onRemoveRecent = viewModel::removeRecentSearch,
                        onClearRecents = viewModel::clearRecentSearches,
                        onLoadMore = viewModel::loadMoreSearchResults,
                        onRetry = { viewModel.submitSearch(searchQuery) },
                    )
                }
                screen<LibraryRoute>(
                    enterTransition = { fadeIn(tween(Motion.MEDIUM)) },
                    exitTransition = { fadeOut(tween(Motion.SHORT)) },
                ) {
                    LibraryScreen(
                        playlists = playlists,
                        likedCount = favorites.size,
                        downloadCount = downloads.size,
                        historyCount = history.size,
                        bottomPadding = bottomPadding,
                        onOpenLiked = { navController.navigate(LikedRoute) },
                        onOpenDownloads = { navController.navigate(DownloadsRoute) },
                        onOpenHistory = { navController.navigate(HistoryRoute) },
                        onOpenPlaylist = { navController.navigate(PlaylistRoute(it.id)) },
                        onCreatePlaylist = { viewModel.createPlaylist(it) },
                    )
                }
                screen<BrowseRoute> {
                    val browseViewModel: BrowseViewModel = viewModel()
                    val state by browseViewModel.state.collectAsStateWithLifecycle()
                    BrowseScreen(
                        state = state,
                        bottomPadding = bottomPadding,
                        onBack = { navController.popBackStack() },
                        onRetry = browseViewModel::load,
                        onLoadMore = browseViewModel::loadMore,
                        onDownloadAll = { tracks -> viewModel.downloadAll(tracks, state.title.ifBlank { "this collection" }) },
                    )
                }
                screen<PlaylistRoute> { entry ->
                    val id = entry.toRoute<PlaylistRoute>().id
                    PlaylistScreen(
                        playlist = playlists.firstOrNull { it.id == id },
                        bottomPadding = bottomPadding,
                        onBack = { navController.popBackStack() },
                        onRename = { viewModel.renamePlaylist(id, it) },
                        onTogglePin = {
                            playlists.firstOrNull { it.id == id }?.let { viewModel.setPlaylistPinned(id, !it.isPinned) }
                        },
                        onDelete = {
                            navController.popBackStack()
                            viewModel.deletePlaylist(id)
                        },
                        onDownloadAll = {
                            playlists.firstOrNull { it.id == id }?.let { viewModel.downloadAll(it.tracks, it.name) }
                        },
                    )
                }
                screen<LikedRoute> {
                    TrackCollectionScreen(TrackCollection.LIKED, favorites, bottomPadding, onBack = { navController.popBackStack() })
                }
                screen<DownloadsRoute> {
                    TrackCollectionScreen(
                        TrackCollection.DOWNLOADS,
                        downloads,
                        bottomPadding,
                        onBack = { navController.popBackStack() },
                        activeDownloads = activeDownloads,
                        onCancelDownload = viewModel::deleteDownload,
                    )
                }
                screen<HistoryRoute> {
                    TrackCollectionScreen(
                        TrackCollection.HISTORY,
                        history,
                        bottomPadding,
                        onBack = { navController.popBackStack() },
                        onClear = viewModel::clearHistory,
                    )
                }
                screen<SettingsRoute> {
                    SettingsScreen(
                        settings = settings,
                        diagnostics = diagnostics,
                        downloadCount = downloads.size,
                        bottomPadding = bottomPadding,
                        actions = SettingsActions(
                            onBack = { navController.popBackStack() },
                            onThemeMode = viewModel::setThemeMode,
                            onLocale = viewModel::setLocale,
                            onShowLyricsControls = viewModel::setShowLyricsControls,
                            onShowLyricsTranslation = viewModel::setShowLyricsTranslation,
                            onShowLyricsRomanization = viewModel::setShowLyricsRomanization,
                            onLyricsOffset = viewModel::setLyricsSyncOffset,
                            onClearRecentSearches = viewModel::clearRecentSearches,
                            onAutoplay = viewModel::setAutoplay,
                            onNormalizeVolume = viewModel::setNormalizeVolume,
                        ),
                    )
                }
            }
            }
            }

            // ── Status-bar scrim so scrolled content never collides with the clock ─
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .windowInsetsTopHeight(WindowInsets.statusBars.add(WindowInsets(top = 10.dp)))
                    .background(
                        Brush.verticalGradient(
                            0f to MaterialTheme.colorScheme.background.copy(alpha = 0.92f),
                            0.7f to MaterialTheme.colorScheme.background.copy(alpha = 0.6f),
                            1f to Color.Transparent,
                        ),
                    ),
            )

            // ── Bottom chrome: fade-out scrim, mini player, nav pill ──────
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .graphicsLayer {
                        translationY = sheet.progress * (chromeHeight + navInset).toPx()
                        alpha = 1f - sheet.progress
                    },
            ) {
                Box(
                    Modifier
                        .matchParentSize()
                        .background(
                            Brush.verticalGradient(
                                0f to Color.Transparent,
                                0.35f to MaterialTheme.colorScheme.background.copy(alpha = 0.85f),
                                1f to MaterialTheme.colorScheme.background,
                            ),
                        ),
                )
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = Spacing.xl)
                        .navigationBarsPadding()
                        .padding(bottom = Spacing.sm),
                ) {
                    AnimatedVisibility(
                        visible = hasTrack,
                        enter = slideInVertically(Motion.bouncy()) { it } + fadeIn() + scaleIn(initialScale = 0.9f),
                        exit = slideOutVertically { it } + fadeOut() + scaleOut(targetScale = 0.9f),
                    ) {
                        Column {
                            MiniPlayer(
                                state = playback,
                                progress = manager.progress,
                                sheet = sheet,
                                onTogglePlay = manager::togglePlayPause,
                                onNext = manager::playNext,
                                onPrevious = manager::playPrevious,
                            )
                            Spacer(Modifier.height(Spacing.sm))
                        }
                    }
                    MoodifyNavBar(selected = selectedTab, onSelect = ::selectTab)
                }
            }

            // ── Snackbars float above the chrome ──────────────────────────
            SnackbarHost(
                hostState = snackbar,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = if (sheet.isVisible) navInset + Spacing.xl else bottomPadding)
                    .padding(horizontal = Spacing.md),
            ) { data ->
                Snackbar(
                    snackbarData = data,
                    shape = MaterialTheme.shapes.large,
                    containerColor = MaterialTheme.colorScheme.inverseSurface,
                    contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                    actionColor = MaterialTheme.colorScheme.primary,
                )
            }

            // ── Full player, sliding up from the mini player ──────────────
            if (sheet.isVisible && hasTrack) {
                val artworkColors = com.moodify.app.ui.theme.LocalArtworkColors.current
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            translationY = (1f - sheet.progress) * screenHeightPx
                            alpha = (sheet.progress * 3f).coerceAtMost(1f)
                        },
                ) {
                    PlayerTheme(artworkColors) {
                        FullPlayer(
                            state = playback,
                            progress = manager.progress,
                            sheet = sheet,
                            isFavorite = playback.currentTrack?.id in favoriteIds,
                            actions = FullPlayerActions(
                                onCollapse = ::collapsePlayer,
                                onTogglePlay = manager::togglePlayPause,
                                onNext = manager::playNext,
                                onPrevious = manager::playPrevious,
                                onSkipTo = { index ->
                                    playback.queue.getOrNull(index)?.let { manager.playTrack(it, playback.queue) }
                                },
                                onSeek = manager::seekTo,
                                onToggleShuffle = manager::toggleShuffle,
                                onToggleRepeat = manager::toggleRepeat,
                                onRetry = manager::retryCurrent,
                                onToggleLike = viewModel::toggleFavorite,
                                onOpenQueue = { showQueue = true },
                                onOpenLyrics = {
                                    playback.currentTrack?.let(viewModel::loadLyrics)
                                    showLyrics = true
                                },
                                onOpenMore = { showMore = true },
                                onOpenSleepTimer = { showSleep = true },
                                onDownload = viewModel::downloadTrack,
                                onSearchArtist = { actions.searchFor(it) },
                            ),
                        )
                        if (showQueue) {
                            QueueSheet(
                                state = playback,
                                onDismiss = { showQueue = false },
                                onPlayIndex = { index -> playback.queue.getOrNull(index)?.let { manager.playTrack(it, playback.queue) } },
                                onMove = manager::moveQueueItem,
                                onRemove = viewModel::removeFromQueue,
                                onClear = manager::clearQueue,
                                onSaveAsPlaylist = viewModel::saveQueueAsPlaylist,
                            )
                        }
                        if (showMore) {
                            PlayerMoreSheet(
                                state = playback,
                                onDismiss = { showMore = false },
                                onSetSpeed = manager::setPlaybackSpeed,
                                onSetPitch = manager::setPitch,
                                onSaveToPlaylist = { showMore = false; pickerTrack = playback.currentTrack },
                                onMoreByArtist = { playback.currentTrack?.let { actions.searchFor(it.artist) } },
                                onStartRadio = { playback.currentTrack?.let(viewModel::startRadio) },
                                onShowRelated = {
                                    playback.currentTrack?.let(viewModel::loadRelated)
                                    showRelated = true
                                },
                            )
                        }
                        if (showRelated) {
                            playback.currentTrack?.let { current ->
                                com.moodify.app.ui.player.RelatedSheet(
                                    track = current,
                                    page = related.page.takeIf { related.trackId == current.id },
                                    isLoading = related.isLoading,
                                    onDismiss = { showRelated = false },
                                )
                            }
                        }
                        if (showSleep) {
                            SleepTimerSheet(state = playback, onDismiss = { showSleep = false }, onSet = viewModel::setSleepTimer)
                        }
                    }
                }
            }
        }

        // ── Global sheets ─────────────────────────────────────────────────
        menuTrack?.let { track ->
            val playlistId = menuPlaylistId
            TrackActionsSheet(
                track = track,
                isFavorite = track.id in favoriteIds,
                isDownloaded = track.id in playback.downloadedTrackIds,
                onDismiss = { menuTrack = null },
                onPlayNext = { viewModel.playNext(track) },
                onAddToQueue = { viewModel.addToQueue(track) },
                onToggleFavorite = { viewModel.toggleFavorite(track) },
                onSaveToPlaylist = { menuTrack = null; pickerTrack = track },
                onDownload = { viewModel.downloadTrack(track) },
                onRemoveDownload = { viewModel.deleteDownload(track) },
                onMoreByArtist = { actions.searchFor(track.artist) },
                onStartRadio = { viewModel.startRadio(track) },
                extraActions = { dismiss ->
                    if (playlistId != null) {
                        androidx.compose.material3.TextButton(
                            onClick = { viewModel.removeFromPlaylist(playlistId, track); dismiss() },
                            modifier = Modifier.padding(horizontal = Spacing.md),
                        ) {
                            androidx.compose.material3.Text("Remove from this playlist", color = MaterialTheme.colorScheme.error)
                        }
                    }
                },
            )
        }
        pickerTrack?.let { track ->
            PlaylistPickerSheet(
                track = track,
                playlists = playlists,
                onDismiss = { pickerTrack = null },
                onSelect = { viewModel.addToPlaylist(it.id, track) },
                onCreate = { viewModel.createPlaylist(it, addTrack = track) },
            )
        }
        if (showLyrics) {
            PlayerTheme(com.moodify.app.ui.theme.LocalArtworkColors.current) {
                LyricsOverlay(viewModel = viewModel, onClose = { showLyrics = false })
            }
        }
    }
}

/** Wraps the existing lyrics experience (sync, search, translate, edit, share). */
@UnstableApi
@Composable
private fun LyricsOverlay(viewModel: MainViewModel, onClose: () -> Unit) {
    val playback by viewModel.playbackState.collectAsStateWithLifecycle()
    val settings by viewModel.appSettings.collectAsStateWithLifecycle()
    val track = playback.currentTrack ?: run { onClose(); return }
    val manager = viewModel.playbackManager
    val lyrics by viewModel.lyrics.collectAsStateWithLifecycle()
    val lyricsError by viewModel.lyricsErrorMessage.collectAsStateWithLifecycle()
    val actionError by viewModel.lyricsActionErrorMessage.collectAsStateWithLifecycle()
    val loading by viewModel.isLoadingLyrics.collectAsStateWithLifecycle()
    val searching by viewModel.isSearchingLyrics.collectAsStateWithLifecycle()
    val results by viewModel.lyricsSearchResults.collectAsStateWithLifecycle()
    val searchError by viewModel.lyricsSearchErrorMessage.collectAsStateWithLifecycle()
    val translating by viewModel.isTranslatingLyrics.collectAsStateWithLifecycle()
    FullScreenLyricsScreen(
        track = track,
        state = playback,
        progress = manager.progress,
        lyrics = lyrics,
        lyricsErrorMessage = lyricsError,
        actionErrorMessage = actionError,
        isLoading = loading,
        isSearching = searching,
        searchResults = results,
        searchErrorMessage = searchError,
        isTranslating = translating,
        syncOffsetMs = settings.lyricsSyncOffsetMs,
        showControls = settings.showLyricsControls,
        showTranslation = settings.showLyricsTranslation,
        showRomanization = settings.showLyricsRomanization,
        onClose = onClose,
        onPlayPause = manager::togglePlayPause,
        onNext = manager::playNext,
        onPrevious = manager::playPrevious,
        onSeek = manager::seekTo,
        onRefresh = { viewModel.refreshLyrics(track) },
        onSearch = { title, artist -> viewModel.searchLyrics(track, title, artist) },
        onSelectSearchResult = { viewModel.selectLyricsSearchResult(track, it) },
        onSaveLyrics = { viewModel.saveLyrics(track, it) },
        onTranslate = { viewModel.translateLyrics(track, it) },
        onSyncOffsetChange = viewModel::setLyricsSyncOffset,
        onShowControlsChange = viewModel::setShowLyricsControls,
        onShowTranslationChange = viewModel::setShowLyricsTranslation,
        onShowRomanizationChange = viewModel::setShowLyricsRomanization,
    )
}

/** A NavHost destination that exposes its animation scope for shared-element transitions. */
private inline fun <reified T : Any> androidx.navigation.NavGraphBuilder.screen(
    noinline enterTransition: (androidx.compose.animation.AnimatedContentTransitionScope<androidx.navigation.NavBackStackEntry>.() -> EnterTransition?)? = null,
    noinline exitTransition: (androidx.compose.animation.AnimatedContentTransitionScope<androidx.navigation.NavBackStackEntry>.() -> ExitTransition?)? = null,
    noinline content: @Composable androidx.compose.animation.AnimatedContentScope.(androidx.navigation.NavBackStackEntry) -> Unit,
) {
    composable<T>(enterTransition = enterTransition, exitTransition = exitTransition) { entry ->
        CompositionLocalProvider(com.moodify.app.ui.designsystem.LocalNavAnimatedScope provides this) {
            content(entry)
        }
    }
}