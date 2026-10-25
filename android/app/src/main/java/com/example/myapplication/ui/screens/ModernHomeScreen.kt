package com.example.myapplication.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.myapplication.data.BrowseEndpoint
import com.example.myapplication.data.CatalogSection
import com.example.myapplication.data.Track
import com.example.myapplication.ui.components.CatalogShelves

@Composable
fun ModernHomeScreen(
    onIntent: (String) -> Unit,
    catalogSections: List<CatalogSection> = emptyList(),
    currentTrack: Track? = null,
    onTrackSelect: (Track) -> Unit = {},
    onBrowseClick: (BrowseEndpoint) -> Unit = {},
    isLoadingCatalog: Boolean = false
) {
    val quickPicks = remember(catalogSections) {
        catalogSections
            .flatMap { section -> section.items.mapNotNull { it.track } }
            .distinctBy { it.id }
            .take(6)
    }
    val musicSections = remember(catalogSections) {
        catalogSections
            .map { section ->
                section.copy(items = section.items.filter { item ->
                    item.type.lowercase() !in setOf("video", "podcast", "episode")
                })
            }
            .filter { it.items.isNotEmpty() }
            .take(5)
    }
    var intent by remember { mutableStateOf("") }
    val suggestions = listOf("Deep focus", "Calm reset", "New releases", "Surprise me")

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 160.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        item {
            HomeWelcome(
                intent = intent,
                onIntentChange = { intent = it },
                onSubmit = {
                    if (intent.isNotBlank()) {
                        onIntent(intent.trim())
                        intent = ""
                    }
                }
            )
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(top = 14.dp, bottom = 26.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                suggestions.forEach { suggestion ->
                    FilterChip(
                        selected = false,
                        onClick = { onIntent(suggestion) },
                        label = { Text(suggestion, maxLines = 1) },
                        leadingIcon = {
                            Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                    )
                }
            }
        }

        item {
            HomeSectionHeader(
                title = "Quick picks",
                supporting = if (quickPicks.isEmpty()) "Your first shelf is loading" else "A few places to start"
            )
        }

        if (quickPicks.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    quickPicks.forEach { track ->
                        QuickPickRow(
                            track = track,
                            isPlaying = currentTrack?.id == track.id,
                            onClick = { onTrackSelect(track) }
                        )
                    }
                }
            }
        } else {
            item {
                HomeEmptyState(
                    isLoading = isLoadingCatalog,
                    onTryIntent = { onIntent("music for this moment") }
                )
            }
        }

        item {
            Spacer(modifier = Modifier.height(34.dp))
            HomeSectionHeader(
                title = "Live catalog",
                supporting = "Fresh music shelves from your region",
                action = musicSections.firstOrNull()?.endpoint?.let { endpoint ->
                    { onBrowseClick(endpoint) }
                }
            )
            Spacer(modifier = Modifier.height(14.dp))
        }

        if (musicSections.isNotEmpty()) {
            item {
                CatalogShelves(
                    sections = musicSections,
                    currentTrack = currentTrack,
                    onTrackClick = onTrackSelect,
                    onBrowseClick = onBrowseClick
                )
            }
        } else {
            item {
                CatalogUnavailableState(onTryIntent = { onIntent("latest music") })
            }
        }
    }
}

@Composable
private fun HomeWelcome(
    intent: String,
    onIntentChange: (String) -> Unit,
    onSubmit: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
    ) {
        Text(
            text = homeGreeting(),
            color = colors.primary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.1.sp
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = "What should we listen to?",
            color = colors.onBackground,
            fontSize = 31.sp,
            lineHeight = 36.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-1.1).sp
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Describe the moment and Moodify will find the next track.",
            color = colors.onSurfaceVariant,
            fontSize = 14.sp,
            lineHeight = 20.sp
        )
        Spacer(modifier = Modifier.height(18.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = intent,
                onValueChange = onIntentChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text("Try: music for a rainy commute") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = colors.primary,
                    unfocusedBorderColor = colors.outline,
                    focusedContainerColor = colors.surfaceVariant.copy(alpha = 0.45f),
                    unfocusedContainerColor = colors.surfaceVariant.copy(alpha = 0.28f),
                    focusedTextColor = colors.onSurface,
                    unfocusedTextColor = colors.onSurface,
                    cursorColor = colors.primary
                ),
                shape = RoundedCornerShape(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(
                onClick = onSubmit,
                enabled = intent.isNotBlank(),
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(if (intent.isNotBlank()) colors.primary else colors.surfaceVariant)
                    .semantics { role = Role.Button }
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = "Start listening",
                    tint = if (intent.isNotBlank()) colors.onPrimary else colors.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun HomeSectionHeader(
    title: String,
    supporting: String,
    action: (() -> Unit)? = null
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = colors.onSurface,
                fontFamily = FontFamily.Serif,
                fontSize = 25.sp,
                lineHeight = 29.sp
            )
            Text(
                text = supporting,
                color = colors.onSurfaceVariant,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (action != null) {
            Button(
                onClick = action,
                contentPadding = PaddingValues(horizontal = 12.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = colors.primary),
                modifier = Modifier.height(44.dp)
            ) {
                Text("View all", fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun QuickPickRow(
    track: Track,
    isPlaying: Boolean,
    onClick: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val rowColor by animateColorAsState(
        targetValue = if (isPlaying) colors.primary.copy(alpha = 0.12f) else colors.surfaceVariant.copy(alpha = 0.3f),
        label = "quick pick background"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(rowColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp)
            .semantics { role = Role.Button },
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = track.albumArtUrl,
            contentDescription = "Artwork for ${track.title}",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(12.dp))
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = track.title,
                color = if (isPlaying) colors.primary else colors.onSurface,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = listOf(track.artist, track.album).filter { it.isNotBlank() }.joinToString(" · "),
                color = colors.onSurfaceVariant,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Icon(
            imageVector = Icons.Default.PlayArrow,
            contentDescription = if (isPlaying) "Playing" else "Play ${track.title}",
            tint = if (isPlaying) colors.primary else colors.onSurfaceVariant,
            modifier = Modifier.size(22.dp)
        )
    }
}

@Composable
private fun HomeEmptyState(isLoading: Boolean, onTryIntent: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = colors.surfaceVariant.copy(alpha = 0.38f),
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = colors.primary, modifier = Modifier.size(22.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isLoading) "Tuning your shelves" else "Your first shelf is waiting",
                    color = colors.onSurface,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = if (isLoading) "Moodify is fetching music for this moment." else "Describe a moment above to start a listening thread.",
                    color = colors.onSurfaceVariant,
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
            }
            if (!isLoading) {
                IconButton(onClick = onTryIntent) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Start a listening thread", tint = colors.primary)
                }
            }
        }
    }
}

@Composable
private fun CatalogUnavailableState(onTryIntent: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, colors.outline.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("The catalog is taking a moment.", color = colors.onSurface, fontWeight = FontWeight.SemiBold)
        Text("Try a search above while the live shelves reconnect.", color = colors.onSurfaceVariant, fontSize = 12.sp)
        Button(onClick = onTryIntent, contentPadding = PaddingValues(horizontal = 14.dp)) {
            Text("Find music")
        }
    }
}

private fun homeGreeting(): String = when (java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)) {
    in 0..4 -> "STILL AWAKE"
    in 5..11 -> "GOOD MORNING"
    in 12..16 -> "GOOD AFTERNOON"
    else -> "GOOD EVENING"
}
