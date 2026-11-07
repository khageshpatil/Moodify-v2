package com.moodify.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.NorthWest
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.moodify.app.data.CatalogItem
import com.moodify.app.data.CatalogSection
import com.moodify.app.ui.designsystem.ArtworkImage
import com.moodify.app.ui.designsystem.AuraGlow
import com.moodify.app.ui.designsystem.CatalogShelf
import com.moodify.app.ui.designsystem.CompactBrowseRow
import com.moodify.app.ui.designsystem.EmptyState
import com.moodify.app.ui.designsystem.ErrorState
import com.moodify.app.ui.designsystem.LocalMoodifyActions
import com.moodify.app.ui.designsystem.MoodTile
import com.moodify.app.ui.designsystem.SectionHeader
import com.moodify.app.ui.designsystem.SkeletonRows
import com.moodify.app.ui.designsystem.TrackRow
import com.moodify.app.ui.designsystem.isArtist
import com.moodify.app.ui.designsystem.musicOnly
import com.moodify.app.ui.designsystem.placeholderHue
import com.moodify.app.ui.designsystem.pressable
import com.moodify.app.ui.designsystem.rememberCatalogClick
import com.moodify.app.ui.designsystem.subtitle
import com.moodify.app.ui.theme.LocalArtworkColors
import com.moodify.app.ui.theme.Radii
import com.moodify.app.ui.theme.Spacing

data class SearchUiState(
    val query: String = "",
    val type: String? = null,
    val isSearching: Boolean = false,
    val resultSections: List<CatalogSection> = emptyList(),
    /** The filter [resultSections] were fetched for; differs from [type] while a new chip loads. */
    val resultsType: String? = null,
    val suggestions: List<String> = emptyList(),
    val recentSearches: List<String> = emptyList(),
    val browseSections: List<CatalogSection> = emptyList(),
    val chartSections: List<CatalogSection> = emptyList(),
    val error: String? = null,
    val canLoadMore: Boolean = false,
    val isLoadingMore: Boolean = false,
)

private val BROWSE_FILTERS = setOf("album", "artist", "playlist")

/** Same song listed twice (e.g. "Whispers of Bliss" ×2): keep the first by id, then by title + artist. */
private fun dedupeTracks(tracks: List<com.moodify.app.data.Track>): List<com.moodify.app.data.Track> {
    val seen = HashSet<String>()
    return tracks.distinctBy { it.id }.filter { track ->
        seen.add(track.title.lowercase().replace(Regex("""\s+"""), " ").trim() + "|" + track.artist.lowercase().trim())
    }
}

