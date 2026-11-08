package com.moodify.app.ui.social

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.moodify.app.data.Track
import com.moodify.app.data.social.ProfileAvatar
import com.moodify.app.ui.designsystem.ArtworkImage
import com.moodify.app.ui.designsystem.placeholderHue
import com.moodify.app.ui.designsystem.pressable
import com.moodify.app.ui.theme.Radii
import com.moodify.app.ui.theme.Spacing

/** The colours an emoji picture can sit on. The same 12, in the same order, on the web. */
val AvatarColors = listOf(
    0xFF7C5CFF, 0xFFFF5C8A, 0xFFFF8A3D, 0xFFF5C542, 0xFF3DDC84, 0xFF2EC4B6,
    0xFF3D9BFF, 0xFF9B5CFF, 0xFFE0457B, 0xFF00B894, 0xFF6C5CE7, 0xFFFD79A8,
).map { Color(it) }

val AvatarEmoji = listOf(
    "🎧", "🎵", "🎸", "🥁", "🎹", "🎤", "🎷", "🎺", "🎻", "🪩", "✨", "🔥",
    "🌙", "☀️", "🌊", "🌸", "🍀", "🦋", "🐱", "🐶", "🦊", "🐼", "👾", "🚀",
)

private val SONG_ID = Regex("^[A-Za-z0-9_-]{11}$")

/** Songs that can be a profile song: catalogue songs with https cover art. */
fun profileSongCandidates(tracks: List<Track>): List<Track> =
    tracks.filter { SONG_ID.matches(it.id) && it.albumArtUrl.startsWith("https://") }.distinctBy { it.id }

fun Track.asProfileSong() = ProfileAvatar.Song(id, title.take(100), artist.take(100), albumArtUrl)

/**
 * Someone's picture: their emoji on its colour, their profile song's cover,
 * or (when they haven't picked one) their initial on a colour from their code.
 */
@Composable
fun AvatarBubble(code: String, name: String, avatar: ProfileAvatar?, size: Dp, modifier: Modifier = Modifier) {
    when (avatar) {
        is ProfileAvatar.Song -> ArtworkImage(
            url = avatar.artworkUrl,
            contentDescription = null,
            placeholderKey = code,
            shape = CircleShape,
            modifier = modifier.size(size),
        )
        is ProfileAvatar.Emoji -> {
            val color = AvatarColors.getOrElse(avatar.color) { AvatarColors[0] }
            Box(
                modifier.size(size).clip(CircleShape).background(Brush.linearGradient(listOf(color, lerp(color, Color.Black, 0.3f)))),
                contentAlignment = Alignment.Center,
            ) {
                // Sized with the circle, not the font-size setting.
                val fontSize = with(LocalDensity.current) { (size * 0.52f).toSp() }
                Text(avatar.emoji, fontSize = fontSize, maxLines = 1)
            }
        }
        null -> {
            val hue = placeholderHue(code)
            Box(
                modifier.size(size).clip(CircleShape).background(Brush.linearGradient(listOf(hue, lerp(hue, Color.Black, 0.35f)))),
                contentAlignment = Alignment.Center,
            ) {
                val fontSize = with(LocalDensity.current) { (size * 0.42f).toSp() }
                Text(name.take(1).uppercase(), fontSize = fontSize, color = Color.White, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

/** "Your picture": an emoji on a colour, or a song whose cover becomes your picture. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AvatarPickerSheet(
    code: String,
    name: String,
    current: ProfileAvatar?,
    songs: List<Track>,
    onSave: (ProfileAvatar?) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember { mutableStateOf(current ?: ProfileAvatar.Emoji(AvatarEmoji[0], 0)) }
    var songTab by remember { mutableStateOf(current is ProfileAvatar.Song) }
    val candidates = remember(songs) { profileSongCandidates(songs).take(40) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(
            Modifier.fillMaxWidth().navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            item(key = "preview") {
                Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Your picture", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(Spacing.md))
                    AvatarBubble(code, name, draft, 96.dp)
                    Spacer(Modifier.height(Spacing.xs))
                    Text(
                        (draft as? ProfileAvatar.Song)?.let { "${it.title} · ${it.artist}" } ?: "Friends see this next to your name",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(Spacing.md))
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        SegmentedButton(selected = !songTab, onClick = { songTab = false }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("Emoji") }
                        SegmentedButton(selected = songTab, onClick = { songTab = true }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("Profile song") }
                    }
                }
            }
            if (!songTab) {
                val emoji = draft as? ProfileAvatar.Emoji
                val color = emoji?.color ?: 0
                items(AvatarEmoji.chunked(6), key = { "e" + it.first() }) { row ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        row.forEach { e ->
                            val selected = emoji?.emoji == e
                            Box(
                                Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(Radii.md))
                                    .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
                                    .pressable(pressedScale = 0.92f) { draft = ProfileAvatar.Emoji(e, color) }
                                    .semantics { this.selected = selected; role = Role.RadioButton },
                                contentAlignment = Alignment.Center,
                            ) {
                                val fontSize = with(LocalDensity.current) { 26.dp.toSp() }
                                Text(e, fontSize = fontSize)
                            }
                        }
                    }
                }
                item(key = "colours") {
                    Text("Colour", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.xs))
                }
                items(AvatarColors.indices.chunked(6), key = { "c" + it.first() }) { row ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        row.forEach { i ->
                            val selected = emoji != null && color == i
                            Box(
                                Modifier.weight(1f).aspectRatio(1f).padding(Spacing.xs).clip(CircleShape).background(AvatarColors[i])
                                    .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
                                    .pressable(pressedScale = 0.92f) { draft = ProfileAvatar.Emoji(emoji?.emoji ?: AvatarEmoji[0], i) }
                                    .semantics { this.selected = selected; role = Role.RadioButton; contentDescription = "Colour ${i + 1}" },
                                contentAlignment = Alignment.Center,
                            ) {
                                if (selected) Icon(Icons.Rounded.Check, contentDescription = null, tint = Color.White)
                            }
                        }
                    }
                }
            } else if (candidates.isEmpty()) {
                item(key = "nosongs") {
                    Text(
                        "Play or like a song first, then pick it here. Its cover becomes your picture.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.md),
                    )
                }
            } else {
                items(candidates, key = { "s" + it.id }) { track ->
                    val selected = (draft as? ProfileAvatar.Song)?.id == track.id
                    Row(
                        Modifier.fillMaxWidth()
                            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                            .pressable(pressedScale = 0.98f) { draft = track.asProfileSong() }
                            .semantics { this.selected = selected; role = Role.RadioButton }
                            .padding(horizontal = Spacing.gutter, vertical = Spacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ArtworkImage(url = track.albumArtUrl, contentDescription = null, placeholderKey = track.id, shape = CircleShape, modifier = Modifier.size(48.dp))
                        Spacer(Modifier.width(Spacing.md))
                        Column(Modifier.weight(1f)) {
                            Text(track.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(track.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (selected) Icon(Icons.Rounded.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            item(key = "actions") {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter).padding(top = Spacing.md, bottom = Spacing.lg),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (current != null) TextButton(onClick = { onSave(null) }) { Text("Remove") }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.width(Spacing.sm))
                    Button(onClick = { onSave(draft) }, enabled = draft != current) { Text("Save") }
                }
            }
        }
    }
}
