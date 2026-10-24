package com.example.myapplication.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.myapplication.data.BrowseEndpoint
import com.example.myapplication.data.CatalogItem
import com.example.myapplication.data.CatalogSection
import com.example.myapplication.data.Track
import com.example.myapplication.ui.theme.PrimaryPurple
import com.example.myapplication.ui.theme.TextPrimary
import com.example.myapplication.ui.theme.TextSecondary

@Composable
fun CatalogShelves(
    sections: List<CatalogSection>,
    currentTrack: Track? = null,
    onTrackClick: (Track) -> Unit,
    onBrowseClick: (BrowseEndpoint) -> Unit = {}
) {
    Column(verticalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.fillMaxWidth()) {
        sections.filter { it.items.isNotEmpty() }.forEach { section ->
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text(
                    text = section.title,
                    color = TextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(end = 12.dp)
                ) {
                    items(section.items, key = { "${it.type}:${it.id}" }) { item ->
                        CatalogCard(
                            item = item,
                            currentTrack = currentTrack,
                            onTrackClick = onTrackClick,
                            onBrowseClick = onBrowseClick
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CatalogCard(
    item: CatalogItem,
    currentTrack: Track?,
    onTrackClick: (Track) -> Unit,
    onBrowseClick: (BrowseEndpoint) -> Unit
) {
    val track = item.track
    val clickModifier = Modifier.clickable {
        if (track != null) onTrackClick(track)
        else item.endpoint?.let(onBrowseClick)
    }
    Column(
        modifier = clickModifier.width(142.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        AsyncImage(
            model = track?.albumArtUrl?.takeIf { it.isNotBlank() } ?: item.artworkUrl,
            contentDescription = item.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .width(142.dp)
                .height(142.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
        )
        Text(
            text = track?.title ?: item.title,
            color = if (track != null && currentTrack?.id == track.id) PrimaryPurple else TextPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = track?.artist ?: item.type.replaceFirstChar { it.uppercase() },
            color = TextSecondary,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
