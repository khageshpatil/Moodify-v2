package com.moodify.app.ui.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.moodify.app.data.ListeningStats
import com.moodify.app.ui.designsystem.AuraGlow
import com.moodify.app.ui.designsystem.EmptyState
import com.moodify.app.ui.designsystem.SectionHeader
import com.moodify.app.ui.designsystem.TrackRow
import com.moodify.app.ui.theme.Radii
import com.moodify.app.ui.theme.Spacing

/** "Your listening": top songs and artists from your play counts, shareable as text. */
@Composable
fun StatsScreen(stats: ListeningStats, bottomPadding: Dp, onBack: () -> Unit) {
    val context = LocalContext.current
    val songs = stats.topSongs.map { it.first }
    Box(Modifier.fillMaxSize()) {
        AuraGlow(intensity = 0.9f, height = 360.dp)
        LazyColumn(
            contentPadding = PaddingValues(bottom = bottomPadding + Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            item(key = "header") {
                Row(
                    Modifier
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .padding(horizontal = Spacing.xs, vertical = Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                    Text(
                        "Your listening",
                        style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.weight(1f).semantics { heading() },
                    )
                    if (!stats.isEmpty) {
                        IconButton(onClick = {
                            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, stats.shareText())
                            context.startActivity(Intent.createChooser(send, "Share your music"))
                        }) { Icon(Icons.Rounded.Share, contentDescription = "Share") }
                    }
                }
            }
            if (stats.isEmpty) {
                item(key = "empty") {
                    EmptyState(
                        icon = Icons.Rounded.Insights,
                        title = "Nothing yet",
                        message = "Play some music and your top songs and artists will show up here.",
                    )
                }
                return@LazyColumn
            }
            item(key = "totals") {
                Row(
                    Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.md),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    StatTile(
                        value = if (stats.totalMinutes >= 120) "${stats.totalMinutes / 60} h" else "${stats.totalMinutes} min",
                        label = "listened",
                        modifier = Modifier.weight(1f),
                    )
                    StatTile(value = stats.totalPlays.toString(), label = "plays", modifier = Modifier.weight(1f))
                }
            }
            if (stats.topArtists.isNotEmpty()) {
                item(key = "artistsHeader") { SectionHeader(title = "Top artists") }
                itemsIndexed(stats.topArtists, key = { _, it -> "artist:${it.first}" }) { index, (name, plays) ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter, vertical = Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${index + 1}",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.width(36.dp),
                        )
                        Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text(if (plays == 1) "1 play" else "$plays plays", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                item(key = "gap") { Spacer(Modifier.height(Spacing.lg)) }
            }
            item(key = "songsHeader") { SectionHeader(title = "Top songs") }
            itemsIndexed(stats.topSongs, key = { _, it -> "song:${it.first.id}" }) { index, (track, plays) ->
                TrackRow(
                    track = track,
                    queueContext = songs,
                    leadingNumber = index + 1,
                    subtitle = "${track.artist} · ${if (plays == 1) "1 play" else "$plays plays"}",
                    modifier = Modifier.padding(horizontal = Spacing.gutter - Spacing.sm),
                )
            }
        }
    }
}

@Composable
private fun StatTile(value: String, label: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(Radii.lg))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(Spacing.lg),
    ) {
        Text(value, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
