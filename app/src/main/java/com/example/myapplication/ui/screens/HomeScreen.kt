package com.example.myapplication.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapplication.data.DEFAULT_MOODS
import com.example.myapplication.data.DEFAULT_VIBE_WORLDS
import com.example.myapplication.data.Mood
import com.example.myapplication.data.Track
import com.example.myapplication.data.VibeWorld
import com.example.myapplication.ui.components.MoodTileCard
import com.example.myapplication.ui.components.TrackRowItem
import com.example.myapplication.ui.components.VibeWorldCard
import com.example.myapplication.ui.theme.NeonPink
import com.example.myapplication.ui.theme.PrimaryPurple
import com.example.myapplication.ui.theme.SurfaceCard

@Composable
fun HomeScreen(
    trendingTracks: List<Track>,
    currentTrack: Track?,
    isLoading: Boolean,
    onTrackSelect: (Track) -> Unit,
    onMoodSelect: (Mood) -> Unit,
    onVibeSelect: (VibeWorld) -> Unit,
    onFavoriteToggle: (Track) -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        contentPadding = PaddingValues(bottom = 120.dp)
    ) {
        // Hero Banner
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(130.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(PrimaryPurple, NeonPink)
                        )
                    )
                    .padding(20.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Column {
                    Text(
                        text = "What's your mood today?",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Explore AI Curated Vibe Playlists & Songs",
                        fontSize = 13.sp,
                        color = Color.White.copy(alpha = 0.85f)
                    )
                }
            }
        }

        // Quick Mood Tiles (2 Columns Grid)
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Pick A Mood Vibe",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )

                val chunkedMoods = DEFAULT_MOODS.chunked(2)
                chunkedMoods.forEach { pair ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        pair.forEach { mood ->
                            Box(modifier = Modifier.weight(1f)) {
                                MoodTileCard(mood = mood, onClick = onMoodSelect)
                            }
                        }
                        if (pair.size == 1) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        // Vibe Worlds Carousel
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Vibe Worlds",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(DEFAULT_VIBE_WORLDS) { vibe ->
                        VibeWorldCard(vibe = vibe, onClick = onVibeSelect)
                    }
                }
            }
        }

        // Trending Tracks Section
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "🔥 Trending Now",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )

                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = PrimaryPurple,
                        strokeWidth = 2.dp
                    )
                }
            }
        }

        items(trendingTracks) { track ->
            TrackRowItem(
                track = track,
                isPlayingCurrent = currentTrack?.id == track.id,
                onTrackClick = { onTrackSelect(track) },
                onFavoriteToggle = onFavoriteToggle
            )
        }
    }
}
