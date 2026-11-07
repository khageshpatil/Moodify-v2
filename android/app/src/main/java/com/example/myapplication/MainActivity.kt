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
import androidx.compose.ui.semantics.clearAndSetSemantics
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

        // Only a fresh launch: a restored activity or one reopened from Recents
        // still carries its original link (e.g. an old room invite).
        val fromHistory = intent?.flags?.and(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0
        if (savedInstanceState == null && !fromHistory) handleLink(intent, mainViewModel)

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

    override fun onStart() {
        super.onStart()
        // The live social connection only runs while Moodify is on screen.
        com.moodify.app.AppGraph.social.setForeground(true)
    }

    override fun onStop() {
        com.moodify.app.AppGraph.social.setForeground(false)
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val mainViewModel: MainViewModel by viewModels()
        handleLink(intent, mainViewModel)
    }

    /** Opens YouTube / YouTube Music links (tapped or shared) inside Moodify. */
    private fun handleLink(intent: Intent?, viewModel: MainViewModel) {
        val data = intent?.data
        if (intent?.action == Intent.ACTION_VIEW && data?.scheme == "moodify" && data.host == "add") {
            data.lastPathSegment?.let(viewModel::openFriendInvite)
            return
        }
        if (intent?.action == Intent.ACTION_VIEW && data?.scheme == "moodify" && data.host == "chat") {
            data.lastPathSegment?.let(viewModel::openChat)
            return
        }
        if (intent?.action == Intent.ACTION_VIEW && data?.scheme == "moodify" && data.host == "room") {
            // Room codes are single-use, so a link Android replays later (task
            // relaunch, Recents) is always stale: join from each link once.
            val roomId = data.lastPathSegment?.uppercase() ?: return
            val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
            val handled = prefs.getStringSet(KEY_HANDLED_ROOM_LINKS, emptySet()).orEmpty()
            if (roomId in handled) return
            prefs.edit().putStringSet(KEY_HANDLED_ROOM_LINKS, (handled + roomId).toList().takeLast(50).toSet()).apply()
            viewModel.openRoomInvite(roomId)
            return
        }
        if (intent?.action == Intent.ACTION_VIEW && data?.scheme == "moodify" && data.host == "link") {
            data.lastPathSegment?.let { viewModel.openDeviceLink(it, data.getQueryParameter("k")) }
            return
        }
        val text = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.dataString
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            else -> null
        } ?: return
        com.moodify.app.data.SharedLinks.parse(text)?.let(viewModel::openLink)
    }
}

/** Remembers whether we have already asked for notification permission. */
private const val PREFS = "moodify_ui"
private const val KEY_HANDLED_ROOM_LINKS = "handled_room_links"
private const val KEY_ASKED_NOTIFICATIONS = "asked_notifications"
private const val ARTIST_PAGE_TYPE = "MUSIC_PAGE_TYPE_ARTIST"
private val NON_MUSIC_CHIPS = listOf("Podcast", "Audiobook")

