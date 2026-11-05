package com.moodify.app.ui.screens

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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.moodify.app.data.CatalogItem
import com.moodify.app.data.CatalogSection
import com.moodify.app.ui.designsystem.ArtworkImage
import com.moodify.app.ui.designsystem.AuraGlow
import com.moodify.app.ui.designsystem.CatalogShelf
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
    val suggestions: List<String> = emptyList(),
    val recentSearches: List<String> = emptyList(),
    val browseSections: List<CatalogSection> = emptyList(),
    val chartSections: List<CatalogSection> = emptyList(),
    val error: String? = null,
    val canLoadMore: Boolean = false,
    val isLoadingMore: Boolean = false,
)

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
) {
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var fieldFocused by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

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
                    SearchMode.IDLE -> IdleSearch(state, bottomPadding, onSubmit = submit, onRemoveRecent, onClearRecents)
                    SearchMode.SUGGESTING -> Suggestions(state, bottomPadding, onSubmit = submit, onFill = onQueryChange)
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
    Row(
        modifier
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
                value = query,
                onValueChange = onQueryChange,
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
    onSubmit: (String) -> Unit,
    onRemoveRecent: (String) -> Unit,
    onClearRecents: () -> Unit,
) {
    val actions = LocalMoodifyActions.current
    val browseItems = remember(state.browseSections) {
        state.browseSections.flatMap { it.items }.filter { it.endpoint?.browseId?.isNotBlank() == true }.distinctBy { it.id }
    }
    LazyColumn(
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
        if (browseItems.isNotEmpty()) {
            item(key = "browseHeader") { SectionHeader(title = "Browse moods & genres") }
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
    val tracks = remember(sections) { sections.flatMap { s -> s.items.mapNotNull { it.track } }.distinctBy { it.id } }
    val topResult = remember(sections) {
        sections.firstOrNull()?.items?.firstOrNull()
    }
    val otherSections = remember(sections) { sections.filter { s -> s.items.any { it.track == null } } }

    when {
        state.isSearching && sections.isEmpty() -> SkeletonRows(count = 8, modifier = Modifier.padding(top = Spacing.lg))
        state.error != null && sections.isEmpty() -> ErrorState(message = state.error, onRetry = onRetry)
        sections.isEmpty() -> EmptyState(
            icon = Icons.Rounded.SearchOff,
            title = "No matches",
            message = "Try another spelling, an artist name, or describe a feeling.",
        )
        else -> LazyColumn(
            state = listState,
            contentPadding = PaddingValues(top = Spacing.md, bottom = bottomPadding + Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            if (topResult != null && state.type == null) {
                item(key = "top") { TopResultCard(topResult, tracks) }
                item(key = "topSpacer") { Spacer(Modifier.height(Spacing.lg)) }
            }
            if (tracks.isNotEmpty()) {
                item(key = "songsHeader") { SectionHeader(title = "Songs") }
                items(tracks, key = { "song:${it.id}" }) { track ->
                    TrackRow(
                        track = track,
                        queueContext = tracks,
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

