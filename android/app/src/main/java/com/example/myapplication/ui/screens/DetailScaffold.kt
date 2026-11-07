package com.moodify.app.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.moodify.app.ui.designsystem.AuraGlow
import com.moodify.app.ui.designsystem.LocalMoodifyIsDark
import com.moodify.app.ui.theme.LocalArtworkColors
import com.moodify.app.ui.theme.Spacing
import com.moodify.app.ui.theme.rememberArtworkColors

/**
 * Shared layout for every "collection" page: a hero tinted by [heroArtworkUrl],
 * Play / Shuffle, then [content]. A compact top bar fades in the title once the
 * hero scrolls away.
 */
@Composable
fun DetailScaffold(
    title: String,
    subtitle: String,
    eyebrow: String,
    heroArtworkUrl: String?,
    bottomPadding: Dp,
    onBack: () -> Unit,
    hero: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    onPlay: (() -> Unit)? = null,
    onShuffle: (() -> Unit)? = null,
    listState: LazyListState = rememberLazyListState(),
    topActions: @Composable RowScope.() -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    val heroColors = rememberArtworkColors(heroArtworkUrl, LocalMoodifyIsDark.current)
    val collapsed by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 600 } }
    val barAlpha by animateFloatAsState(if (collapsed) 1f else 0f, label = "barAlpha")

    // Tint this page's glow (and accents read from LocalArtworkColors) by its own artwork.
    CompositionLocalProvider(LocalArtworkColors provides heroColors) {
        Box(modifier.fillMaxSize()) {
            AuraGlow(height = 520.dp, intensity = 1.2f)
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(bottom = bottomPadding + Spacing.xl),
            ) {
                item(key = "hero") {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .windowInsetsPadding(WindowInsets.statusBars)
                            .padding(top = 56.dp, start = Spacing.gutter, end = Spacing.gutter),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        hero()
                        Spacer(Modifier.height(Spacing.xl))
                        Text(
                            eyebrow.uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = heroColors.accent,
                        )
                        Spacer(Modifier.height(Spacing.xs))
                        Text(
                            title,
                            style = MaterialTheme.typography.headlineLarge,
                            textAlign = TextAlign.Center,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.semantics { heading() },
                        )
                        if (subtitle.isNotBlank()) {
                            Spacer(Modifier.height(Spacing.xs))
                            Text(
                                subtitle,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }
                        if (onPlay != null || onShuffle != null) {
                            Spacer(Modifier.height(Spacing.xl))
                            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                                if (onPlay != null) {
                                    Button(
                                        onClick = onPlay,
                                        // The main action looks the same on every page; artwork
                                        // only tints the glow and the eyebrow.
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.primary,
                                            contentColor = MaterialTheme.colorScheme.onPrimary,
                                        ),
                                        contentPadding = PaddingValues(horizontal = Spacing.xl, vertical = 14.dp),
                                    ) {
                                        Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                                        Spacer(Modifier.width(Spacing.sm))
                                        Text("Play", style = MaterialTheme.typography.titleSmall)
                                    }
                                }
                                if (onShuffle != null) {
                                    FilledTonalButton(
                                        onClick = onShuffle,
                                        contentPadding = PaddingValues(horizontal = Spacing.xl, vertical = 14.dp),
                                    ) {
                                        Icon(Icons.Rounded.Shuffle, contentDescription = null)
                                        Spacer(Modifier.width(Spacing.sm))
                                        Text("Shuffle", style = MaterialTheme.typography.titleSmall)
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(Spacing.xl))
                    }
                }
                content()
            }

            // Compact top bar: always shows Back; title + background fade in when collapsed.
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background.copy(alpha = barAlpha))
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .height(56.dp)
                    .padding(horizontal = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f * (1f - barAlpha))),
                ) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                }
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = Spacing.sm)
                        .graphicsLayer { alpha = barAlpha },
                )
                topActions()
            }
        }
    }
}
