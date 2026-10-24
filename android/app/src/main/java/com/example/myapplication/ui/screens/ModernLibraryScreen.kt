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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapplication.data.Track
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
    currentTrack: Track?,
    onTrackSelect: (Track) -> Unit,
    onFavoriteToggle: (Track) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 17.dp, end = 17.dp, bottom = 150.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(28.dp))
            Text(text = "YOUR LIBRARY", color = PrimaryPurple, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
            Spacer(modifier = Modifier.height(14.dp))
            Text(text = "A place for the things", color = TextPrimary, fontFamily = FontFamily.Serif, fontSize = 35.sp, lineHeight = 38.sp)
            Text(text = "worth keeping.", color = PrimaryPurple, fontFamily = FontFamily.Serif, fontSize = 35.sp, lineHeight = 38.sp)
            Spacer(modifier = Modifier.height(10.dp))
            Text(text = "Favorites, playlists, and the trail of what you have been listening to.", color = TextSecondary, fontSize = 14.sp, lineHeight = 21.sp)
            Spacer(modifier = Modifier.height(28.dp))
        }

        item {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Shortcut("Favorites", favoriteTracks.size, Icons.Default.Favorite)
                Shortcut("Playlists", 0, Icons.Default.MusicNote)
                Shortcut("History", 0, Icons.Default.AccessTime)
                Shortcut("Queue", 0, Icons.Default.List)
            }
            Spacer(modifier = Modifier.height(54.dp))
        }

        item {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Text(text = "01", color = PrimaryPurple, fontSize = 11.sp)
                Spacer(modifier = Modifier.width(10.dp))
                Text(text = "Saved for later", color = TextPrimary, fontFamily = FontFamily.Serif, fontSize = 26.sp)
            }
            Spacer(modifier = Modifier.height(18.dp))
        }

        if (favoriteTracks.isEmpty()) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, SignalBorder, RoundedCornerShape(2.dp))
                        .padding(22.dp),
                    horizontalAlignment = Alignment.Start
                ) {
                    Icon(Icons.Default.Favorite, contentDescription = null, tint = PrimaryPurple, modifier = Modifier.size(22.dp))
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Your listening story starts here.", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text("Save songs you want to find again.", color = TextSecondary, fontSize = 13.sp)
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
    }
}

@Composable
private fun Shortcut(label: String, count: Int, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(
        modifier = Modifier
            .height(42.dp)
            .border(1.dp, SignalBorder, RoundedCornerShape(22.dp))
            .padding(horizontal = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Icon(icon, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(17.dp))
        Text(label, color = TextSecondary, fontSize = 12.sp)
        Text(count.toString(), color = TextMuted, fontSize = 11.sp)
    }
}
