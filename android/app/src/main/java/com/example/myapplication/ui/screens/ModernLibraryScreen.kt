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
) {
    var showCreate by remember { mutableStateOf(false) }
    var sort by rememberSaveable { mutableStateOf(PlaylistSort.RECENT) }
    var grid by rememberSaveable { mutableStateOf(true) }
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
                        modifier = Modifier.fillMaxWidth().height(112.dp),
                        large = true,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                        CollectionTile(
                            title = "Downloads",
                            subtitle = countLabel(downloadCount),
                            icon = Icons.Rounded.DownloadForOffline,
                            colors = listOf(MoodHues[2], Color(0xFF0E6E64)),
                            onClick = onOpenDownloads,
                            modifier = Modifier.weight(1f).height(96.dp),
                        )
                        CollectionTile(
                            title = "History",
                            subtitle = countLabel(historyCount),
                            icon = Icons.Rounded.History,
                            colors = listOf(MoodHues[3], Color(0xFFB2541B)),
                            onClick = onOpenHistory,
                            modifier = Modifier.weight(1f).height(96.dp),
                        )
                    }
                }
            }
            item(key = "playlistsHeader") {
                Column {
                    Text(
                        "Playlists",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.xs),
                    )
                    Row(
                        Modifier.padding(horizontal = Spacing.gutter),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PlaylistSort.entries.forEach { option ->
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
            if (sorted.isEmpty()) {
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
            .shadow(14.dp, RoundedCornerShape(Radii.lg), ambientColor = colors.first(), spotColor = colors.first())
            .clip(RoundedCornerShape(Radii.lg))
            .background(Brush.linearGradient(colors))
            .pressable(haptic = true, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = "$title, $subtitle" },
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.14f),
            modifier = Modifier
                .size(if (large) 120.dp else 64.dp)
                .align(Alignment.CenterEnd)
                .padding(end = Spacing.sm),
        )
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(Spacing.lg),
        ) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(if (large) 26.dp else 22.dp))
            Spacer(Modifier.height(Spacing.sm))
            Text(title, style = if (large) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium, color = Color.White)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.8f))
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