private val FILTERS = listOf(null to "All", "song" to "Songs", "album" to "Albums", "artist" to "Artists", "playlist" to "Playlists", "video" to "Videos")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(
    state: SearchUiState,
    bottomPadding: Dp,
    focusRequestKey: Int,
    onQueryChange: (String) -> Unit,
    onSubmit: (String) -> Unit,
    onTypeChange: (String?) -> Unit,
    onRemoveRecent: (String) -> Unit,
    onClearRecents: () -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    scrollToTopKey: Int = 0,
    onOpenMood: (com.moodify.app.ui.designsystem.MoodOption) -> Unit = {},
) {
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var fieldFocused by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val idleListState = rememberLazyListState()
    com.moodify.app.ui.designsystem.ScrollToTopEffect(scrollToTopKey, listState)
    com.moodify.app.ui.designsystem.ScrollToTopEffect(scrollToTopKey, idleListState)

    LaunchedEffect(focusRequestKey) {
        if (focusRequestKey > 0) runCatching { focusRequester.requestFocus() }
    }

    // Dismiss the keyboard as soon as results are scrolled.
    val hideKeyboardOnScroll = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y < -4f) {
                    keyboard?.hide()
                    focusManager.clearFocus()
                }
                return Offset.Zero
            }
        }
    }

    // Infinite scroll for results.
    val latestLoadMore by rememberUpdatedState(onLoadMore)
    val nearEnd by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 0 && last >= info.totalItemsCount - 4
        }
    }
    val latestState by rememberUpdatedState(state)
    LaunchedEffect(Unit) {
        snapshotFlow { nearEnd }.collect { near ->
            if (near && latestState.canLoadMore && !latestState.isLoadingMore) latestLoadMore()
        }
    }

    val submit: (String) -> Unit = { text ->
        onSubmit(text)
        keyboard?.hide()
        focusManager.clearFocus()
    }
    // Recents and suggestions are new searches, so they start unfiltered.
    val submitFresh: (String) -> Unit = { text ->
        onTypeChange(null)
        submit(text)
    }

    // Closing the keyboard (or Back) ends editing and returns to the results.
    val imeVisible = WindowInsets.isImeVisible
    var imeWasVisible by remember { mutableStateOf(false) }
    LaunchedEffect(imeVisible) {
        if (imeVisible) {
            imeWasVisible = true
        } else if (imeWasVisible) {
            imeWasVisible = false
            focusManager.clearFocus()
        }
    }
    BackHandler(enabled = fieldFocused) { focusManager.clearFocus() }

    Box(Modifier.fillMaxSize()) {
        AuraGlow(intensity = 0.6f, height = 300.dp)
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
            Text(
                "Search",
                style = MaterialTheme.typography.displaySmall,
                modifier = Modifier
                    .padding(horizontal = Spacing.gutter)
                    .padding(top = Spacing.lg, bottom = Spacing.md)
                    .semantics { heading() },
            )
            SearchField(
                query = state.query,
                onQueryChange = onQueryChange,
                onSubmit = submit,
                modifier = Modifier
                    .padding(horizontal = Spacing.gutter)
                    .focusRequester(focusRequester)
                    .onFocusChanged { fieldFocused = it.isFocused },
            )
            AnimatedVisibility(
                visible = state.query.isNotBlank(),
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = Spacing.gutter),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    modifier = Modifier.padding(top = Spacing.md),
                ) {
                    items(FILTERS, key = { it.second }) { (type, label) ->
                        FilterChip(
                            selected = state.type == type,
                            onClick = { onTypeChange(type) },
                            label = { Text(label) },
                            shape = CircleShape,
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                        )
                    }
                }
            }
            Spacer(Modifier.height(Spacing.sm))

            val mode = when {
                state.query.isBlank() -> SearchMode.IDLE
                fieldFocused && state.suggestions.isNotEmpty() -> SearchMode.SUGGESTING
                else -> SearchMode.RESULTS
            }
            AnimatedContent(
                targetState = mode,
                transitionSpec = { fadeIn().togetherWith(fadeOut()) },
                label = "searchMode",
            ) { shown ->
                when (shown) {
                    SearchMode.IDLE -> IdleSearch(state, bottomPadding, idleListState, onSubmit = submitFresh, onRemoveRecent, onClearRecents, onOpenMood)
                    SearchMode.SUGGESTING -> Suggestions(state, bottomPadding, onSubmit = submitFresh, onFill = onQueryChange)
                    SearchMode.RESULTS -> Box(Modifier.nestedScroll(hideKeyboardOnScroll)) {
                        Results(state, bottomPadding, listState, onRetry)
                    }
                }
            }
        }
    }
}

private enum class SearchMode { IDLE, SUGGESTING, RESULTS }

