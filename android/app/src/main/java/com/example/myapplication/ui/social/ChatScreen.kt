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
) {
    val actions = LocalMoodifyActions.current
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
            state.friend?.let { Avatar(it, size = 40.dp) }
            Spacer(Modifier.width(Spacing.md))
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val p = state.presence
                val status = when {
                    state.friend == null -> "Not friends any more"
                    p?.online == true && p.playing && p.track != null -> "Listening to ${p.track.title}"
                    p?.online == true -> "Online"
                    else -> "End-to-end encrypted"
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (p?.online == true && p.playing) { EqualizerBars(playing = true, color = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(4.dp)) }
                    Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
            items(state.messages, key = { it.mid }) { message ->
                Bubble(
                    message = message,
                    onLongPress = { menuFor = message },
                    onRetry = { onRetry(message.mid) },
                    onPlay = { track -> actions.play(track, listOf(track)) },
                    onOpenCollection = { item -> item.endpoint?.let { actions.openBrowse(it, item.title) } },
                    onJoinRoom = onJoinRoom,
                )
            }
        }

        // Composer
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = Spacing.sm, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (nowPlaying != null && state.friend != null) {
                IconButton(onClick = { onShareTrack(nowPlaying) }) {
                    Icon(Icons.Rounded.MusicNote, contentDescription = "Send what you're playing: ${nowPlaying.title}", tint = MaterialTheme.colorScheme.primary)
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
    onLongPress: () -> Unit,
    onRetry: () -> Unit,
    onPlay: (Track) -> Unit,
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
                .combinedClickable(onClick = { if (message.status == SocialRepository.STATUS_FAILED) onRetry() }, onLongClick = onLongPress)
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
                    action = "Play", onAction = { onPlay(parsed.track) },
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
            else -> DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(message.sentAtMs))
        }
        Text(
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
