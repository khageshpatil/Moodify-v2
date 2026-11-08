package com.moodify.app.ui.social

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.moodify.app.data.Track
import com.moodify.app.data.social.ChatMessageEntity
import com.moodify.app.data.social.Reactions
import com.moodify.app.data.social.SharePayload
import com.moodify.app.data.social.SocialRepository
import com.moodify.app.ui.designsystem.ArtworkImage
import com.moodify.app.ui.designsystem.EqualizerBars
import com.moodify.app.ui.designsystem.LocalMoodifyActions
import com.moodify.app.ui.theme.Radii
import com.moodify.app.ui.theme.Spacing
import java.text.DateFormat
import java.util.Date

/** A conversation with one friend: text, songs, collections and room invites, end-to-end encrypted. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(
    state: ChatUiState,
    nowPlaying: Track?,
    onBack: () -> Unit,
    onSend: (String) -> Unit,
    onShareTrack: (Track) -> Unit,
    onRetry: (String) -> Unit,
    onReact: (mid: String, emoji: String) -> Unit,
    onJoinRoom: (String) -> Unit,
    onSavePlaylist: (String, List<Track>) -> Unit = { _, _ -> },
    onOpenProfile: () -> Unit = {},
    onListenTogether: () -> Unit = {},
    /** What "+" offers to send: your playlists, liked songs and recent plays. */
    pickPlaylists: List<com.moodify.app.data.LocalPlaylist> = emptyList(),
    pickLiked: List<Track> = emptyList(),
    pickRecent: List<Track> = emptyList(),
    onSendPlaylist: (String, List<Track>) -> Unit = { _, _ -> },
) {
    val actions = LocalMoodifyActions.current
    var picking by remember { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf("") }
    var menuFor by remember { mutableStateOf<ChatMessageEntity?>(null) }
    val list = rememberLazyListState()
    val name = state.friend?.displayName ?: state.peer
    LaunchedEffect(state.messages.size) { if (state.messages.isNotEmpty()) list.animateScrollToItem(state.messages.lastIndex) }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).imePadding()) {
        // Header: who, and what they're playing.
        Row(
            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = Spacing.xs, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
            Row(
                Modifier.weight(1f).clip(RoundedCornerShape(Radii.md)).combinedClickable(onClick = onOpenProfile).padding(vertical = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
            state.friend?.let { Avatar(it, size = 40.dp) }
            Spacer(Modifier.width(Spacing.md))
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val p = state.presence
                val status = when {
                    state.friend == null -> "Not friends any more"
                    p?.online == true && p.playing && p.track != null -> "Listening to ${p.track.title}"
                    else -> friendStatus(p)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (p?.online == true && p.playing) { EqualizerBars(playing = true, color = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(4.dp)) }
                    Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            }
            if (state.friend != null) {
                IconButton(onClick = onListenTogether) {
                    Icon(Icons.Rounded.Headphones, contentDescription = "Listen together with $name", tint = MaterialTheme.colorScheme.primary)
                }
            }
        }

        LazyColumn(
            state = list,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = Spacing.gutter, vertical = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            if (state.messages.isEmpty()) {
                item("empty") {
                    Text(
                        "Say hi to $name, or send what you're playing. Only the two of you can read this chat.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = Spacing.xl),
                    )
                }
            }
            if (state.messages.isNotEmpty()) {
                item("e2e") {
                    Text(
                        "🔒 Only you and $name can read this\nDouble-tap to ❤️ · long-press for more",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                    )
                }
            }
            items(state.messages.size, key = { state.messages[it].mid }) { index ->
                val message = state.messages[index]
                val previous = state.messages.getOrNull(index - 1)
                val next = state.messages.getOrNull(index + 1)
                Column {
                if (previous == null || !sameDay(previous.sentAtMs, message.sentAtMs)) DayLabel(message.sentAtMs)
                // One time per run of messages from the same person within a few minutes.
                val showTime = next == null || next.fromMe != message.fromMe ||
                    next.sentAtMs - message.sentAtMs > GROUP_MS || !sameDay(message.sentAtMs, next.sentAtMs)
                Bubble(
                    message = message,
                    showTime = showTime,
                    onDoubleTap = {
                        val mine = state.myCode?.let { Reactions.parse(message.reactions)[it] }
                        if (state.friend != null) onReact(message.mid, if (mine == "❤️") "" else "❤️")
                    },
                    onSavePlaylist = onSavePlaylist,
                    onLongPress = { menuFor = message },
                    onRetry = { onRetry(message.mid) },
                    onPlay = { track, queue -> actions.play(track, queue) },
                    onOpenCollection = { item -> item.endpoint?.let { actions.openBrowse(it, item.title) } },
                    onJoinRoom = onJoinRoom,
                )
                }
            }
        }

        // Composer
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = Spacing.sm, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.friend != null) {
                IconButton(onClick = { picking = true }) {
                    Icon(Icons.Rounded.Add, contentDescription = "Send a song or playlist", tint = MaterialTheme.colorScheme.primary)
                }
            }
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it.take(SharePayload.MAX_TEXT) },
                placeholder = { Text(if (state.friend != null) "Message" else "You can't message $name") },
                enabled = state.friend != null,
                maxLines = 5,
                shape = RoundedCornerShape(Radii.xl),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (draft.isNotBlank()) { onSend(draft); draft = "" } }),
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { onSend(draft); draft = "" }, enabled = draft.isNotBlank() && state.friend != null) {
                Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "Send", tint = if (draft.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    if (picking) {
        SendPicker(
            nowPlaying = nowPlaying,
            playlists = pickPlaylists,
            liked = pickLiked,
            recent = pickRecent,
            onTrack = { picking = false; onShareTrack(it) },
            onPlaylist = { picking = false; onSendPlaylist(it.name, it.tracks) },
            onDismiss = { picking = false },
        )
    }

    menuFor?.let { message ->
        val clipboard = LocalClipboardManager.current
        ModalBottomSheet(onDismissRequest = { menuFor = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.navigationBarsPadding().padding(bottom = Spacing.lg)) {
                val mine = state.myCode?.let { Reactions.parse(message.reactions)[it] }
                Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter), horizontalArrangement = Arrangement.SpaceBetween) {
                    Reactions.QUICK.forEach { emoji ->
                        Text(
                            emoji,
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(if (emoji == mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
                                .combinedClickable(onClick = { onReact(message.mid, if (emoji == mine) "" else emoji); menuFor = null })
                                .padding(Spacing.sm),
                        )
                    }
                }
                if (message.kind == "text") {
                    TextButton(onClick = { clipboard.setText(AnnotatedString(message.body)); menuFor = null }, modifier = Modifier.padding(horizontal = Spacing.md)) {
                        Icon(Icons.Rounded.ContentCopy, contentDescription = null); Spacer(Modifier.width(Spacing.sm)); Text("Copy text")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Bubble(
    message: ChatMessageEntity,
    showTime: Boolean,
    onDoubleTap: () -> Unit,
    onSavePlaylist: (String, List<Track>) -> Unit,
    onLongPress: () -> Unit,
    onRetry: () -> Unit,
    onPlay: (Track, List<Track>) -> Unit,
    onOpenCollection: (com.moodify.app.data.CatalogItem) -> Unit,
    onJoinRoom: (String) -> Unit,
) {
    val mine = message.fromMe
    val parsed = if (message.kind == "text") null else SharePayload.parse(message.body)
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
        Box(
            Modifier
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(Radii.lg))
                .background(if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
                .combinedClickable(onClick = { if (message.status == SocialRepository.STATUS_FAILED) onRetry() }, onLongClick = onLongPress, onDoubleClick = onDoubleTap)
                .padding(if (message.kind == "text") Spacing.md else Spacing.sm),
        ) {
            val onColor = if (mine) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
            when {
                message.kind == "text" -> Text(message.body, style = MaterialTheme.typography.bodyLarge, color = onColor)
                parsed?.roomId != null -> Card(
                    art = parsed.track?.albumArtUrl.orEmpty(), key = parsed.roomId, title = "Listen together",
                    subtitle = parsed.track?.let { "${it.title} · room ${parsed.roomId}" } ?: "Room ${parsed.roomId}", color = onColor,
                    action = if (mine) null else "Join", onAction = { onJoinRoom(parsed.roomId) },
                )
                parsed?.track != null -> Card(
                    art = parsed.track.albumArtUrl, key = parsed.track.id, title = parsed.track.title, subtitle = parsed.track.artist, color = onColor,
                    action = "Play", onAction = { onPlay(parsed.track, listOf(parsed.track)) },
                )
                parsed?.playlist != null -> PlaylistCard(
                    playlist = parsed.playlist,
                    color = onColor,
                    mine = mine,
                    onPlay = { parsed.playlist.tracks.firstOrNull()?.let { first -> onPlay(first, parsed.playlist.tracks) } },
                    onSave = { onSavePlaylist(parsed.playlist.name, parsed.playlist.tracks) },
                )
                parsed?.collection != null -> Card(
                    art = parsed.collection.artworkUrl, key = parsed.collection.id, title = parsed.collection.title,
                    subtitle = parsed.collection.type.replaceFirstChar { it.uppercase() }, color = onColor,
                    action = "Open", onAction = { onOpenCollection(parsed.collection) },
                )
                else -> Text("This message needs a newer Moodify.", style = MaterialTheme.typography.bodyMedium, color = onColor)
            }
        }
        val reactions = Reactions.parse(message.reactions)
        if (reactions.isNotEmpty()) {
            Text(
                reactions.values.groupingBy { it }.eachCount().entries.joinToString(" ") { (emoji, n) -> if (n > 1) "$emoji$n" else emoji },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(horizontal = Spacing.sm, vertical = 2.dp),
            )
        }
        val meta = when (message.status) {
            SocialRepository.STATUS_SENDING -> "Sending…"
            SocialRepository.STATUS_FAILED -> "Not sent · tap to retry"
            else -> if (showTime) DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(message.sentAtMs)) else null
        }
        if (meta != null) Text(
            meta,
            style = MaterialTheme.typography.labelSmall,
            color = if (message.status == SocialRepository.STATUS_FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp, start = Spacing.xs, end = Spacing.xs),
        )
    }
}

@Composable
private fun Card(
    art: String,
    key: String,
    title: String,
    subtitle: String,
    color: androidx.compose.ui.graphics.Color,
    action: String?,
    onAction: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ArtworkImage(url = art, contentDescription = null, placeholderKey = key, shape = RoundedCornerShape(Radii.sm), modifier = Modifier.size(56.dp))
        Spacer(Modifier.width(Spacing.md))
        Column(Modifier.weight(1f, fill = false).widthIn(max = 150.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = color, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = color.copy(alpha = 0.75f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (action != null) {
            Spacer(Modifier.width(Spacing.sm))
            FilledTonalButton(onClick = onAction, contentPadding = PaddingValues(horizontal = Spacing.md)) {
                if (action == "Play") Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(action)
            }
        }
    }
}

private const val GROUP_MS = 5 * 60_000L

private fun sameDay(a: Long, b: Long): Boolean {
    val ca = java.util.Calendar.getInstance().apply { timeInMillis = a }
    val cb = java.util.Calendar.getInstance().apply { timeInMillis = b }
    return ca.get(java.util.Calendar.YEAR) == cb.get(java.util.Calendar.YEAR) && ca.get(java.util.Calendar.DAY_OF_YEAR) == cb.get(java.util.Calendar.DAY_OF_YEAR)
}

/** "Today", "Yesterday", or the date. */
@Composable
private fun DayLabel(atMs: Long) {
    val now = System.currentTimeMillis()
    val label = when {
        sameDay(atMs, now) -> "Today"
        sameDay(atMs, now - 24 * 60 * 60_000L) -> "Yesterday"
        else -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(atMs))
    }
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.sm),
    )
}

/** A friend's playlist: play it, or keep your own copy. */
@Composable
private fun PlaylistCard(
    playlist: SharePayload.SharedPlaylist,
    color: androidx.compose.ui.graphics.Color,
    mine: Boolean,
    onPlay: () -> Unit,
    onSave: () -> Unit,
) {
    Column(Modifier.widthIn(max = 280.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val first = playlist.tracks.firstOrNull()
            ArtworkImage(url = first?.albumArtUrl.orEmpty(), contentDescription = null, placeholderKey = playlist.name, shape = RoundedCornerShape(Radii.sm), modifier = Modifier.size(56.dp))
            Spacer(Modifier.width(Spacing.md))
            Column(Modifier.weight(1f, fill = false)) {
                Text(playlist.name, style = MaterialTheme.typography.titleSmall, color = color, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    "Playlist · ${playlist.tracks.size} ${if (playlist.tracks.size == 1) "song" else "songs"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = color.copy(alpha = 0.75f),
                )
                Text(
                    playlist.tracks.take(3).joinToString(", ") { it.title },
                    style = MaterialTheme.typography.bodySmall,
                    color = color.copy(alpha = 0.6f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(Spacing.sm))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            FilledTonalButton(onClick = onPlay, contentPadding = PaddingValues(horizontal = Spacing.md)) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("Play")
            }
            if (!mine) {
                var saved by androidx.compose.runtime.saveable.rememberSaveable(playlist.name) { mutableStateOf(false) }
                FilledTonalButton(onClick = { onSave(); saved = true }, enabled = !saved, contentPadding = PaddingValues(horizontal = Spacing.md)) {
                    Text(if (saved) "Saved ✓" else "Save", maxLines = 1)
                }
            }
        }
    }
}

/** "+": pick something to send. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SendPicker(
    nowPlaying: Track?,
    playlists: List<com.moodify.app.data.LocalPlaylist>,
    liked: List<Track>,
    recent: List<Track>,
    onTrack: (Track) -> Unit,
    onPlaylist: (com.moodify.app.data.LocalPlaylist) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(Modifier.navigationBarsPadding(), contentPadding = PaddingValues(bottom = Spacing.lg)) {
            item("title") {
                Text("Send", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.sm))
            }
            if (nowPlaying != null) {
                item("nowHeader") { PickerHeader("Playing now") }
                item("now") { PickerRow(nowPlaying.albumArtUrl, nowPlaying.id, nowPlaying.title, nowPlaying.artist) { onTrack(nowPlaying) } }
            }
            val withSongs = playlists.filter { it.tracks.isNotEmpty() }
            if (withSongs.isNotEmpty()) {
                item("plHeader") { PickerHeader("Your playlists") }
                items(withSongs.size, key = { "pl:${withSongs[it].id}" }) { i ->
                    val p = withSongs[i]
                    PickerRow(p.tracks.first().albumArtUrl, p.id, p.name, "Playlist · ${p.tracks.size} songs") { onPlaylist(p) }
                }
            }
            if (liked.isNotEmpty()) {
                item("likedHeader") { PickerHeader("Liked songs") }
                val l = liked.take(50)
                items(l.size, key = { "like:${l[it].id}" }) { i -> PickerRow(l[i].albumArtUrl, l[i].id, l[i].title, l[i].artist) { onTrack(l[i]) } }
            }
            val r = recent.distinctBy { it.id }.filter { t -> liked.none { it.id == t.id } }.take(20)
            if (r.isNotEmpty()) {
                item("recentHeader") { PickerHeader("Recently played") }
                items(r.size, key = { "recent:${r[it].id}" }) { i -> PickerRow(r[i].albumArtUrl, r[i].id, r[i].title, r[i].artist) { onTrack(r[i]) } }
            }
            if (nowPlaying == null && withSongs.isEmpty() && liked.isEmpty() && r.isEmpty()) {
                item("empty") {
                    Text(
                        "Play or like some songs first, and they'll show up here to send.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(Spacing.gutter),
                    )
                }
            }
        }
    }
}

@Composable
private fun PickerHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = Spacing.gutter, end = Spacing.gutter, top = Spacing.md, bottom = Spacing.xs),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PickerRow(art: String, key: String, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onClick).padding(horizontal = Spacing.gutter, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtworkImage(url = art, contentDescription = null, placeholderKey = key, shape = RoundedCornerShape(Radii.sm), modifier = Modifier.size(44.dp))
        Spacer(Modifier.width(Spacing.md))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "Send", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
    }
}