@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onSubmit: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    // The text always comes from [query]; only the cursor is kept here.
    var selection by remember { mutableStateOf(TextRange(query.length)) }
    var composition by remember { mutableStateOf<TextRange?>(null) }
    var lastTyped by remember { mutableStateOf(query) }
    if (query != lastTyped) {
        // Changed from outside (cleared, a suggestion filled in): cursor to the end.
        lastTyped = query
        selection = TextRange(query.length)
        composition = null
    }
    val value = TextFieldValue(
        text = query,
        selection = TextRange(selection.start.coerceIn(0, query.length), selection.end.coerceIn(0, query.length)),
        composition = composition?.takeIf { it.max <= query.length },
    )
    // The tap that focuses the field would drop the cursor wherever it landed
    // (often the start), so the next keystrokes glue onto the old query. The
    // first selection change after focusing is moved to the end instead.
    var justFocused by remember { mutableStateOf(false) }
    Row(
        modifier
            .onFocusChanged { if (it.isFocused) justFocused = true }
            .fillMaxWidth()
            .height(56.dp)
            .clip(CircleShape)
            .background(colors.surfaceContainerHigh)
            .border(1.dp, colors.outlineVariant, CircleShape)
            .padding(start = Spacing.lg, end = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, contentDescription = null, tint = colors.onSurfaceVariant)
        Spacer(Modifier.width(Spacing.md))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text("Songs, artists, or a feeling…", style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
            }
            BasicTextField(
                value = value,
                onValueChange = { next ->
                    selection = if (justFocused && next.text == query) TextRange(next.text.length) else next.selection
                    composition = next.composition
                    justFocused = false
                    if (next.text != query) {
                        lastTyped = next.text
                        onQueryChange(next.text)
                    }
                },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
                cursorBrush = SolidColor(colors.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmit(query) }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) {
            IconButton(onClick = { onQueryChange("") }) {
                Icon(Icons.Rounded.Close, contentDescription = "Clear search", tint = colors.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IdleSearch(
    state: SearchUiState,
    bottomPadding: Dp,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onSubmit: (String) -> Unit,
    onRemoveRecent: (String) -> Unit,
    onClearRecents: () -> Unit,
    onOpenMood: (com.moodify.app.ui.designsystem.MoodOption) -> Unit,
) {
    val actions = LocalMoodifyActions.current
    // Moods are Moodify's own (same as Home); YouTube's tiles add the genres.
    val browseItems = remember(state.browseSections) {
        state.browseSections.flatMap { it.items }
            .filter { it.endpoint?.browseId?.isNotBlank() == true }
            .filterNot { com.moodify.app.ui.designsystem.isMoodTile(it.title) }
            .distinctBy { it.id }
    }
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(top = Spacing.md, bottom = bottomPadding + Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.xl),
    ) {
        if (state.recentSearches.isNotEmpty()) {
            item(key = "recent") {
                Column {
                    Row(Modifier.padding(horizontal = Spacing.gutter), verticalAlignment = Alignment.CenterVertically) {
                        Text("Recent", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        TextButton(onClick = onClearRecents) { Text("Clear") }
                    }
                    FlowRow(
                        Modifier.padding(horizontal = Spacing.gutter),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        state.recentSearches.forEach { recent ->
                            InputChip(
                                selected = false,
                                onClick = { onSubmit(recent) },
                                label = { Text(recent, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                leadingIcon = { Icon(Icons.Rounded.History, contentDescription = null, modifier = Modifier.size(16.dp)) },
                                trailingIcon = {
                                    Icon(
                                        Icons.Rounded.Close,
                                        contentDescription = "Remove $recent",
                                        modifier = Modifier.size(16.dp).pressable { onRemoveRecent(recent) },
                                    )
                                },
                                shape = CircleShape,
                            )
                        }
                    }
                }
            }
        }
        item(key = "moodsHeader") { SectionHeader(title = "Moods") }
        items(com.moodify.app.ui.designsystem.MoodOptions.chunked(2), key = { row -> "mood:" + row.joinToString { it.id } }) { row ->
            Row(
                Modifier.padding(horizontal = Spacing.gutter),
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                row.forEach { mood ->
                    MoodTile(
                        title = mood.label,
                        accent = mood.hue,
                        onClick = { onOpenMood(mood) },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        if (browseItems.isNotEmpty()) {
            item(key = "browseHeader") { SectionHeader(title = "Genres & more") }
            items(browseItems.chunked(2), key = { row -> row.joinToString { it.id } }) { row ->
                Row(
                    Modifier.padding(horizontal = Spacing.gutter),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    row.forEach { item ->
                        val accent = com.moodify.app.ui.designsystem.vividTileColor(item.accentColor, item.title)
                        MoodTile(
                            title = item.title,
                            accent = accent,
                            onClick = { item.endpoint?.let { actions.openBrowse(it, item.title) } },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        items(state.chartSections.map { it.musicOnly() }.filter { it.items.isNotEmpty() }.take(4), key = { "chart:${it.id}" }) { section ->
            CatalogShelf(section)
        }
    }
}

@Composable
private fun Suggestions(state: SearchUiState, bottomPadding: Dp, onSubmit: (String) -> Unit, onFill: (String) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(top = Spacing.sm, bottom = bottomPadding)) {
        items(state.suggestions, key = { it }) { suggestion ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .pressable(pressedScale = 0.99f) { onSubmit(suggestion) }
                    .padding(start = Spacing.gutter, end = Spacing.sm, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(Spacing.lg))
                Text(suggestion, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                IconButton(onClick = { onFill(suggestion) }) {
                    Icon(Icons.Rounded.NorthWest, contentDescription = "Use $suggestion", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun Results(
    state: SearchUiState,
    bottomPadding: Dp,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onRetry: () -> Unit,
) {
    val sections = remember(state.resultSections, state.type) {
        state.resultSections
            .map { if (state.type == "video") it else it.musicOnly() }
            .filter { it.items.isNotEmpty() }
    }
    // Video shelves (live clips, TV performances, fan uploads) stay out of Songs.
    val isVideoShelf: (CatalogSection) -> Boolean = { it.title.contains("video", ignoreCase = true) || it.title.contains("episode", ignoreCase = true) }
    val tracks = remember(sections) {
        val songSections = if (state.type == "video") sections else sections.filterNot(isVideoShelf)
        dedupeTracks(songSections.flatMap { s -> s.items.mapNotNull { it.track } })
    }
    val videos = remember(sections, state.type) {
        if (state.type != null) emptyList() else dedupeTracks(sections.filter(isVideoShelf).flatMap { s -> s.items.mapNotNull { it.track } })
            .filterNot { video -> tracks.any { it.id == video.id } }
            .take(5)
    }
    val topResult = remember(sections) {
        sections.firstOrNull()?.items?.firstOrNull()
    }
    val otherSections = remember(sections) { sections.filter { s -> s.items.any { it.track == null } } }
    // Album / artist / playlist filters list their results top to bottom.
    val browseResults = remember(sections, state.type) {
        if (state.type in BROWSE_FILTERS) {
            sections.flatMap { it.items }.filter { it.track == null }.distinctBy { "${it.type}:${it.id}" }
        } else {
            emptyList()
        }
    }
    val showTop = topResult != null && state.type == null
    // The top result is already on screen; don't repeat it as the first song.
    val songRows = remember(tracks, showTop, topResult) {
        if (showTop) tracks.filterNot { it.id == topResult?.track?.id } else tracks
    }
    val onBrowseClick = rememberCatalogClick(tracks)
    val waitingForFilter = state.resultsType != state.type
    val filterName = FILTERS.firstOrNull { it.first == state.type }?.second?.lowercase()

    when {
        waitingForFilter || (state.isSearching && sections.isEmpty()) ->
            SkeletonRows(count = 8, modifier = Modifier.padding(top = Spacing.lg))
        state.error != null && sections.isEmpty() -> ErrorState(message = state.error, onRetry = onRetry)
        sections.isEmpty() || (state.type in BROWSE_FILTERS && browseResults.isEmpty()) -> EmptyState(
            icon = Icons.Rounded.SearchOff,
            title = if (filterName == null) "No matches" else "No $filterName for “${state.query.trim()}”",
            message = if (filterName == null) {
                "Try another spelling, an artist name, or describe a feeling."
            } else {
                "Try All, or check the spelling."
            },
        )
        state.type in BROWSE_FILTERS -> LazyColumn(
            state = listState,
            contentPadding = PaddingValues(top = Spacing.sm, bottom = bottomPadding + Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            items(browseResults, key = { "${it.type}:${it.id}" }) { item ->
                CompactBrowseRow(
                    item = item,
                    onClick = { onBrowseClick(item) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.gutter - Spacing.sm),
                )
            }
            if (state.isLoadingMore) {
                item(key = "more") { SkeletonRows(count = 2, modifier = Modifier.padding(top = Spacing.md)) }
            }
        }
        else -> LazyColumn(
            state = listState,
            contentPadding = PaddingValues(top = Spacing.md, bottom = bottomPadding + Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            if (showTop && topResult != null) {
                item(key = "top") { TopResultCard(topResult, tracks) }
                item(key = "topSpacer") { Spacer(Modifier.height(Spacing.lg)) }
            }
            if (songRows.isNotEmpty()) {
                item(key = "songsHeader") { SectionHeader(title = "Songs") }
                items(songRows, key = { "song:${it.id}" }) { track ->
                    TrackRow(
                        track = track,
                        queueContext = tracks,
                        modifier = Modifier.animateItem().padding(horizontal = Spacing.gutter - Spacing.sm),
                    )
                }
            }
            if (videos.isNotEmpty()) {
                item(key = "videosHeader") { Column(Modifier.padding(top = Spacing.lg)) { SectionHeader(title = "Videos") } }
                items(videos, key = { "video:${it.id}" }) { track ->
                    TrackRow(
                        track = track,
                        queueContext = videos,
                        modifier = Modifier.padding(horizontal = Spacing.gutter - Spacing.sm),
                    )
                }
            }
            items(otherSections, key = { "section:${it.id}:${it.title}" }) { section ->
                val browseOnly = section.copy(items = section.items.filter { it.track == null })
                Column(Modifier.padding(top = Spacing.xl)) { CatalogShelf(browseOnly) }
            }
            if (state.isLoadingMore) {
                item(key = "more") { SkeletonRows(count = 2, modifier = Modifier.padding(top = Spacing.md)) }
            }
        }
    }
}

/** Big card for the best match — an artist, album or song — with a play/open action. */
@Composable
private fun TopResultCard(item: CatalogItem, sectionTracks: List<com.moodify.app.data.Track>) {
    val onClick = rememberCatalogClick(sectionTracks)
    val glow = LocalArtworkColors.current.glow
    val artwork = item.track?.albumArtUrl?.takeIf(String::isNotBlank) ?: item.artworkUrl
    Column(Modifier.padding(horizontal = Spacing.gutter)) {
        Text("TOP RESULT", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(Spacing.sm))
        Row(
            Modifier
                .fillMaxWidth()
                .shadow(12.dp, RoundedCornerShape(Radii.lg), ambientColor = glow, spotColor = glow)
                .clip(RoundedCornerShape(Radii.lg))
                .background(
                    Brush.linearGradient(
                        listOf(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.surfaceContainer),
                    ),
                )
                .pressable(pressedScale = 0.98f, haptic = true) { onClick(item) }
                .padding(Spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ArtworkImage(
                url = artwork,
                contentDescription = null,
                placeholderKey = item.id,
                shape = if (item.isArtist) CircleShape else RoundedCornerShape(Radii.md),
                modifier = Modifier.size(96.dp),
            )
            Spacer(Modifier.width(Spacing.lg))
            Column(Modifier.weight(1f)) {
                Text(item.track?.title ?: item.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                Text(item.subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            if (item.track != null) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = "Play", tint = MaterialTheme.colorScheme.onPrimary)
                    }
                }
            }
        }
    }
}

