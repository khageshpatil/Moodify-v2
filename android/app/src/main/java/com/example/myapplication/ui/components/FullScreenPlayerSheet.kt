package com.example.myapplication.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
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
    onSetSpeed: (Float) -> Unit,
    onSetPitch: (Float) -> Unit,
    onDownload: () -> Unit,
    lyrics: String?,
    isLoadingLyrics: Boolean,
    onLoadLyrics: () -> Unit
) {
    val track = state.currentTrack ?: return
    var showQueue by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showLyrics by remember { mutableStateOf(false) }

    val imageScale by animateFloatAsState(
        targetValue = if (state.isPlaying) 1f else 0.92f,
        label = "imageScale"
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = DarkBackground,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 12.dp)
                    .width(40.dp)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(Color.Gray.copy(alpha = 0.5f))
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
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                }
                Text(
                    text = "NOW PLAYING",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                    color = PrimaryPurple
                )
                IconButton(onClick = { showQueue = !showQueue }) {
                    Icon(
                        imageVector = Icons.Default.QueueMusic,
                        contentDescription = "Queue",
                        tint = Color.White
                    )
                }
                Box {
                    IconButton(onClick = { showSettings = !showSettings }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Player settings", tint = Color.White)
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
                            text = { Text("Lyrics") },
                            onClick = { showLyrics = true; onLoadLyrics(); showSettings = false }
                        )
                    }
                }
            }

            if (showLyrics) {
                LyricsPanel(
                    lyrics = lyrics,
                    isLoading = isLoadingLyrics,
                    onClose = { showLyrics = false }
                )
            }

            if (showQueue) {
                QueuePreview(
                    state = state,
                    onQueueItemClick = onQueueItemClick,
                    onRemoveFromQueue = onRemoveFromQueue
                )
            }

            // Album artwork — animates smaller when paused
            Box(
                modifier = Modifier
                    .size(280.dp)
                    .scale(imageScale)
                    .clip(RoundedCornerShape(24.dp))
                    .background(SurfaceCard),
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
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = track.artist,
                    fontSize = 15.sp,
                    color = Color.Gray,
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
                    color = NeonPink,
                    trackColor = Color.Transparent
                )
            }

            // Error message
            if (state.errorMessage != null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = state.errorMessage,
                        color = NeonPink,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                    TextButton(onClick = onRetry) { Text("Retry", color = PrimaryPurple) }
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
                        thumbColor = NeonPink,
                        activeTrackColor = PrimaryPurple,
                        inactiveTrackColor = Color.Gray.copy(alpha = 0.3f)
                    )
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = formatTime(state.positionMs),
                        fontSize = 12.sp,
                        color = Color.Gray
                    )
                    Text(
                        text = formatTime(state.durationMs),
                        fontSize = 12.sp,
                        color = Color.Gray
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
                        tint = if (state.isShuffle) PrimaryPurple else Color.Gray,
                        modifier = Modifier.size(24.dp)
                    )
                }

                IconButton(onClick = onPrevious) {
                    Icon(
                        imageVector = Icons.Default.SkipPrevious,
                        contentDescription = "Previous",
                        tint = Color.White,
                        modifier = Modifier.size(36.dp)
                    )
                }

                Box(
                    modifier = Modifier
                        .size(68.dp)
                        .clip(CircleShape)
                        .background(Brush.linearGradient(listOf(PrimaryPurple, NeonPink))),
                    contentAlignment = Alignment.Center
                ) {
                    IconButton(onClick = onTogglePlayPause) {
                        Icon(
                            imageVector = if (state.isPlaying) Icons.Default.Pause
                            else Icons.Default.PlayArrow,
                            contentDescription = "Play/Pause",
                            tint = Color.White,
                            modifier = Modifier.size(38.dp)
                        )
                    }
                }

                IconButton(onClick = onNext) {
                    Icon(
                        imageVector = Icons.Default.SkipNext,
                        contentDescription = "Next",
                        tint = Color.White,
                        modifier = Modifier.size(36.dp)
                    )
                }

                IconButton(onClick = onToggleRepeat) {
                    Icon(
                        imageVector = Icons.Default.Repeat,
                        contentDescription = "Repeat",
                        tint = if (state.isRepeat) PrimaryPurple else Color.Gray,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun QueuePreview(
    state: PlaybackState,
    onQueueItemClick: (com.example.myapplication.data.Track) -> Unit,
    onRemoveFromQueue: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 190.dp)
    ) {
        Text("UP NEXT", color = PrimaryPurple, fontSize = 11.sp, fontWeight = FontWeight.Bold)
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
                        color = if (index == state.currentIndex) PrimaryPurple else Color.Gray,
                        modifier = Modifier.width(30.dp)
                    )
                    Text(
                        text = queuedTrack.title,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { onRemoveFromQueue(index) }) {
                        Icon(Icons.Default.Close, contentDescription = "Remove", tint = Color.Gray)
                    }
                }
            }
        }
    }
}

@Composable
private fun LyricsPanel(
    lyrics: String?,
    isLoading: Boolean,
    onClose: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 240.dp)
            .background(SurfaceCard, RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("LYRICS", color = PrimaryPurple, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            TextButton(onClick = onClose) { Text("Close", color = Color.Gray) }
        }
        when {
            isLoading -> CircularProgressIndicator(color = PrimaryPurple, modifier = Modifier.size(24.dp))
            lyrics.isNullOrBlank() -> Text("Lyrics are not available for this track.", color = Color.Gray, fontSize = 13.sp)
            else -> androidx.compose.foundation.lazy.LazyColumn {
                item {
                    Text(lyrics, color = Color.White, fontSize = 14.sp, lineHeight = 22.sp)
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