/** "Playing from …" label for a queue started on the screen that's showing now. */
private fun playSourceFor(navController: androidx.navigation.NavController, viewModel: MainViewModel): String? {
    val entry = navController.currentBackStackEntry ?: return null
    val destination = entry.destination
    return when {
        destination.hasRoute(HomeRoute::class) -> "Home"
        destination.hasRoute(SearchRoute::class) ->
            viewModel.searchQuery.value.trim().takeIf(String::isNotBlank)?.let { "Search · $it" } ?: "Search"
        destination.hasRoute(LikedRoute::class) -> "Liked songs"
        destination.hasRoute(DownloadsRoute::class) -> "Downloads"
        destination.hasRoute(HistoryRoute::class) -> "History"
        destination.hasRoute(PlaylistRoute::class) -> {
            val id = entry.toRoute<PlaylistRoute>().id
            viewModel.playlists.value.firstOrNull { it.id == id }?.name
        }
        destination.hasRoute(BrowseRoute::class) -> entry.toRoute<BrowseRoute>().title.takeIf(String::isNotBlank)
        destination.hasRoute(com.moodify.app.ui.navigation.MoodRoute::class) -> {
            val id = entry.toRoute<com.moodify.app.ui.navigation.MoodRoute>().id
            com.moodify.app.ui.designsystem.MoodOptions.firstOrNull { it.id == id }?.let { "${it.label} mood" }
        }
        else -> null
    }
}

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
    val searchResultsType by viewModel.searchResultsType.collectAsStateWithLifecycle()
    val previewingId by viewModel.playbackManager.previewTrackId.collectAsStateWithLifecycle()
    val savedCollections by viewModel.savedCollections.collectAsStateWithLifecycle()
    val storage by viewModel.storage.collectAsStateWithLifecycle()
    val recentCollections by viewModel.recentCollections.collectAsStateWithLifecycle()
    // Albums, playlists and artists opened from a shared link.
    val linkTarget by viewModel.linkTarget.collectAsStateWithLifecycle()
    LaunchedEffect(linkTarget) {
        val target = linkTarget ?: return@LaunchedEffect
        val route = when (target) {
            is com.moodify.app.data.SharedLink.Playlist -> BrowseRoute("VL" + target.playlistId, null, "MUSIC_PAGE_TYPE_PLAYLIST", "")
            is com.moodify.app.data.SharedLink.Album -> BrowseRoute(target.browseId, null, "MUSIC_PAGE_TYPE_ALBUM", "")
            is com.moodify.app.data.SharedLink.Artist -> BrowseRoute(target.channelId, null, ARTIST_PAGE_TYPE, "")
            is com.moodify.app.data.SharedLink.Song -> null
        }
        viewModel.consumeLinkTarget()
        route?.let { navController.navigate(it) }
    }
    val suggestions by viewModel.searchSuggestions.collectAsStateWithLifecycle()
    val isSearching by viewModel.isSearching.collectAsStateWithLifecycle()
    val isLoadingMore by viewModel.isLoadingMore.collectAsStateWithLifecycle()
    val searchError by viewModel.errorMessage.collectAsStateWithLifecycle()

    // ── Sheets & overlays ──────────────────────────────────────────────────
    var menuTrack by remember { mutableStateOf<Track?>(null) }
    var menuPlaylistId by remember { mutableStateOf<String?>(null) }
    var pickerTrack by remember { mutableStateOf<Track?>(null) }
    // "Send to a friend": a song or a collection waiting for a friend to be picked.
    var sendTrack by remember { mutableStateOf<Track?>(null) }
    var sendCollection by remember { mutableStateOf<com.moodify.app.data.CatalogItem?>(null) }
    var sendPlaylist by remember { mutableStateOf<com.moodify.app.data.LocalPlaylist?>(null) }
    val friendsViewModel: com.moodify.app.ui.social.FriendsViewModel = viewModel()
    val social by friendsViewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(friendsViewModel) { friendsViewModel.messages.collect { snackbar.showSnackbar(it) } }
    val pendingFriendCode by viewModel.pendingFriendCode.collectAsStateWithLifecycle()
    val pendingDeviceLinkNav by viewModel.pendingDeviceLink.collectAsStateWithLifecycle()
    LaunchedEffect(pendingDeviceLinkNav) {
        if (pendingDeviceLinkNav != null) navController.navigate(com.moodify.app.ui.navigation.AccountRoute) { launchSingleTop = true }
    }
    val pendingChat by viewModel.pendingChat.collectAsStateWithLifecycle()
    // A message while you're in the app (not in that chat): a banner, not a notification.
    LaunchedEffect(Unit) {
        com.moodify.app.AppGraph.social.inAppMessages.collect { m ->
            val result = snackbar.showSnackbar(
                message = "${m.name}: ${m.preview}",
                actionLabel = "Open",
                duration = androidx.compose.material3.SnackbarDuration.Long,
            )
            if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                navController.navigate(com.moodify.app.ui.navigation.ChatRoute(m.peer)) { launchSingleTop = true }
            }
        }
    }
    val unreadMessages by remember { com.moodify.app.AppGraph.social.unreadTotal() }.collectAsStateWithLifecycle(0)
    LaunchedEffect(pendingChat) {
        val code = pendingChat ?: return@LaunchedEffect
        viewModel.consumeChat()
        navController.navigate(com.moodify.app.ui.navigation.ChatRoute(code)) { launchSingleTop = true }
    }
    val conversations by remember { com.moodify.app.AppGraph.social.conversations() }.collectAsStateWithLifecycle(emptyList())
    LaunchedEffect(pendingFriendCode) {
        if (pendingFriendCode != null) navController.navigate(com.moodify.app.ui.navigation.FriendsRoute) { launchSingleTop = true }
    }
    // Listen together (M4)
    val room by friendsViewModel.room.collectAsStateWithLifecycle()
    val showRoom by viewModel.roomSheetOpen.collectAsStateWithLifecycle()
    val pendingRoom by viewModel.pendingRoom.collectAsStateWithLifecycle()
    LaunchedEffect(pendingRoom) {
        pendingRoom?.let { roomId ->
            if (room?.roomId != roomId) friendsViewModel.joinRoom(roomId)
            viewModel.setRoomSheetOpen(true)
            viewModel.consumeRoomInvite()
        }
    }
    var showQueue by remember { mutableStateOf(false) }
    var showMore by remember { mutableStateOf(false) }
    var showSleep by remember { mutableStateOf(false) }
    var showRelated by remember { mutableStateOf(false) }
    var showLyrics by remember { mutableStateOf(false) }
    var searchFocusKey by rememberSaveable { mutableIntStateOf(0) }
    var selectedTab by rememberSaveable { mutableStateOf(MainTab.HOME) }
    // Bumped when the tab you're already on is tapped again: its list scrolls to the top.
    val tabReselects = remember { androidx.compose.runtime.mutableStateMapOf<MainTab, Int>() }

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
            MainTab.FRIENDS -> com.moodify.app.ui.navigation.FriendsRoute
        }
        val alreadyOnRoot = navController.currentDestination?.let { dest ->
            (tab == MainTab.HOME && dest.hasRoute(HomeRoute::class)) ||
                (tab == MainTab.SEARCH && dest.hasRoute(SearchRoute::class)) ||
                (tab == MainTab.LIBRARY && dest.hasRoute(LibraryRoute::class)) ||
                (tab == MainTab.FRIENDS && dest.hasRoute(com.moodify.app.ui.navigation.FriendsRoute::class))
        } == true
        if (alreadyOnRoot) {
            tabReselects[tab] = (tabReselects[tab] ?: 0) + 1
            return
        }
        // Deeper inside the current tab: go back to its first screen.
        if (tab == selectedTab && navController.popBackStack(route, inclusive = false)) return
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
                viewModel.playTrack(track, queue.ifEmpty { listOf(track) }, playSourceFor(navController, viewModel))
                // Remember albums / playlists you play from, for "Recently played".
                navController.currentBackStackEntry
                    ?.takeIf { it.destination.hasRoute(BrowseRoute::class) }
                    ?.toRoute<BrowseRoute>()
                    ?.let { route ->
                        val kind = com.moodify.app.ui.navigation.BrowseKind.of(route.browseId, route.pageType)
                        if (kind == com.moodify.app.ui.navigation.BrowseKind.ALBUM || kind == com.moodify.app.ui.navigation.BrowseKind.PLAYLIST) {
                            viewModel.rememberCollection(
                                com.moodify.app.data.CatalogItem(
                                    type = kind.name.lowercase(),
                                    id = route.browseId,
                                    title = route.title.ifBlank { kind.label },
                                    artworkUrl = queue.firstOrNull()?.albumArtUrl ?: track.albumArtUrl,
                                    endpoint = BrowseEndpoint(route.browseId, null, route.params, route.pageType),
                                ),
                            )
                        }
                    }
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
                if (query.isBlank()) {
                    searchFocusKey++
                } else {
                    viewModel.onSearchTypeChange(null)
                    viewModel.submitSearch(query)
                }
            },
            openTrackMenuInPlaylist = { track, playlistId -> menuPlaylistId = playlistId; menuTrack = track },
            openArtist = { track ->
                collapsePlayer()
                val name = com.moodify.app.data.primaryArtist(track.artist)
                val linked = track.artistId
                if (!linked.isNullOrBlank()) {
                    navController.navigate(BrowseRoute(linked, null, ARTIST_PAGE_TYPE, name))
                } else {
                    scope.launch {
                        val endpoint = viewModel.findArtistEndpoint(name)
                        val browseId = endpoint?.browseId
                        if (browseId != null) {
                            navController.navigate(BrowseRoute(browseId, endpoint.params, endpoint.pageType ?: ARTIST_PAGE_TYPE, name))
                        } else {
                            selectTab(MainTab.SEARCH)
                            viewModel.submitSearch(name)
                        }
                    }
                }
            },
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
            destination.hasRoute(com.moodify.app.ui.navigation.FriendsRoute::class) -> selectedTab = MainTab.FRIENDS
        }
    }

    BackHandler(enabled = sheet.isExpanded) { collapsePlayer() }
    val onChatRoute = backStackEntry?.destination?.hasRoute(com.moodify.app.ui.navigation.ChatRoute::class) == true

    val hasTrack = playback.currentTrack != null
    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val chromeHeight = NavBarHeight + Spacing.md + (if (hasTrack) MiniPlayerHeight + Spacing.sm else 0.dp)
    val bottomPadding = chromeHeight + navInset + Spacing.sm

    val homeState = remember(homeCatalog, history, favorites, trending, forYou, playback.currentTrack, playlists, isLoadingCatalog, catalogStale, catalogError, settings.favoriteMoods, personal, homeChips, selectedChip, isLoadingHomeMore, downloads, savedCollections, recentCollections) {
        // The mini-player already shows (and resumes) the current song, so Home
        // has no "now playing" card; just don't deal that song into the picks.
        val playingId = playback.currentTrack?.id
        // Taste (from your plays) blended with YouTube's Quick picks for your region.
        val discover = com.moodify.app.data.DiscoverBlend
            .blend(forYou, com.moodify.app.data.DiscoverBlend.regionalPicks(homeCatalog))
            .filterNot { it.id == playingId }
        HomeUiState(
            sections = homeCatalog?.sections.orEmpty().ifEmpty { exploreCatalog?.sections.orEmpty() },
            quickPicks = discover.ifEmpty {
                (history + favorites + trending)
                    .distinctBy { it.id }
                    .filterNot { it.id == playingId }
                    .take(16)
            },
            quickPicksArePersonal = discover.isNotEmpty(),
            pinnedPlaylists = playlists.filter { it.isPinned && it.tracks.isNotEmpty() },
            isLoading = isLoadingCatalog,
            isStale = catalogStale,
            error = catalogError,
            favoriteMoods = settings.favoriteMoods,
            keepListening = personal.keepListening.filterNot { it.id == playingId },
            forgotten = personal.forgotten,
            becauseArtist = personal.becauseArtist,
            becauseTracks = personal.becauseTracks,
            offlineTracks = downloads,
            // Podcasts and other non-music chips lead to content Moodify filters out.
            // Moods have one home (the mood pills above); keep YouTube's genre chips only.
            chips = homeChips.filterNot { chip ->
                NON_MUSIC_CHIPS.any { chip.title.contains(it, ignoreCase = true) } ||
                    com.moodify.app.ui.designsystem.isMoodTile(chip.title)
            },
            selectedChip = selectedChip,
            saved = savedCollections,
            recent = recentCollections,
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
            // With the full player open, everything behind it is hidden from
            // TalkBack so swiping can't wander into the screen underneath.
            val hiddenBehindPlayer = if (sheet.isExpanded) Modifier.clearAndSetSemantics { } else Modifier
            NavHost(
                navController = navController,
                startDestination = HomeRoute,
                modifier = Modifier.fillMaxSize().then(hiddenBehindPlayer),
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
                        scrollToTopKey = tabReselects[MainTab.HOME] ?: 0,
                        onPreview = { track -> if (track == null) manager.stopPreview() else manager.startPreview(track) },
                        onOpenMood = { navController.navigate(com.moodify.app.ui.navigation.MoodRoute(it.id)) },
                        previewingId = previewingId,
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
                            resultsType = searchResultsType,
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
                        scrollToTopKey = tabReselects[MainTab.SEARCH] ?: 0,
                        onOpenMood = { navController.navigate(com.moodify.app.ui.navigation.MoodRoute(it.id)) },
                    )
                }
                screen<com.moodify.app.ui.navigation.FriendsRoute>(
                    enterTransition = { fadeIn(tween(Motion.MEDIUM)) },
                    exitTransition = { fadeOut(tween(Motion.SHORT)) },
                ) {
                    val prefill = pendingFriendCode
                    LaunchedEffect(prefill) { if (prefill != null) viewModel.consumeFriendInvite() }
                    val friendsList = androidx.compose.foundation.lazy.rememberLazyListState()
                    com.moodify.app.ui.designsystem.ScrollToTopEffect(tabReselects[MainTab.FRIENDS] ?: 0, friendsList)
                    com.moodify.app.ui.social.FriendsScreen(
                        state = social,
                        bottomPadding = bottomPadding,
                        onOpenAccount = { navController.navigate(com.moodify.app.ui.navigation.AccountRoute) },
                        onAddFriend = friendsViewModel::addFriend,
                        onAccept = friendsViewModel::accept,
                        onIgnore = friendsViewModel::ignore,
                        onRemove = friendsViewModel::remove,
                        onBlock = friendsViewModel::block,
                        onReport = friendsViewModel::report,
                        onDismissShared = friendsViewModel::dismissShared,
                        onJoinRoom = { roomId -> friendsViewModel.joinRoom(roomId); viewModel.setRoomSheetOpen(true) },
                        onFollow = { friend -> friendsViewModel.followFriend(friend); viewModel.setRoomSheetOpen(true) },
                        onListenTogether = { friend -> friendsViewModel.listenWith(friend); viewModel.setRoomSheetOpen(true) },
                        nowPlayingTitle = playback.currentTrack?.title,
                        onSendNowPlaying = { friend -> playback.currentTrack?.let { friendsViewModel.sendTrack(friend, it) } },
                        onRefresh = friendsViewModel::refresh,
                        conversations = conversations,
                        onOpenChat = { code -> navController.navigate(com.moodify.app.ui.navigation.ChatRoute(code)) },
                        prefillCode = prefill,
                        listState = friendsList,
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
                        scrollToTopKey = tabReselects[MainTab.LIBRARY] ?: 0,
                        saved = savedCollections,
                        onOpenSaved = { item ->
                            item.endpoint?.let { endpoint -> actions.openBrowse(endpoint, item.title) }
                        },
                        onOpenSettings = { navController.navigate(SettingsRoute) },
                        onOpenStats = { navController.navigate(com.moodify.app.ui.navigation.StatsRoute) },
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
                        isSaved = savedCollections.any { it.id == state.browseId },
                        onToggleSave = viewModel::toggleSaved,
                        onSendToFriend = { sendCollection = it },
                    )
                }
                screen<com.moodify.app.ui.navigation.ChatRoute> {
                    val chatViewModel: com.moodify.app.ui.social.ChatViewModel = viewModel()
                    val chat by chatViewModel.state.collectAsStateWithLifecycle()
                    LaunchedEffect(chatViewModel) { chatViewModel.messages.collect { snackbar.showSnackbar(it) } }
                    // Read while on screen; notifications for it resume when you leave or the app goes away.
                    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
                    androidx.compose.runtime.DisposableEffect(lifecycleOwner, chatViewModel) {
                        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                            when (event) {
                                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> chatViewModel.setVisible(true)
                                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> chatViewModel.setVisible(false)
                                else -> Unit
                            }
                        }
                        lifecycleOwner.lifecycle.addObserver(observer)
                        onDispose { lifecycleOwner.lifecycle.removeObserver(observer); chatViewModel.setVisible(false) }
                    }
                    com.moodify.app.ui.social.ChatScreen(
                        state = chat,
                        nowPlaying = playback.currentTrack,
                        onBack = { navController.popBackStack() },
                        onSend = chatViewModel::send,
                        onShareTrack = chatViewModel::shareTrack,
                        onRetry = chatViewModel::retry,
                        onReact = chatViewModel::react,
                        onJoinRoom = { roomId -> friendsViewModel.joinRoom(roomId); viewModel.setRoomSheetOpen(true) },
                        onSavePlaylist = chatViewModel::savePlaylist,
                    )
                }
                screen<com.moodify.app.ui.navigation.AccountRoute> {
                    val accountViewModel: com.moodify.app.ui.social.AccountViewModel = viewModel()
                    val account by accountViewModel.state.collectAsStateWithLifecycle()
                    // A scanned browser QR code: trust the key it carries, then compare words.
                    val deviceLink by viewModel.pendingDeviceLink.collectAsStateWithLifecycle()
                    LaunchedEffect(deviceLink, account.loading) {
                        val (linkCode, key) = deviceLink ?: return@LaunchedEffect
                        if (account.loading) return@LaunchedEffect
                        viewModel.consumeDeviceLink()
                        val keyBytes = key?.let {
                            runCatching { android.util.Base64.decode(it, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP) }.getOrNull()
                        }
                        accountViewModel.prepareLink(linkCode, keyBytes)
                    }
                    com.moodify.app.ui.social.AccountScreen(
                        state = account,
                        bottomPadding = bottomPadding,
                        onBack = { navController.popBackStack() },
                        onCreate = accountViewModel::create,
                        onRestore = accountViewModel::restore,
                        onConfirmBackedUp = accountViewModel::confirmBackedUp,
                        onRevealWords = accountViewModel::revealRecoveryWords,
                        onHideWords = accountViewModel::hideRecoveryWords,
                        onRename = accountViewModel::rename,
                        onRetry = accountViewModel::retryConnect,
                        onDelete = accountViewModel::deleteAccount,
                        onDismissMessages = accountViewModel::dismissMessages,
                        onPrepareLink = { accountViewModel.prepareLink(it) },
                        onConfirmLink = accountViewModel::confirmLink,
                        onCancelLink = accountViewModel::cancelLink,
                    )
                }
                screen<com.moodify.app.ui.navigation.StatsRoute> {
                    val stats by viewModel.listeningStats.collectAsStateWithLifecycle()
                    LaunchedEffect(Unit) { viewModel.loadListeningStats() }
                    com.moodify.app.ui.screens.StatsScreen(stats = stats, bottomPadding = bottomPadding, onBack = { navController.popBackStack() })
                }
                screen<com.moodify.app.ui.navigation.MoodRoute> {
                    val moodViewModel: com.moodify.app.ui.screens.MoodViewModel = viewModel()
                    val state by moodViewModel.state.collectAsStateWithLifecycle()
                    com.moodify.app.ui.screens.MoodScreen(
                        state = state,
                        bottomPadding = bottomPadding,
                        onBack = { navController.popBackStack() },
                        onRetry = moodViewModel::load,
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
                        onSend = { sendPlaylist = playlists.firstOrNull { it.id == id } },
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
                            onDataSaver = viewModel::setDataSaver,
                            onSkipSilence = viewModel::setSkipSilence,
                            onAutoDownloadLiked = viewModel::setAutoDownloadLiked,
                            onDownloadOnWifiOnly = viewModel::setDownloadOnWifiOnly,
                            onPauseHistory = viewModel::setPauseHistory,
                            onClearHistory = viewModel::clearHistory,
                            onRefreshStorage = viewModel::refreshStorage,
                            onClearAudioCache = viewModel::clearAudioCache,
                            onClearLyricsCache = viewModel::clearLyricsCache,
                            onClearCatalogCache = viewModel::clearCatalogCache,
                            onExportBackup = viewModel::exportBackup,
                            onImportBackup = viewModel::importBackup,
                            onOpenAccount = { navController.navigate(com.moodify.app.ui.navigation.AccountRoute) },
                            onShareListening = viewModel::setShareListening,
                        ),
                        storage = storage,
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
            // (Hidden in a conversation, where the composer sits at the bottom.)
            if (!onChatRoute) Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .then(if (sheet.isExpanded) Modifier.clearAndSetSemantics { } else Modifier)
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
                    MoodifyNavBar(selected = selectedTab, onSelect = ::selectTab, badges = mapOf(MainTab.FRIENDS to unreadMessages))
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
                                onOpenArtist = actions.openArtist,
                                onSaveToPlaylist = { pickerTrack = playback.currentTrack },
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
                                onMoreByArtist = { playback.currentTrack?.let(actions.openArtist) },
                                onStartRadio = { playback.currentTrack?.let(viewModel::startRadio) },
                                onShowRelated = {
                                    playback.currentTrack?.let(viewModel::loadRelated)
                                    showRelated = true
                                },
                                onOpenSleepTimer = { showSleep = true },
                                roomLabel = room?.let { r -> if (r.isFollowing) "Following ${r.followingName}" else "Listening room · ${r.roomId}" },
                                onListenTogether = { viewModel.setRoomSheetOpen(true) },
                                onOpenEqualizer = {
                                    val eq = Intent(android.media.audiofx.AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL)
                                        .putExtra(android.media.audiofx.AudioEffect.EXTRA_AUDIO_SESSION, manager.audioSessionId)
                                        .putExtra(android.media.audiofx.AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
                                        .putExtra(android.media.audiofx.AudioEffect.EXTRA_CONTENT_TYPE, android.media.audiofx.AudioEffect.CONTENT_TYPE_MUSIC)
                                    val opened = runCatching { context.startActivity(eq) }.isSuccess
                                    if (!opened) scope.launch { snackbar.showSnackbar("This phone has no equaliser app") }
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
        if (showRoom) {
            com.moodify.app.ui.social.ListeningRoomSheet(
                room = room,
                friends = social.lists.friends,
                hasIdentity = social.myCode != null,
                actions = com.moodify.app.ui.social.RoomActions(
                    onStart = friendsViewModel::startRoom,
                    onJoin = friendsViewModel::joinRoom,
                    onLeave = { friendsViewModel.leaveRoom(); viewModel.setRoomSheetOpen(false) },
                    onChat = friendsViewModel::roomChat,
                    onInvite = friendsViewModel::inviteToRoom,
                    onMakeDj = friendsViewModel::makeDj,
                    onMakeHost = friendsViewModel::makeHost,
                    onNext = friendsViewModel::roomNext,
                    onQueue = friendsViewModel::queueInRoom,
                    onGetCode = { viewModel.setRoomSheetOpen(false); navController.navigate(com.moodify.app.ui.navigation.AccountRoute) { launchSingleTop = true } },
                ),
                onDismiss = { viewModel.setRoomSheetOpen(false) },
            )
        }
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
                onMoreByArtist = { actions.openArtist(track) },
                onStartRadio = { viewModel.startRadio(track) },
                onSendToFriend = { sendTrack = track },
                onOpenAlbum = track.albumId?.takeIf(String::isNotBlank)?.let { albumId ->
                    {
                        collapsePlayer()
                        navController.navigate(BrowseRoute(albumId, null, "MUSIC_PAGE_TYPE_ALBUM", track.album.ifBlank { track.title }))
                    }
                },
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
        if (sendTrack != null || sendCollection != null || sendPlaylist != null) {
            com.moodify.app.ui.social.SendToFriendSheet(
                title = sendTrack?.title ?: sendCollection?.title ?: sendPlaylist?.name.orEmpty(),
                friends = social.lists.friends,
                hasIdentity = social.myCode != null,
                onPick = { friend ->
                    sendTrack?.let { friendsViewModel.sendTrack(friend, it) }
                    sendCollection?.let { friendsViewModel.sendCollection(friend, it) }
                    sendPlaylist?.let { friendsViewModel.sendPlaylist(friend, it.name, it.tracks) }
                    sendTrack = null
                    sendCollection = null
                    sendPlaylist = null
                },
                onGetCode = {
                    sendTrack = null
                    sendCollection = null
                    sendPlaylist = null
                    navController.navigate(com.moodify.app.ui.navigation.AccountRoute)
                },
                onDismiss = { sendTrack = null; sendCollection = null; sendPlaylist = null },
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