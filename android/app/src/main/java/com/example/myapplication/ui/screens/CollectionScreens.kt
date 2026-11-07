package com.moodify.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.moodify.app.data.LocalPlaylist
import com.moodify.app.data.Track
import com.moodify.app.ui.designsystem.EmptyState
import com.moodify.app.ui.designsystem.LocalMoodifyActions
import com.moodify.app.ui.designsystem.TrackRow
import com.moodify.app.ui.designsystem.formatDuration
import com.moodify.app.ui.designsystem.sharedArtwork
import com.moodify.app.ui.player.PlaylistCollage
import com.moodify.app.ui.theme.MoodHues
import com.moodify.app.ui.theme.Radii
import com.moodify.app.ui.theme.Spacing

@Composable
fun PlaylistScreen(
    playlist: LocalPlaylist?,
    bottomPadding: Dp,
    onBack: () -> Unit,
    onRename: (String) -> Unit,
    onTogglePin: () -> Unit,
    onDelete: () -> Unit,
    onDownloadAll: () -> Unit = {},
    onSend: () -> Unit = {},
) {
    if (playlist == null) {
        // Deleted (or not yet loaded): leave quietly.
        androidx.compose.runtime.LaunchedEffect(Unit) { onBack() }
        return
    }
    val actions = LocalMoodifyActions.current
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val tracks = playlist.tracks
    val runtime = tracks.sumOf { it.durationSeconds.toLong() } * 1000

    DetailScaffold(
        title = playlist.name,
        subtitle = countLabel(tracks.size) + runtimeSuffix(tracks),
        eyebrow = if (playlist.isPinned) "Pinned playlist" else "Your playlist",
        heroArtworkUrl = tracks.firstOrNull { it.albumArtUrl.isNotBlank() }?.albumArtUrl,
        bottomPadding = bottomPadding,
        onBack = onBack,
        onPlay = tracks.takeIf { it.isNotEmpty() }?.let { { actions.play(it.first(), it) } },
        onShuffle = tracks.takeIf { it.size > 1 }?.let { { val s = it.shuffled(); actions.play(s.first(), s) } },
        hero = {
            PlaylistCollage(
                playlist,
                Modifier
                    .size(232.dp)
                    .sharedArtwork("playlist:${playlist.id}")
                    .shadow(32.dp, RoundedCornerShape(Radii.lg)),
                RoundedCornerShape(Radii.lg),
            )
        },
        topActions = {
            if (tracks.isNotEmpty()) {
                IconButton(onClick = onDownloadAll) {
                    Icon(Icons.Rounded.Download, contentDescription = "Download playlist")
                }
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "Playlist options") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Send to a friend") },
                        onClick = { menu = false; onSend() },
                        enabled = playlist.tracks.isNotEmpty(),
                    )
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                        onClick = { menu = false; renaming = true },
                    )
                    DropdownMenuItem(
                        text = { Text(if (playlist.isPinned) "Unpin from Home" else "Pin to Home") },
                        leadingIcon = { Icon(Icons.Rounded.PushPin, null) },
                        onClick = { menu = false; onTogglePin() },
                    )
                    DropdownMenuItem(
                        text = { Text("Delete playlist") },
                        leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null) },
                        onClick = { menu = false; confirmDelete = true },
                    )
                }
            }
        },
    ) {
        if (tracks.isEmpty()) {
            item(key = "empty") {
                EmptyState(
                    icon = Icons.Rounded.LibraryMusic,
                    title = "An empty stage",
                    message = "Use “Save to playlist” on any song to add it here.",
                    actionLabel = "Find music",
                    onAction = { actions.searchFor("") },
                )
            }
        }
        items(tracks, key = { it.id }) { track ->
            // Rows here open the track sheet with "Remove from this playlist".
            androidx.compose.runtime.CompositionLocalProvider(
                LocalMoodifyActions provides actions.withTrackMenu { actions.openTrackMenuInPlaylist(it, playlist.id) },
            ) {
                TrackRow(
                    track = track,
                    queueContext = tracks,
                    modifier = Modifier.animateItem().padding(horizontal = Spacing.gutter - Spacing.sm),
                )
            }
        }
    }

    if (renaming) {
        CreatePlaylistDialog(
            onDismiss = { renaming = false },
            onCreate = { onRename(it); renaming = false },
            title = "Rename playlist",
            initial = playlist.name,
            confirmLabel = "Save",
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete “${playlist.name}”?") },
            text = { Text("The playlist will be removed. You can undo right after.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

enum class TrackCollection(val title: String, val eyebrow: String, val icon: ImageVector, val colors: List<Color>) {
    LIKED("Liked songs", "Your favourites", Icons.Rounded.Favorite, listOf(Color(0xFF7C4DFF), Color(0xFFE5579B))),
    DOWNLOADS("Downloads", "Available offline", Icons.Rounded.DownloadForOffline, listOf(MoodHues[2], Color(0xFF0E6E64))),
    HISTORY("Recently played", "Your listening trail", Icons.Rounded.History, listOf(MoodHues[3], Color(0xFFB2541B))),
}

/** Liked songs, Downloads, History: a gradient hero tile plus a track list. */
@Composable
fun TrackCollectionScreen(
    collection: TrackCollection,
    tracks: List<Track>,
    bottomPadding: Dp,
    onBack: () -> Unit,
    onClear: (() -> Unit)? = null,
    activeDownloads: List<com.moodify.app.player.ActiveDownload> = emptyList(),
    onCancelDownload: (com.moodify.app.data.Track) -> Unit = {},
) {
    val actions = LocalMoodifyActions.current
    val runtime = tracks.sumOf { it.durationSeconds.toLong() } * 1000
    var confirmClear by remember { mutableStateOf(false) }
    DetailScaffold(
        title = collection.title,
        subtitle = countLabel(tracks.size) + runtimeSuffix(tracks),
        eyebrow = collection.eyebrow,
        heroArtworkUrl = tracks.firstOrNull { it.albumArtUrl.isNotBlank() }?.albumArtUrl,
        bottomPadding = bottomPadding,
        onBack = onBack,
        onPlay = tracks.takeIf { it.isNotEmpty() }?.let { { actions.play(it.first(), it) } },
        onShuffle = tracks.takeIf { it.size > 1 }?.let { { val s = it.shuffled(); actions.play(s.first(), s) } },
        hero = {
            Box(
                Modifier
                    .size(200.dp)
                    .shadow(32.dp, RoundedCornerShape(Radii.lg), ambientColor = collection.colors.first(), spotColor = collection.colors.first())
                    .clip(RoundedCornerShape(Radii.lg))
                    .background(Brush.linearGradient(collection.colors)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(collection.icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(84.dp))
            }
        },
        topActions = {
            if (onClear != null && tracks.isNotEmpty()) {
                IconButton(onClick = { confirmClear = true }) {
                    Icon(Icons.Rounded.DeleteSweep, contentDescription = "Clear ${collection.title.lowercase()}")
                }
            }
        },
    ) {
        if (activeDownloads.isNotEmpty()) {
            item(key = "downloadingHeader") {
                com.moodify.app.ui.designsystem.SectionHeader(title = "Downloading", eyebrow = "${activeDownloads.size} in queue")
            }
            items(activeDownloads, key = { "dl:${it.track.id}" }) { download ->
                DownloadProgressRow(download, onCancel = { onCancelDownload(download.track) })
            }
            item(key = "downloadedHeader") {
                androidx.compose.foundation.layout.Spacer(Modifier.size(Spacing.lg))
            }
        }
        if (tracks.isEmpty() && activeDownloads.isEmpty()) {
            item(key = "empty") {
                EmptyState(
                    icon = collection.icon,
                    title = when (collection) {
                        TrackCollection.LIKED -> "No likes yet"
                        TrackCollection.DOWNLOADS -> "Nothing saved offline"
                        TrackCollection.HISTORY -> "Your trail starts here"
                    },
                    message = when (collection) {
                        TrackCollection.LIKED -> "Tap the heart on any song and it lands here."
                        TrackCollection.DOWNLOADS -> "Download songs from the player to listen without a connection."
                        TrackCollection.HISTORY -> "Songs you play will appear here."
                    },
                )
            }
        }
        items(tracks, key = { it.id }) { track ->
            TrackRow(
                track = track,
                queueContext = tracks,
                modifier = Modifier.animateItem().padding(horizontal = Spacing.gutter - Spacing.sm),
            )
        }
    }
    if (confirmClear && onClear != null) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear listening history?") },
            text = { Text("This only affects this device.") },
            confirmButton = { TextButton(onClick = { confirmClear = false; onClear() }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

/** " · 1:23:45" only when nearly every track's length is known; a partial sum would mislead. */
internal fun runtimeSuffix(tracks: List<Track>): String {
    if (tracks.isEmpty()) return ""
    val known = tracks.count { it.durationSeconds > 0 }
    if (known < tracks.size * 0.9) return ""
    return " · " + formatDuration(tracks.sumOf { it.durationSeconds.toLong() } * 1000)
}

@Composable
private fun DownloadProgressRow(download: com.moodify.app.player.ActiveDownload, onCancel: () -> Unit) {
    val colors = androidx.compose.material3.MaterialTheme.colorScheme
    androidx.compose.foundation.layout.Row(
        Modifier
            .padding(horizontal = Spacing.gutter, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        com.moodify.app.ui.designsystem.ArtworkImage(
            url = download.track.albumArtUrl,
            contentDescription = null,
            placeholderKey = download.track.id,
            shape = RoundedCornerShape(Radii.sm),
            modifier = Modifier.size(48.dp),
        )
        androidx.compose.foundation.layout.Spacer(Modifier.size(Spacing.md))
        androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
            Text(download.track.title, style = androidx.compose.material3.MaterialTheme.typography.titleSmall, maxLines = 1)
            Text(
                when {
                    !download.isRunning -> "Waiting…"
                    download.progress != null -> "${download.progress}%"
                    else -> "Starting…"
                },
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
            androidx.compose.foundation.layout.Spacer(Modifier.size(4.dp))
            val progress = download.progress
            if (download.isRunning && progress != null) {
                androidx.compose.material3.LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.fillMaxWidth(),
                    color = colors.tertiary,
                )
            } else {
                androidx.compose.material3.LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = colors.tertiary)
            }
        }
        IconButton(onClick = onCancel) { Icon(Icons.Rounded.Close, contentDescription = "Cancel download of ${download.track.title}") }
    }
}