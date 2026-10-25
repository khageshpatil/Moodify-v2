package com.example.myapplication.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapplication.data.Track
import com.example.myapplication.data.LocalPlaylist
import com.example.myapplication.ui.components.TrackRowItem
import com.example.myapplication.ui.theme.PrimaryPurple
import com.example.myapplication.ui.theme.SignalBorder
import com.example.myapplication.ui.theme.SurfaceCard
import com.example.myapplication.ui.theme.TextMuted
import com.example.myapplication.ui.theme.TextPrimary
import com.example.myapplication.ui.theme.TextSecondary

@Composable
fun ModernLibraryScreen(
    favoriteTracks: List<Track>,
    historyTracks: List<Track> = emptyList(),
    playlists: List<LocalPlaylist> = emptyList(),
    queueTracks: List<Track> = emptyList(),
    currentTrack: Track?,
    onTrackSelect: (Track) -> Unit,
    onFavoriteToggle: (Track) -> Unit,
    onClearHistory: () -> Unit = {},
    onCreatePlaylist: (String) -> LocalPlaylist? = { null },
    onDeletePlaylist: (String) -> Unit = {},
    onPlaylistTrackSelect: (LocalPlaylist, Track) -> Unit = { _, _ -> },
) {
    val colors = MaterialTheme.colorScheme
    var showCreatePlaylist by remember { mutableStateOf(false) }
    var playlistName by remember { mutableStateOf("") }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 17.dp, end = 17.dp, bottom = 150.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(28.dp))
            Text(text = "YOUR LIBRARY", color = colors.primary, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
            Spacer(modifier = Modifier.height(14.dp))
            Text(text = "A place for the things", color = colors.onSurface, fontFamily = FontFamily.Serif, fontSize = 35.sp, lineHeight = 38.sp)
            Text(text = "worth keeping.", color = colors.primary, fontFamily = FontFamily.Serif, fontSize = 35.sp, lineHeight = 38.sp)
            Spacer(modifier = Modifier.height(10.dp))
            Text(text = "Favorites, playlists, and the trail of what you have been listening to.", color = colors.onSurfaceVariant, fontSize = 14.sp, lineHeight = 21.sp)
            Spacer(modifier = Modifier.height(28.dp))
        }

        item {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Shortcut("Favorites", favoriteTracks.size, Icons.Default.Favorite)
                Shortcut("Playlists", playlists.size, Icons.Default.MusicNote)
                Shortcut("History", historyTracks.size, Icons.Default.AccessTime)
                Shortcut("Queue", queueTracks.size, Icons.Default.List)
            }
            Spacer(modifier = Modifier.height(54.dp))
        }

        item {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Your playlists", color = colors.onSurface, fontFamily = FontFamily.Serif, fontSize = 26.sp)
                    Text("Keep the songs that belong together.", color = colors.onSurfaceVariant, fontSize = 12.sp)
                }
                TextButton(onClick = { showCreatePlaylist = true }) { Text("New", color = colors.primary) }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        if (playlists.isEmpty()) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, colors.outline.copy(alpha = 0.45f), RoundedCornerShape(2.dp))
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("No playlists yet", color = colors.onSurface, fontWeight = FontWeight.SemiBold)
                    Text("Create one here or save a song from the player.", color = colors.onSurfaceVariant, fontSize = 13.sp)
                    Button(onClick = { showCreatePlaylist = true }) { Text("Create playlist") }
                }
            }
        } else {
            items(playlists, key = { it.id }) { playlist ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(playlist.name, color = colors.onSurface, fontWeight = FontWeight.SemiBold)
                        Text("${playlist.tracks.size} songs", color = colors.onSurfaceVariant, fontSize = 12.sp)
                    }
                    if (playlist.tracks.isNotEmpty()) {
                        TextButton(onClick = { onPlaylistTrackSelect(playlist, playlist.tracks.first()) }) {
                            Text("Play", color = colors.primary)
                        }
                    }
                    TextButton(onClick = { onDeletePlaylist(playlist.id) }) {
                        Text("Delete", color = colors.onSurfaceVariant)
                    }
                }
            }
        }

        item {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Text(text = "01", color = colors.primary, fontSize = 11.sp)
                Spacer(modifier = Modifier.width(10.dp))
                Text(text = "Saved for later", color = colors.onSurface, fontFamily = FontFamily.Serif, fontSize = 26.sp)
            }
            Spacer(modifier = Modifier.height(18.dp))
        }

        if (favoriteTracks.isEmpty()) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, colors.outline.copy(alpha = 0.45f), RoundedCornerShape(2.dp))
                        .padding(22.dp),
                    horizontalAlignment = Alignment.Start
                ) {
                    Icon(Icons.Default.Favorite, contentDescription = null, tint = colors.primary, modifier = Modifier.size(22.dp))
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Your listening story starts here.", color = colors.onSurface, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text("Save songs you want to find again.", color = colors.onSurfaceVariant, fontSize = 13.sp)
                }
            }
        } else {
            items(favoriteTracks) { track ->
                TrackRowItem(
                    track = track,
                    isPlayingCurrent = currentTrack?.id == track.id,
                    onTrackClick = { onTrackSelect(track) },
                    onFavoriteToggle = onFavoriteToggle
                )
                Spacer(modifier = Modifier.height(10.dp))
            }
        }

        if (historyTracks.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = "Recently played", color = colors.onSurface, fontFamily = FontFamily.Serif, fontSize = 26.sp)
                    TextButton(onClick = onClearHistory) { Text("Clear", color = colors.primary, fontSize = 12.sp) }
                }
                Spacer(modifier = Modifier.height(10.dp))
            }
            items(historyTracks) { track ->
                TrackRowItem(
                    track = track,
                    isPlayingCurrent = currentTrack?.id == track.id,
                    onTrackClick = { onTrackSelect(track) },
                    onFavoriteToggle = onFavoriteToggle
                )
                Spacer(modifier = Modifier.height(10.dp))
            }
        }

        if (queueTracks.isNotEmpty()) {
            item {
                Text(text = "Up next", color = colors.onSurface, fontFamily = FontFamily.Serif, fontSize = 26.sp)
                Spacer(modifier = Modifier.height(10.dp))
            }
            items(queueTracks) { track ->
                TrackRowItem(
                    track = track,
                    isPlayingCurrent = currentTrack?.id == track.id,
                    onTrackClick = { onTrackSelect(track) },
                    onFavoriteToggle = onFavoriteToggle
                )
                Spacer(modifier = Modifier.height(10.dp))
            }
        }
    }

    if (showCreatePlaylist) {
        AlertDialog(
            onDismissRequest = { showCreatePlaylist = false },
            title = { Text("Create playlist") },
            text = {
                OutlinedTextField(
                    value = playlistName,
                    onValueChange = { playlistName = it },
                    label = { Text("Name") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onCreatePlaylist(playlistName)
                        playlistName = ""
                        showCreatePlaylist = false
                    },
                    enabled = playlistName.isNotBlank(),
                ) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { showCreatePlaylist = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Shortcut(label: String, count: Int, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .height(42.dp)
            .border(1.dp, colors.outline.copy(alpha = 0.45f), RoundedCornerShape(22.dp))
            .padding(horizontal = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Icon(icon, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(17.dp))
        Text(label, color = colors.onSurfaceVariant, fontSize = 12.sp)
        Text(count.toString(), color = colors.onSurfaceVariant.copy(alpha = 0.7f), fontSize = 11.sp)
    }
}
