package com.example.myapplication.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapplication.data.Track
import com.example.myapplication.data.CatalogSection
import com.example.myapplication.data.BrowseEndpoint
import com.example.myapplication.ui.components.CatalogShelves
import com.example.myapplication.ui.components.TrackRowItem
import com.example.myapplication.ui.theme.PrimaryPurple
import com.example.myapplication.ui.theme.SignalBorder
import com.example.myapplication.ui.theme.SurfaceCard
import com.example.myapplication.ui.theme.TextPrimary
import com.example.myapplication.ui.theme.TextSecondary
import com.example.myapplication.ui.theme.TextMuted

@Composable
fun DiscoverScreen(
    searchQuery: String,
    searchResults: List<Track>,
    currentTrack: Track?,
    isSearching: Boolean,
    errorMessage: String?,
    catalogSections: List<CatalogSection> = emptyList(),
    exploreSections: List<CatalogSection> = emptyList(),
    chartsSections: List<CatalogSection> = emptyList(),
    homeSections: List<CatalogSection> = emptyList(),
    onBrowseClick: (BrowseEndpoint) -> Unit = {},
    searchType: String? = null,
    onSearchTypeChange: (String?) -> Unit = {},
    canLoadMore: Boolean = false,
    isLoadingMore: Boolean = false,
    onLoadMore: () -> Unit = {},
    onQueryChange: (String) -> Unit,
    onTrackSelect: (Track) -> Unit,
    onFavoriteToggle: (Track) -> Unit
) {
    var activeSurface by remember { mutableStateOf("explore") }
    var showOnlyMusic by remember { mutableStateOf(true) }
    val colors = MaterialTheme.colorScheme

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 17.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        Spacer(modifier = Modifier.height(24.dp))
        Text(text = "FIND A SONG, A FEELING, A MEMORY.", color = colors.primary, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
        Spacer(modifier = Modifier.height(14.dp))
        Text(text = "Search the soundtrack.", color = colors.onSurface, fontFamily = FontFamily.Serif, fontSize = 34.sp, lineHeight = 36.sp)
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = "Look for a song, an artist, or the feeling you want to stay with.", color = colors.onSurfaceVariant, fontSize = 14.sp, lineHeight = 21.sp)
        Spacer(modifier = Modifier.height(28.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
            .border(1.dp, colors.outline.copy(alpha = 0.45f), RoundedCornerShape(0.dp)),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Search, contentDescription = "Search", tint = colors.primary, modifier = Modifier.padding(start = 2.dp).size(21.dp))
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onQueryChange,
                placeholder = { Text("Search songs, artists, albums...", color = colors.onSurfaceVariant.copy(alpha = 0.72f)) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) { Icon(Icons.Default.Clear, contentDescription = "Clear", tint = colors.onSurfaceVariant) }
                    }
                },
                modifier = Modifier.weight(1f),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent,
                    focusedTextColor = colors.onSurface,
                    unfocusedTextColor = colors.onSurface,
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent
                ),
                singleLine = true
            )
        }
        Spacer(modifier = Modifier.height(24.dp))

        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(null to "All", "song" to "Songs", "video" to "Videos", "album" to "Albums", "artist" to "Artists", "playlist" to "Playlists").forEach { (type, label) ->
                FilterChip(
                    selected = searchType == type,
                    onClick = { onSearchTypeChange(type) },
                    label = { Text(label) }
                )
            }
        }
        if (searchQuery.isBlank()) {
            Spacer(modifier = Modifier.height(10.dp))
            FilterChip(
                selected = showOnlyMusic,
                onClick = { showOnlyMusic = !showOnlyMusic },
                label = { Text(if (showOnlyMusic) "Music only" else "Include video and podcasts") }
            )
        }
        Spacer(modifier = Modifier.height(20.dp))

        if (searchQuery.isBlank()) {
            Text(
                text = "CATALOG",
                color = colors.onSurfaceVariant,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.3.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("explore" to "Explore", "charts" to "Charts", "for-you" to "For you").forEach { (surface, label) ->
                    FilterChip(
                        selected = activeSurface == surface,
                        onClick = { activeSurface = surface },
                        label = { Text(label) }
                    )
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
        }

        // Loading or Results Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (searchQuery.isBlank()) "Recommended Discoveries" else "Search Results",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = colors.onSurface
            )

            if (isSearching) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = colors.primary,
                    strokeWidth = 2.dp
                )
            }
        }

        if (errorMessage != null) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = errorMessage,
                color = colors.primary,
                fontSize = 13.sp,
                lineHeight = 18.sp
            )
        }

        // Search Results List
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(bottom = 150.dp)
        ) {
            // A typed search page owns the shelves shown below the query.
            // Previously the default Explore shelf won whenever it was
            // loaded, which made album/artist/playlist search results look
            // missing even though the backend had returned them.
            val visibleCatalogSections = if (searchQuery.isNotBlank()) {
                catalogSections
            } else {
                when (activeSurface) {
                    "charts" -> chartsSections
                    "for-you" -> homeSections
                    else -> if (exploreSections.isNotEmpty()) exploreSections else catalogSections
                }
            }
            val filteredCatalogSections = visibleCatalogSections
                .map { section ->
                    section.copy(items = if (showOnlyMusic) section.items.filter { it.type.lowercase() !in setOf("video", "podcast", "episode") } else section.items)
                }
                .filter { it.items.isNotEmpty() }

            if (filteredCatalogSections.isNotEmpty()) {
                item {
                    CatalogShelves(
                        sections = filteredCatalogSections.take(if (searchQuery.isBlank()) 6 else 10),
                        currentTrack = currentTrack,
                        onTrackClick = onTrackSelect,
                        onBrowseClick = onBrowseClick
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
            items(searchResults) { track ->
                TrackRowItem(
                    track = track,
                    isPlayingCurrent = currentTrack?.id == track.id,
                    onTrackClick = { onTrackSelect(track) },
                    onFavoriteToggle = onFavoriteToggle
                )
            }
            if (searchQuery.isNotBlank() && canLoadMore) {
                item {
                    OutlinedButton(
                        onClick = onLoadMore,
                        enabled = !isLoadingMore,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (isLoadingMore) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Text("Load more results")
                        }
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }
}
