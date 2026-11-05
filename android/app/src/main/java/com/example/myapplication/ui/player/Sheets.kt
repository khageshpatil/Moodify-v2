package com.moodify.app.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.AllInclusive
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.moodify.app.data.LocalPlaylist
import com.moodify.app.data.Track
import com.moodify.app.player.PlaybackState
import com.moodify.app.ui.designsystem.ArtworkImage
import com.moodify.app.ui.designsystem.EqualizerBars
import com.moodify.app.ui.designsystem.formatDuration
import com.moodify.app.ui.designsystem.pressable
import com.moodify.app.ui.theme.Radii
import com.moodify.app.ui.theme.Spacing
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

fun shareTrack(context: android.content.Context, track: Track) {
    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(android.content.Intent.EXTRA_SUBJECT, track.title)
        putExtra(
            android.content.Intent.EXTRA_TEXT,
            "${track.title} — ${track.artist}\nhttps://music.youtube.com/watch?v=${track.id}",
        )
    }
    context.startActivity(android.content.Intent.createChooser(intent, "Share song"))
}

@Composable
private fun SheetHeader(track: Track) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.xl, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtworkImage(track.albumArtUrl, null, Modifier.size(56.dp), RoundedCornerShape(Radii.sm), track.id)
        Spacer(Modifier.width(Spacing.lg))
        Column(Modifier.weight(1f)) {
            Text(track.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(track.artist, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

@Composable
private fun SheetAction(icon: ImageVector, label: String, onClick: () -> Unit, tint: Color = MaterialTheme.colorScheme.onSurface) {
    Row(
        Modifier
            .fillMaxWidth()
            .pressable(pressedScale = 0.98f, onClick = onClick)
            .padding(horizontal = Spacing.xl, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(Spacing.lg))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = tint)
    }
}

/** Actions for any track: queue, like, save, download, artist, share. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackActionsSheet(
    track: Track,
    isFavorite: Boolean,
    isDownloaded: Boolean,
    onDismiss: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onToggleFavorite: () -> Unit,
    onSaveToPlaylist: () -> Unit,
    onDownload: () -> Unit,
    onRemoveDownload: () -> Unit,
    onMoreByArtist: () -> Unit,
    onStartRadio: () -> Unit = {},
    extraActions: @Composable (dismiss: () -> Unit) -> Unit = {},
) {
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.navigationBarsPadding().padding(bottom = Spacing.md)) {
            SheetHeader(track)
            Spacer(Modifier.height(Spacing.sm))
            SheetAction(Icons.Rounded.Radio, "Start radio", { onStartRadio(); onDismiss() })
            SheetAction(Icons.Rounded.PlaylistPlay, "Play next", { onPlayNext(); onDismiss() })
            SheetAction(Icons.AutoMirrored.Rounded.QueueMusic, "Add to queue", { onAddToQueue(); onDismiss() })
            SheetAction(
                if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                if (isFavorite) "Remove from Liked songs" else "Like",
                { onToggleFavorite(); onDismiss() },
            )
            SheetAction(Icons.AutoMirrored.Rounded.PlaylistAdd, "Save to playlist", onSaveToPlaylist)
            if (isDownloaded) {
                SheetAction(Icons.Rounded.DownloadDone, "Remove download", { onRemoveDownload(); onDismiss() })
            } else {
                SheetAction(Icons.Rounded.Download, "Download", { onDownload(); onDismiss() })
            }
            SheetAction(Icons.Rounded.Person, "More by ${track.artist}", { onMoreByArtist(); onDismiss() })
            SheetAction(Icons.Rounded.Share, "Share", { shareTrack(context, track); onDismiss() })
            extraActions(onDismiss)
        }
    }
}

/** Pick a playlist for [track], or create a new one inline. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistPickerSheet(
    track: Track,
    playlists: List<LocalPlaylist>,
    onDismiss: () -> Unit,
    onSelect: (LocalPlaylist) -> Unit,
    onCreate: (String) -> Unit,
) {
    var newName by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.navigationBarsPadding().padding(bottom = Spacing.lg)) {
            Text(
                "Save to playlist",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = Spacing.xl, vertical = Spacing.sm),
            )
            Row(
                Modifier.padding(horizontal = Spacing.xl, vertical = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    placeholder = { Text("New playlist name") },
                    singleLine = true,
                    shape = RoundedCornerShape(Radii.md),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (newName.isNotBlank()) { onCreate(newName); onDismiss() } }),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(Spacing.sm))
                IconButton(
                    onClick = { onCreate(newName); onDismiss() },
                    enabled = newName.isNotBlank(),
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = "Create playlist and save")
                }
            }
            LazyColumn(Modifier.fillMaxWidth().height((playlists.size.coerceIn(1, 6) * 68).dp)) {
                items(playlists, key = { it.id }) { playlist ->
                    val alreadyIn = playlist.tracks.any { it.id == track.id }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .pressable(enabled = !alreadyIn, pressedScale = 0.98f) { onSelect(playlist); onDismiss() }
                            .padding(horizontal = Spacing.xl, vertical = Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PlaylistCollage(playlist, Modifier.size(52.dp))
                        Spacer(Modifier.width(Spacing.lg))
                        Column(Modifier.weight(1f)) {
                            Text(playlist.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                if (alreadyIn) "Already saved here" else "${playlist.tracks.size} songs",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Up to four covers from a playlist, tiled. */
@Composable
fun PlaylistCollage(playlist: LocalPlaylist, modifier: Modifier = Modifier, shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(Radii.sm)) {
    val covers = playlist.tracks.map { it.albumArtUrl }.filter(String::isNotBlank).distinct().take(4)
    Box(modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
        when {
            covers.size >= 4 -> Column(Modifier.fillMaxSize()) {
                covers.chunked(2).forEach { row ->
                    Row(Modifier.weight(1f)) {
                        row.forEach { url ->
                            ArtworkImage(url, null, Modifier.weight(1f).fillMaxHeight(), RoundedCornerShape(0.dp))
                        }
                    }
                }
            }
            covers.isNotEmpty() -> ArtworkImage(covers.first(), null, Modifier.fillMaxSize(), RoundedCornerShape(0.dp))
            else -> ArtworkImage(null, null, Modifier.fillMaxSize(), RoundedCornerShape(0.dp), placeholderKey = playlist.name)
        }
    }
}

/**
 * The queue: now playing on top, then up-next. Drag the handle to reorder,
 * swipe a row sideways to remove it (with Undo from the snackbar).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueSheet(
    state: PlaybackState,
    onDismiss: () -> Unit,
    onPlayIndex: (Int) -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
    onRemove: (Int) -> Unit,
    onClear: () -> Unit,
    onSaveAsPlaylist: (String) -> Unit,
) {
    val current = state.currentTrack ?: return
    val start = (state.currentIndex + 1).coerceAtLeast(0)
    val upNext = state.queue.drop(start)
    val rowHeight = 64.dp
    val rowHeightPx = with(LocalDensity.current) { rowHeight.toPx() }
    var draggingIndex by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var savingName by remember { mutableStateOf<String?>(null) }
    // Only show a total when nearly every length is known; a partial sum misleads.
    val remainingMs = if (upNext.count { it.durationSeconds > 0 } >= upNext.size * 0.9) {
        upNext.sumOf { it.durationSeconds.toLong() } * 1000
    } else 0L

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxHeight(0.92f).navigationBarsPadding()) {
            Row(Modifier.padding(horizontal = Spacing.xl), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Queue", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        if (upNext.isEmpty()) "Nothing up next" else "${upNext.size} up next" +
                            (if (remainingMs > 0) " · ${formatDuration(remainingMs)}" else ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { savingName = "" }, enabled = state.queue.isNotEmpty()) {
                    Icon(Icons.Rounded.Save, contentDescription = "Save queue as playlist")
                }
                IconButton(onClick = onClear, enabled = upNext.isNotEmpty()) {
                    Icon(Icons.Rounded.DeleteSweep, contentDescription = "Clear up next")
                }
            }
            savingName?.let { name ->
                Row(Modifier.padding(horizontal = Spacing.xl, vertical = Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { savingName = it },
                        placeholder = { Text("Playlist name") },
                        singleLine = true,
                        shape = RoundedCornerShape(Radii.md),
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { onSaveAsPlaylist(name); savingName = null }, enabled = name.isNotBlank()) { Text("Save") }
                }
            }
            Text(
                "NOW PLAYING",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = Spacing.xl, top = Spacing.md, bottom = Spacing.xs),
            )
            QueueRow(track = current, isCurrent = true, isPlaying = state.isPlaying, height = rowHeight)
            if (upNext.isNotEmpty()) {
                Text(
                    "UP NEXT",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = Spacing.xl, top = Spacing.lg, bottom = Spacing.xs),
                )
            }
            Box(Modifier.weight(1f)) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                itemsIndexed(upNext, key = { _, track -> track.id }) { localIndex, track ->
                    val absolute = start + localIndex
                    if (absolute == state.autoplayFromIndex) {
                        AutoplayHeader()
                    }
                    val target = if (draggingIndex >= 0) {
                        (draggingIndex + (dragOffset / rowHeightPx).roundToInt()).coerceIn(start, state.queue.lastIndex)
                    } else -1
                    val shift = when {
                        draggingIndex < 0 || absolute == draggingIndex -> 0f
                        absolute in (draggingIndex + 1)..target -> -rowHeightPx
                        absolute in target until draggingIndex -> rowHeightPx
                        else -> 0f
                    }
                    val isDragging = absolute == draggingIndex
                    SwipeToRemove(onRemove = { onRemove(absolute) }) {
                        QueueRow(
                            track = track,
                            isCurrent = false,
                            isPlaying = false,
                            height = rowHeight,
                            elevated = isDragging,
                            modifier = Modifier
                                .zIndex(if (isDragging) 1f else 0f)
                                .graphicsLayer { translationY = if (isDragging) dragOffset else shift },
                            onClick = { onPlayIndex(absolute) },
                            handleModifier = Modifier.pointerInput(absolute, state.queue.size) {
                                detectDragGestures(
                                    onDragStart = { draggingIndex = absolute; dragOffset = 0f },
                                    onDragEnd = {
                                        val to = (draggingIndex + (dragOffset / rowHeightPx).roundToInt())
                                            .coerceIn(start, state.queue.lastIndex)
                                        if (to != draggingIndex) onMove(draggingIndex, to)
                                        draggingIndex = -1
                                        dragOffset = 0f
                                    },
                                    onDragCancel = { draggingIndex = -1; dragOffset = 0f },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        dragOffset += amount.y
                                    },
                                )
                            },
                        )
                    }
                }
            }
            if (state.isLoadingRadio) {
                androidx.compose.material3.LinearProgressIndicator(
                    Modifier.fillMaxWidth().align(Alignment.TopCenter).height(2.dp),
                )
            }
            com.moodify.app.ui.designsystem.SheetSnackbarHost(Modifier.align(Alignment.BottomCenter))
            }
        }
    }
}

@Composable
private fun SwipeToRemove(onRemove: () -> Unit, content: @Composable () -> Unit) {
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var width by remember { mutableFloatStateOf(1f) }
    Box(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = (kotlin.math.abs(offset.value) / width * 2f).coerceIn(0f, 1f)))
            .onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        scope.launch {
                            if (kotlin.math.abs(offset.value) > width * 0.38f) {
                                offset.animateTo(if (offset.value > 0) width else -width)
                                onRemove()
                                offset.snapTo(0f)
                            } else {
                                offset.animateTo(0f)
                            }
                        }
                    },
                    onHorizontalDrag = { change, delta ->
                        change.consume()
                        scope.launch { offset.snapTo(offset.value + delta) }
                    },
                )
            },
    ) {
        Icon(
            Icons.Rounded.Delete,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier
                .align(if (offset.value >= 0) Alignment.CenterStart else Alignment.CenterEnd)
                .padding(horizontal = Spacing.xl),
        )
        Box(Modifier.offset { IntOffset(offset.value.roundToInt(), 0) }) { content() }
    }
}

@Composable
private fun QueueRow(
    track: Track,
    isCurrent: Boolean,
    isPlaying: Boolean,
    height: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
    elevated: Boolean = false,
    onClick: (() -> Unit)? = null,
    handleModifier: Modifier? = null,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier
            .fillMaxWidth()
            .height(height)
            .background(if (elevated) colors.surfaceContainerHighest else colors.surfaceContainerLow)
            .then(if (onClick != null) Modifier.pressable(pressedScale = 0.99f, onClick = onClick) else Modifier)
            .padding(horizontal = Spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp)) {
            ArtworkImage(track.albumArtUrl, null, Modifier.fillMaxSize(), RoundedCornerShape(Radii.sm), track.id)
            if (isCurrent) {
                Box(
                    Modifier.fillMaxSize().clip(RoundedCornerShape(Radii.sm)).background(Color.Black.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center,
                ) { EqualizerBars(playing = isPlaying, color = Color.White) }
            }
        }
        Spacer(Modifier.width(Spacing.md))
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                style = MaterialTheme.typography.titleSmall,
                color = if (isCurrent) colors.primary else colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(track.artist, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1)
        }
        if (handleModifier != null) {
            Box(
                handleModifier
                    .size(48.dp)
                    .clip(CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.DragHandle, contentDescription = "Drag to reorder", tint = colors.onSurfaceVariant)
            }
        }
    }
}

/** Speed, pitch and quick actions for the current track. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PlayerMoreSheet(
    state: PlaybackState,
    onDismiss: () -> Unit,
    onSetSpeed: (Float) -> Unit,
    onSetPitch: (Float) -> Unit,
    onSaveToPlaylist: () -> Unit,
    onMoreByArtist: () -> Unit,
    onStartRadio: () -> Unit = {},
    onShowRelated: () -> Unit = {},
) {
    val track = state.currentTrack ?: return
    val context = LocalContext.current
    var speed by remember { mutableFloatStateOf(state.playbackSpeed) }
    var pitch by remember { mutableFloatStateOf(state.pitch) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.navigationBarsPadding().padding(bottom = Spacing.lg)) {
            SheetHeader(track)
            Column(Modifier.padding(horizontal = Spacing.xl, vertical = Spacing.sm)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Speed", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Text("%.2fx".format(speed), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
                Slider(
                    value = speed,
                    onValueChange = { speed = (it * 20).roundToInt() / 20f },
                    onValueChangeFinished = { onSetSpeed(speed) },
                    valueRange = 0.5f..2f,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    listOf(0.75f, 1f, 1.25f, 1.5f).forEach { preset ->
                        FilterChip(
                            selected = speed == preset,
                            onClick = { speed = preset; onSetSpeed(preset) },
                            label = { Text("${preset}x") },
                        )
                    }
                }
                Spacer(Modifier.height(Spacing.lg))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Pitch", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = { pitch = 1f; onSetPitch(1f) }, enabled = pitch != 1f) { Text("Reset") }
                    Text("%.2f".format(pitch), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
                Slider(
                    value = pitch,
                    onValueChange = { pitch = (it * 20).roundToInt() / 20f },
                    onValueChangeFinished = { onSetPitch(pitch) },
                    valueRange = 0.5f..1.5f,
                )
            }
            SheetAction(Icons.Rounded.Radio, "Start radio from this song", { onStartRadio(); onDismiss() })
            SheetAction(Icons.Rounded.AllInclusive, "Similar music", { onShowRelated(); onDismiss() })
            SheetAction(Icons.AutoMirrored.Rounded.PlaylistAdd, "Save to playlist", onSaveToPlaylist)
            SheetAction(Icons.Rounded.Person, "More by ${track.artist}", { onMoreByArtist(); onDismiss() })
            SheetAction(Icons.Rounded.Share, "Share", { shareTrack(context, track); onDismiss() })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SleepTimerSheet(
    state: PlaybackState,
    onDismiss: () -> Unit,
    onSet: (Int?) -> Unit,
) {
    val active = state.sleepTimerEndsAtMs
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = Spacing.xl).padding(bottom = Spacing.xl)) {
            Text("Sleep timer", style = MaterialTheme.typography.headlineSmall)
            Text(
                when {
                    state.sleepAtEndOfTrack -> "Pausing at the end of this song"
                    active != null -> "Pausing in ${((active - System.currentTimeMillis()) / 60_000 + 1).coerceAtLeast(1)} min"
                    else -> "Music pauses on its own — drift off."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Spacing.lg))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                listOf(5, 15, 30, 45, 60, 90).forEach { minutes ->
                    FilterChip(selected = false, onClick = { onSet(minutes); onDismiss() }, label = { Text("$minutes min") })
                }
                FilterChip(selected = state.sleepAtEndOfTrack, onClick = { onSet(0); onDismiss() }, label = { Text("End of song") })
            }
            if (active != null || state.sleepAtEndOfTrack) {
                Spacer(Modifier.height(Spacing.md))
                TextButton(onClick = { onSet(null); onDismiss() }) { Text("Turn off timer") }
            }
        }
    }
}

/** Divider that marks where the queue continues with songs picked by autoplay. */
@Composable
private fun AutoplayHeader() {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = Spacing.xl, end = Spacing.xl, top = Spacing.lg, bottom = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.AllInclusive, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(Spacing.sm))
        Column {
            Text("AUTOPLAY", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            Text("Similar songs to keep the mood going", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** YouTube Music's "Related" page for the playing song: similar songs, artists and albums. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RelatedSheet(
    track: Track,
    page: com.moodify.app.data.CatalogPage?,
    isLoading: Boolean,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxHeight(0.9f).navigationBarsPadding()) {
            Text(
                "Similar to ${track.title}",
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = Spacing.xl, vertical = Spacing.sm),
            )
            val sections = page?.sections.orEmpty().filter { it.items.isNotEmpty() }
            when {
                isLoading && sections.isEmpty() -> com.moodify.app.ui.designsystem.SkeletonShelf(Modifier.padding(top = Spacing.lg))
                sections.isEmpty() -> Text(
                    "YouTube Music has nothing related for this one.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(Spacing.xl),
                )
                else -> LazyColumn(
                    contentPadding = PaddingValues(top = Spacing.md, bottom = Spacing.xxl),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xl),
                ) {
                    items(sections, key = { "${it.id}:${it.title}" }) { section ->
                        com.moodify.app.ui.designsystem.CatalogShelf(section)
                    }
                }
            }
        }
    }
}