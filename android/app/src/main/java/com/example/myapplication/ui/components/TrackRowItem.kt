package com.example.myapplication.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.myapplication.data.Track
import com.example.myapplication.ui.theme.NeonPink
import com.example.myapplication.ui.theme.PrimaryPurple
import com.example.myapplication.ui.theme.SurfaceCard

@Composable
fun TrackRowItem(
    track: Track,
    isPlayingCurrent: Boolean = false,
    onTrackClick: (Track) -> Unit,
    onFavoriteToggle: (Track) -> Unit = {}
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(2.dp))
            .background(if (isPlayingCurrent) colors.primary.copy(alpha = 0.08f) else Color.Transparent)
            .clickable { onTrackClick(track) }
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .clip(RoundedCornerShape(2.dp))
            ) {
                AsyncImage(
                    model = track.albumArtUrl,
                    contentDescription = track.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )

                if (isPlayingCurrent) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.4f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Playing",
                            tint = colors.primary,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
            }

            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = track.title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isPlayingCurrent) colors.primary else colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = track.artist,
                    fontSize = 13.sp,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        IconButton(onClick = { onFavoriteToggle(track) }) {
            Icon(
                imageVector = if (track.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                contentDescription = "Favorite",
                tint = if (track.isFavorite) colors.primary else colors.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
