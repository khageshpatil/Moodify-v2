package com.moodify.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.moodify.app.data.LocalPlaylist
import com.moodify.app.ui.designsystem.AuraGlow
import com.moodify.app.ui.designsystem.EmptyState
import com.moodify.app.ui.designsystem.pressable
import com.moodify.app.ui.designsystem.sharedArtwork
import com.moodify.app.ui.player.PlaylistCollage
import com.moodify.app.ui.theme.MoodHues
import com.moodify.app.ui.theme.Radii
import com.moodify.app.ui.theme.Spacing

private enum class PlaylistSort(val label: String) { RECENT("Recent"), NAME("A–Z"), SIZE("Most songs") }

/** What the Library shows below the fixed tiles. */
private enum class LibraryFilter(val label: String) { ALL("All"), PLAYLISTS("Playlists"), ALBUMS("Albums"), ARTISTS("Artists") }

@Composable
fun LibraryScreen(
    playlists: List<LocalPlaylist>,
    likedCount: Int,
    downloadCount: Int,
    historyCount: Int,
    bottomPadding: Dp,
    onOpenLiked: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenPlaylist: (LocalPlaylist) -> Unit,
    onCreatePlaylist: (String) -> Unit,
    scrollToTopKey: Int = 0,
    saved: List<com.moodify.app.data.CatalogItem> = emptyList(),
    onOpenSaved: (com.moodify.app.data.CatalogItem) -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenStats: () -> Unit = {},
) {
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    com.moodify.app.ui.designsystem.ScrollToTopEffect(scrollToTopKey, listState)
    var showCreate by remember { mutableStateOf(false) }
    var sort by rememberSaveable { mutableStateOf(PlaylistSort.RECENT) }
    var grid by rememberSaveable { mutableStateOf(true) }
    var filter by rememberSaveable { mutableStateOf(LibraryFilter.ALL) }
    val savedPlaylists = remember(saved) { saved.filter { it.type == "playlist" } }
    val savedAlbums = remember(saved) { saved.filter { it.type == "album" } }
    val savedArtists = remember(saved) { saved.filter { it.type == "artist" } }
    val showOwnPlaylists = filter == LibraryFilter.ALL || filter == LibraryFilter.PLAYLISTS
    val sorted = remember(playlists, sort) {
        when (sort) {
            // Store order is pinned-first, then most recently updated.
            PlaylistSort.RECENT -> playlists
            PlaylistSort.NAME -> playlists.sortedBy { it.name.lowercase() }
            PlaylistSort.SIZE -> playlists.sortedByDescending { it.tracks.size }
        }
    }

    Box(Modifier.fillMaxSize()) {
        AuraGlow(intensity = 0.7f, height = 320.dp)
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(bottom = bottomPadding + Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            item(key = "header") {
                Row(
                    Modifier
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .padding(horizontal = Spacing.gutter)
                        .padding(top = Spacing.lg),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Your library",
                        style = MaterialTheme.typography.displaySmall,
                        modifier = Modifier
                            .weight(1f)
                            .semantics { heading() },
                    )
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Rounded.Settings, contentDescription = "Settings")
                    }
                    Spacer(Modifier.width(Spacing.xs))
                    IconButton(
                        onClick = { showCreate = true },
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = "Create playlist", tint = MaterialTheme.colorScheme.onPrimary)
                    }
                }
            }
            item(key = "collections") {
                Column(
                    Modifier.padding(horizontal = Spacing.gutter),
                    verticalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    CollectionTile(
                        title = "Liked songs",
                        subtitle = countLabel(likedCount),
                        icon = Icons.Rounded.Favorite,
                        colors = listOf(Color(0xFF7C4DFF), Color(0xFFE5579B)),
                        onClick = onOpenLiked,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 112.dp),
                        large = true,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                        CollectionTile(
                            title = "Your listening",
                            subtitle = "Top songs & artists",
                            icon = Icons.Rounded.Insights,
                            colors = listOf(MoodHues[5], MoodHues[4]),
                            onClick = onOpenStats,
                            modifier = Modifier.weight(1f).heightIn(min = 96.dp),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                        CollectionTile(
                            title = "Downloads",
                            subtitle = countLabel(downloadCount),
                            icon = Icons.Rounded.DownloadForOffline,
                            colors = listOf(MoodHues[2], Color(0xFF0E6E64)),
                            onClick = onOpenDownloads,
                            modifier = Modifier.weight(1f).heightIn(min = 96.dp),
                        )
                        CollectionTile(
                            title = "History",
                            subtitle = countLabel(historyCount),
                            icon = Icons.Rounded.History,
                            colors = listOf(MoodHues[3], Color(0xFFB2541B)),
                            onClick = onOpenHistory,
                            modifier = Modifier.weight(1f).heightIn(min = 96.dp),
                        )
                    }
                }
            }
            if (saved.isNotEmpty()) {
                item(key = "filters") {
                    androidx.compose.foundation.lazy.LazyRow(
                        contentPadding = PaddingValues(horizontal = Spacing.gutter),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        items(LibraryFilter.entries.toList(), key = { it.name }) { option ->
                            FilterChip(
                                selected = filter == option,
                                onClick = { filter = option },
                                label = { Text(option.label) },
                                shape = CircleShape,
                                colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                                ),
                            )
                        }
                    }
                }
            }
            if (filter == LibraryFilter.ALL) {
                savedShelf("Saved playlists", savedPlaylists, onOpenSaved)
                savedShelf("Albums", savedAlbums, onOpenSaved)
                savedShelf("Artists", savedArtists, onOpenSaved)
            } else if (filter != LibraryFilter.PLAYLISTS) {
                val shown = if (filter == LibraryFilter.ALBUMS) savedAlbums else savedArtists
                if (shown.isEmpty()) {
                    item(key = "savedEmpty") {
                        EmptyState(
                            icon = Icons.Rounded.LibraryMusic,
                            title = if (filter == LibraryFilter.ALBUMS) "No saved albums" else "No artists followed",
                            message = if (filter == LibraryFilter.ALBUMS) {
                                "Open an album and tap Save to keep it here."
                            } else {
                                "Open an artist and tap Follow to keep them here."
                            },
                        )
                    }
                }
                items(shown, key = { "saved:${it.id}" }) { item ->
                    com.moodify.app.ui.designsystem.CompactBrowseRow(
                        item = item,
                        onClick = { onOpenSaved(item) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter - Spacing.sm),
                    )
                }
            }
            if (filter == LibraryFilter.PLAYLISTS && savedPlaylists.isNotEmpty()) {
                items(savedPlaylists, key = { "savedpl:${it.id}" }) { item ->
                    com.moodify.app.ui.designsystem.CompactBrowseRow(
                        item = item,
                        onClick = { onOpenSaved(item) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter - Spacing.sm),
                    )
                }
            }
            if (showOwnPlaylists) item(key = "playlistsHeader") {
                Column {
                    Text(
                        "Your playlists",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.xs),
                    )
                    Row(
                        Modifier.padding(horizontal = Spacing.gutter),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Sorting only means something with more than one playlist.
                        if (playlists.size > 1) PlaylistSort.entries.forEach { option ->
                            FilterChip(
                                selected = sort == option,
                                onClick = { sort = option },
                                label = { Text(option.label) },
                                shape = CircleShape,
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { grid = !grid }) {
                            Icon(
                                if (grid) Icons.AutoMirrored.Rounded.ViewList else Icons.Rounded.GridView,
                                contentDescription = if (grid) "Show as list" else "Show as grid",
                            )
                        }
                    }
                }
            }
            if (!showOwnPlaylists) {
                // Albums / Artists filters show only saved items.
            } else if (sorted.isEmpty()) {
                item(key = "empty") {
                    EmptyState(
                        icon = Icons.Rounded.LibraryMusic,
                        title = "Make it yours",
                        message = "Create a playlist, or save songs from the player to start one.",
                        actionLabel = "Create playlist",
                        onAction = { showCreate = true },
                    )
                }
            } else if (grid) {
                items(sorted.chunked(2), key = { row -> row.joinToString { it.id } }) { row ->
                    Row(
                        Modifier.padding(horizontal = Spacing.gutter),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
                    ) {
                        row.forEach { playlist ->
                            PlaylistGridCard(playlist, onClick = { onOpenPlaylist(playlist) }, modifier = Modifier.weight(1f))
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            } else {
                items(sorted, key = { it.id }) { playlist ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .pressable(pressedScale = 0.98f) { onOpenPlaylist(playlist) }
                            .padding(horizontal = Spacing.gutter, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PlaylistCollage(playlist, Modifier.size(60.dp))
                        Spacer(Modifier.width(Spacing.lg))
                        Column(Modifier.weight(1f)) {
                            Text(playlist.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(countLabel(playlist.tracks.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (playlist.isPinned) {
                            Icon(Icons.Rounded.PushPin, contentDescription = "Pinned", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }

    if (showCreate) {
        CreatePlaylistDialog(
            onDismiss = { showCreate = false },
            onCreate = { name -> onCreatePlaylist(name); showCreate = false },
        )
    }
}

@Composable
fun CreatePlaylistDialog(
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
    title: String = "New playlist",
    initial: String = "",
    confirmLabel: String = "Create",
) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = { Text("Late night drive") },
                singleLine = true,
                shape = RoundedCornerShape(Radii.md),
            )
        },
        confirmButton = { TextButton(onClick = { onCreate(name) }, enabled = name.isNotBlank()) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun CollectionTile(
    title: String,
    subtitle: String,
    icon: ImageVector,
    colors: List<Color>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    large: Boolean = false,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(Radii.lg))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .background(Brush.linearGradient(listOf(colors.first().copy(alpha = 0.30f), colors.last().copy(alpha = 0.10f))))
            .pressable(haptic = true, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = "$title, $subtitle" },
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = colors.first().copy(alpha = 0.16f),
            modifier = Modifier
                .size(if (large) 120.dp else 64.dp)
                .align(Alignment.CenterEnd)
                .padding(end = Spacing.sm),
        )
        // Tiles grow with the text (large font sizes) instead of clipping it.
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(if (large) Spacing.lg else Spacing.md),
        ) {
            Icon(icon, contentDescription = null, tint = colors.first(), modifier = Modifier.size(if (large) 26.dp else 22.dp))
            Spacer(Modifier.height(if (large) Spacing.sm else Spacing.xs))
            Text(
                title,
                style = if (large) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

@Composable
private fun PlaylistGridCard(playlist: LocalPlaylist, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.pressable(onClick = onClick)) {
        Box {
            PlaylistCollage(
                playlist,
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .sharedArtwork("playlist:${playlist.id}")
                    .shadow(8.dp, RoundedCornerShape(Radii.md)),
                RoundedCornerShape(Radii.md),
            )
            if (playlist.isPinned) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(Spacing.sm)
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.PushPin, contentDescription = "Pinned", tint = Color.White, modifier = Modifier.size(16.dp))
                }
            }
        }
        Spacer(Modifier.height(Spacing.sm))
        Text(playlist.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(countLabel(playlist.tracks.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

fun countLabel(count: Int): String = if (count == 1) "1 song" else "$count songs"

/** A horizontal row of saved albums / playlists / artists (hidden when empty). */
private fun androidx.compose.foundation.lazy.LazyListScope.savedShelf(
    title: String,
    items: List<com.moodify.app.data.CatalogItem>,
    onOpen: (com.moodify.app.data.CatalogItem) -> Unit,
) {
    if (items.isEmpty()) return
    item(key = "shelf:$title") {
        Column {
            com.moodify.app.ui.designsystem.SectionHeader(title = title)
            Spacer(Modifier.height(Spacing.md))
            androidx.compose.foundation.lazy.LazyRow(
                contentPadding = PaddingValues(horizontal = Spacing.gutter),
                horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
            ) {
                items(items, key = { it.id }) { item ->
                    com.moodify.app.ui.designsystem.MediaCard(item = item, onClick = { onOpen(item) })
                }
            }
        }
    }
}
