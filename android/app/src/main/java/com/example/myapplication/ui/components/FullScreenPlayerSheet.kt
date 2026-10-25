package com.example.myapplication.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.myapplication.data.LyricsDocument
import com.example.myapplication.data.LocalPlaylist
import com.example.myapplication.player.PlaybackState
import com.example.myapplication.ui.theme.*

/**
 * Full-screen player bottom sheet.
 *
 * Contains only Compose UI — album art, track info, seek slider, and
 * playback controls.  The YouTube WebView is NOT part of this composable;
 * it lives permanently in the Activity's view hierarchy and is managed by
 * [com.example.myapplication.player.PlaybackManager].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullScreenPlayerSheet(
    state: PlaybackState,
    onDismiss: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onToggleShuffle: () -> Unit,
    onToggleRepeat: () -> Unit,
    onRetry: () -> Unit,
    onQueueItemClick: (com.example.myapplication.data.Track) -> Unit,
    onRemoveFromQueue: (Int) -> Unit,
    onMoveQueueItem: (Int, Int) -> Unit = { _, _ -> },
    onClearQueue: () -> Unit = {},
    onSetSpeed: (Float) -> Unit,
    onSetPitch: (Float) -> Unit,
    onDownload: () -> Unit,
    playlists: List<LocalPlaylist> = emptyList(),
    onAddToPlaylist: (String) -> Unit = {},
    onCreatePlaylistAndAdd: (String) -> Unit = {},
    lyrics: LyricsDocument?,
    isLoadingLyrics: Boolean,
    onLoadLyrics: () -> Unit
) {
    val track = state.currentTrack ?: return
    val colors = MaterialTheme.colorScheme
    var showQueue by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showLyrics by remember { mutableStateOf(false) }
    var showPlaylistPicker by remember { mutableStateOf(false) }

    val imageScale by animateFloatAsState(
        targetValue = if (state.isPlaying) 1f else 0.92f,
        label = "imageScale"
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = colors.background,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 12.dp)
                    .width(40.dp)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(colors.onSurfaceVariant.copy(alpha = 0.5f))
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = "Dismiss",
                        tint = colors.onSurface,
                        modifier = Modifier.size(28.dp)
                    )
                }
                Text(
                    text = "NOW PLAYING",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                    color = colors.primary
                )
                IconButton(onClick = { showQueue = !showQueue }) {
                    Icon(
                        imageVector = Icons.Default.QueueMusic,
                        contentDescription = "Queue",
                        tint = colors.onSurface
                    )
                }
                Box {
                    IconButton(onClick = { showSettings = !showSettings }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Player settings", tint = colors.onSurface)
                    }
                    DropdownMenu(
                        expanded = showSettings,
                        onDismissRequest = { showSettings = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Speed 0.75x") },
                            onClick = { onSetSpeed(0.75f); showSettings = false }
                        )
                        DropdownMenuItem(
                            text = { Text("Speed 1.0x") },
                            onClick = { onSetSpeed(1f); showSettings = false }
                        )
                        DropdownMenuItem(
                            text = { Text("Speed 1.25x") },
                            onClick = { onSetSpeed(1.25f); showSettings = false }
                        )
                        DropdownMenuItem(
                            text = { Text("Speed 1.5x") },
                            onClick = { onSetSpeed(1.5f); showSettings = false }
                        )
                        DropdownMenuItem(
                            text = { Text("Pitch down") },
                            onClick = { onSetPitch(0.9f); showSettings = false }
                        )
                        DropdownMenuItem(
                            text = { Text("Normal pitch") },
                            onClick = { onSetPitch(1f); showSettings = false }
                        )
                        DropdownMenuItem(
                            text = { Text(if (state.downloadedTrackIds.contains(track.id)) "Downloaded" else "Download audio") },
                            enabled = !state.downloadedTrackIds.contains(track.id),
                            onClick = { onDownload(); showSettings = false }
                        )
                        DropdownMenuItem(
                            text = { Text("Add to playlist") },
                            enabled = playlists.isNotEmpty(),
                            onClick = { showPlaylistPicker = true; showSettings = false }
                        )
                        DropdownMenuItem(
                            text = { Text("Lyrics") },
                            onClick = { showLyrics = true; onLoadLyrics(); showSettings = false }
                        )
                    }
                }
            }

            if (showPlaylistPicker) {
                PlaylistPickerDialog(
                    playlists = playlists,
                    onDismiss = { showPlaylistPicker = false },
                    onSelect = { playlistId ->
                        onAddToPlaylist(playlistId)
                        showPlaylistPicker = false
                    },
                    onCreate = { name ->
                        onCreatePlaylistAndAdd(name)
                        showPlaylistPicker = false
                    }
                )
            }

            if (showLyrics) {
                LyricsPanel(
                    lyrics = lyrics,
                    isLoading = isLoadingLyrics,
                    onClose = { showLyrics = false },
                    positionMs = state.positionMs,
                    onSeekTo = onSeekTo,
                )
            }

            if (showQueue) {
                QueuePreview(
                    state = state,
                    onQueueItemClick = onQueueItemClick,
                    onRemoveFromQueue = onRemoveFromQueue,
                    onMoveQueueItem = onMoveQueueItem,
                    onClearQueue = onClearQueue,
                )
            }

            // Album artwork — animates smaller when paused
            Box(
                modifier = Modifier
                    .size(280.dp)
                    .scale(imageScale)
                    .clip(RoundedCornerShape(24.dp))
                    .background(colors.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = track.albumArtUrl,
                    contentDescription = track.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Song info
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = track.title,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.onSurface,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = track.artist,
                    fontSize = 15.sp,
                    color = colors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Loading indicator
            if (state.isLoading) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp),
                    color = colors.secondary,
                    trackColor = Color.Transparent
                )
            }

            // Error message
            if (state.errorMessage != null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = state.errorMessage,
                        color = colors.secondary,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                    TextButton(onClick = onRetry) { Text("Retry", color = colors.primary) }
                }
            }

            // Seek slider + time labels
            Column(modifier = Modifier.fillMaxWidth()) {
                var sliderPosition by remember(state.positionMs) {
                    mutableFloatStateOf(state.positionMs.toFloat())
                }
                val currentPos = sliderPosition
                val totalDuration = state.durationMs.coerceAtLeast(1L).toFloat()

                Slider(
                    value = currentPos.coerceIn(0f, totalDuration),
                    onValueChange = { sliderPosition = it },
                    onValueChangeFinished = { onSeekTo(sliderPosition.toLong()) },
                    valueRange = 0f..totalDuration,
                    colors = SliderDefaults.colors(
                        thumbColor = colors.secondary,
                        activeTrackColor = colors.primary,
                        inactiveTrackColor = colors.onSurfaceVariant.copy(alpha = 0.3f)
                    )
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = formatTime(state.positionMs),
                        fontSize = 12.sp,
                        color = colors.onSurfaceVariant
                    )
                    Text(
                        text = formatTime(state.durationMs),
                        fontSize = 12.sp,
                        color = colors.onSurfaceVariant
                    )
                }
            }

            // Playback controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onToggleShuffle) {
                    Icon(
                        imageVector = Icons.Default.Shuffle,
                        contentDescription = "Shuffle",
                        tint = if (state.isShuffle) colors.primary else colors.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                }

                IconButton(onClick = onPrevious) {
                    Icon(
                        imageVector = Icons.Default.SkipPrevious,
                        contentDescription = "Previous",
                        tint = colors.onSurface,
                        modifier = Modifier.size(36.dp)
                    )
                }

                Box(
                    modifier = Modifier
                        .size(68.dp)
                        .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(colors.primary, colors.secondary))),
                    contentAlignment = Alignment.Center
                ) {
                    IconButton(onClick = onTogglePlayPause) {
                        Icon(
                            imageVector = if (state.isPlaying) Icons.Default.Pause
                            else Icons.Default.PlayArrow,
                            contentDescription = "Play/Pause",
                            tint = colors.onPrimary,
                            modifier = Modifier.size(38.dp)
                        )
                    }
                }

                IconButton(onClick = onNext) {
                    Icon(
                        imageVector = Icons.Default.SkipNext,
                        contentDescription = "Next",
                        tint = colors.onSurface,
                        modifier = Modifier.size(36.dp)
                    )
                }

                IconButton(onClick = onToggleRepeat) {
                    Icon(
                        imageVector = Icons.Default.Repeat,
                        contentDescription = "Repeat",
                        tint = if (state.isRepeat) colors.primary else colors.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun PlaylistPickerDialog(
    playlists: List<LocalPlaylist>,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    onCreate: (String) -> Unit,
) {
    var newName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save to playlist") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                playlists.forEach { playlist ->
                    TextButton(
                        onClick = { onSelect(playlist.id) },
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 4.dp),
                    ) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(playlist.name)
                            Text("${playlist.tracks.size}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("New playlist") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(newName); newName = "" },
                enabled = newName.isNotBlank(),
            ) { Text("Create and save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun QueuePreview(
    state: PlaybackState,
    onQueueItemClick: (com.example.myapplication.data.Track) -> Unit,
    onRemoveFromQueue: (Int) -> Unit,
    onMoveQueueItem: (Int, Int) -> Unit,
    onClearQueue: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 190.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("UP NEXT", color = colors.primary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            TextButton(
                onClick = onClearQueue,
                enabled = state.queue.size > 1,
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
            ) { Text("Clear", fontSize = 12.sp) }
        }
        androidx.compose.foundation.lazy.LazyColumn {
            items(state.queue.size) { index ->
                val queuedTrack = state.queue[index]
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onQueueItemClick(queuedTrack) }
                        .padding(vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (index == state.currentIndex) "▶" else "${index + 1}",
                        color = if (index == state.currentIndex) colors.primary else colors.onSurfaceVariant,
                        modifier = Modifier.width(30.dp)
                    )
                    Text(
                        text = queuedTrack.title,
                        color = colors.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (index != state.currentIndex) {
                        IconButton(
                            onClick = {
                                val target = if (index > state.currentIndex) index - 1 else index + 1
                                if (target in state.queue.indices && target != state.currentIndex) {
                                    onMoveQueueItem(index, target)
                                }
                            },
                            modifier = Modifier.size(34.dp),
                        ) {
                            Icon(
                                imageVector = if (index > state.currentIndex) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                contentDescription = if (index > state.currentIndex) "Move earlier" else "Move later",
                                tint = colors.onSurfaceVariant,
                            )
                        }
                    }
                    IconButton(onClick = { onRemoveFromQueue(index) }) {
                        Icon(Icons.Default.Close, contentDescription = "Remove", tint = colors.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun LyricsPanel(
    lyrics: LyricsDocument?,
    isLoading: Boolean,
    onClose: () -> Unit,
    positionMs: Long,
    onSeekTo: (Long) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val activeIndex = remember(lyrics, positionMs) {
        if (lyrics?.isSynced == true) {
            lyrics.lines.indexOfLast { line ->
                val start = line.startMs ?: return@indexOfLast false
                start <= positionMs
            }
        } else {
            -1
        }
    }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()

    LaunchedEffect(activeIndex, lyrics?.isSynced) {
        if (activeIndex >= 0 && lyrics?.isSynced == true) {
            listState.animateScrollToItem((activeIndex - 2).coerceAtLeast(0))
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 360.dp)
            .background(colors.surfaceVariant, RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("LYRICS", color = colors.primary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                if (lyrics?.isSynced == true) {
                    Text(
                        text = "LIVE · ${lyrics.source}",
                        color = colors.onSurfaceVariant,
                        fontSize = 10.sp,
                        letterSpacing = 0.8.sp,
                    )
                }
            }
            TextButton(onClick = onClose) { Text("Close", color = colors.onSurfaceVariant) }
        }
        when {
            isLoading -> CircularProgressIndicator(color = colors.primary, modifier = Modifier.size(24.dp))
            lyrics == null || lyrics.lines.isEmpty() -> Text(
                "Lyrics are not available for this track.",
                color = colors.onSurfaceVariant,
                fontSize = 13.sp,
            )
            else -> {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    itemsIndexed(
                        items = lyrics.lines,
                        key = { index, line -> "${index}:${line.startMs}:${line.text.hashCode()}" },
                    ) { index, line ->
                        val isActive = index == activeIndex
                        val startMs = line.startMs
                        Text(
                            text = line.text,
                            color = if (isActive) colors.onPrimaryContainer else colors.onSurfaceVariant,
                            fontSize = if (isActive) 18.sp else 15.sp,
                            lineHeight = if (isActive) 24.sp else 21.sp,
                            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isActive) colors.primaryContainer else Color.Transparent)
                                .clickable(enabled = startMs != null) {
                                    startMs?.let(onSeekTo)
                                }
                                .padding(horizontal = 12.dp, vertical = 9.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun formatTime(millis: Long): String {
    val totalSeconds = millis / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format("%d:%02d", minutes, seconds)
}
