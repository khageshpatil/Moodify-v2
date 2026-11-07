package com.moodify.app.ui.social

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.moodify.app.data.Track
import com.moodify.app.data.social.RoomMember
import com.moodify.app.data.social.RoomUiState
import com.moodify.app.data.social.SocialUser
import com.moodify.app.ui.designsystem.ArtworkImage
import com.moodify.app.ui.designsystem.EmptyState
import com.moodify.app.ui.designsystem.EqualizerBars
import com.moodify.app.ui.theme.Radii
import com.moodify.app.ui.theme.Spacing

/** Callbacks for [ListeningRoomSheet]. */
data class RoomActions(
    val onStart: () -> Unit,
    val onJoin: (String) -> Unit,
    val onLeave: () -> Unit,
    val onChat: (String) -> Unit,
    val onInvite: (SocialUser) -> Unit,
    val onMakeDj: (String) -> Unit,
    val onMakeHost: (String) -> Unit,
    val onNext: () -> Unit,
    val onQueue: (Track) -> Unit,
    val onGetCode: () -> Unit,
)

/**
 * Listen together (social M4): start or join a room, see who's in it,
 * what's coming up, and chat. Everyone hears the same moment of the song.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListeningRoomSheet(
    room: RoomUiState?,
    friends: List<SocialUser>,
    hasIdentity: Boolean,
    actions: RoomActions,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(bottom = Spacing.xl),
        ) {
            when {
                !hasIdentity -> EmptyState(
                    icon = Icons.Rounded.Group,
                    title = "Listen together",
                    message = "Get your Moodify code to start a room with friends.",
                    actionLabel = "Get your code",
                    onAction = actions.onGetCode,
                )
                room == null -> StartOrJoin(actions)
                else -> InRoom(room, friends, actions)
            }
        }
    }
}

@Composable
private fun StartOrJoin(actions: RoomActions) {
    var code by remember { mutableStateOf("") }
    Column(Modifier.padding(horizontal = Spacing.gutter), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        Text("Listen together", style = MaterialTheme.typography.titleLarge)
        Text(
            "Start a room and invite friends. Everyone hears the same song at the same moment, on their own phone.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = actions.onStart, modifier = Modifier.fillMaxWidth()) { Text("Start a room with this song") }
        Spacer(Modifier.height(Spacing.sm))
        Text("Have a room code?", style = MaterialTheme.typography.titleSmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = code,
                onValueChange = { code = it.filter(Char::isLetterOrDigit).uppercase().take(6) },
                placeholder = { Text("K7P2QX") },
                singleLine = true,
                textStyle = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { if (code.length == 6) actions.onJoin(code) }),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(Spacing.sm))
            FilledTonalButton(onClick = { actions.onJoin(code) }, enabled = code.length == 6) { Text("Join") }
        }
    }
}

@Composable
private fun InRoom(room: RoomUiState, friends: List<SocialUser>, actions: RoomActions) {
    val context = LocalContext.current
    var inviting by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }

    Column(Modifier.padding(horizontal = Spacing.gutter)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Listening room", style = MaterialTheme.typography.titleLarge)
                Text(
                    buildString {
                        append(room.roomId.chunked(3).joinToString(" "))
                        append(" · ")
                        append(
                            when {
                                !room.connected -> room.error ?: "Connecting…"
                                room.isHost -> "You're the host"
                                room.role == "dj" -> "You're a DJ"
                                else -> "Following the host"
                            },
                        )
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (room.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = {
                val text = "Listen with me on Moodify: moodify://room/${room.roomId}"
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share room"))
            }) { Icon(Icons.Rounded.Share, contentDescription = "Share room link") }
        }
        Spacer(Modifier.height(Spacing.md))

        // Now playing
        room.track?.let { track ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radii.lg))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(Spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ArtworkImage(url = track.albumArtUrl, contentDescription = null, placeholderKey = track.id, shape = RoundedCornerShape(Radii.md), modifier = Modifier.size(52.dp))
                Spacer(Modifier.width(Spacing.md))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        EqualizerBars(playing = room.playing, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(Spacing.xs))
                        Text(if (room.playing) "Playing for everyone" else "Paused", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                    Text(track.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(track.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (room.canControl && room.queue.isNotEmpty()) {
                    IconButton(onClick = actions.onNext) { Icon(Icons.Rounded.SkipNext, contentDescription = "Play next in room") }
                }
            }
        } ?: Text(
            if (room.canControl) "Play any song and everyone in the room will hear it." else "Waiting for the host to play something.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Up next
        if (room.queue.isNotEmpty()) {
            SheetHeading("Up next · ${room.queue.size}")
            room.queue.take(10).forEach { track -> TrackLine(track, label = null) }
        }

        // Suggestions from listeners (hosts and DJs can add them)
        if (room.canControl && room.suggestions.isNotEmpty()) {
            SheetHeading("Suggested")
            room.suggestions.takeLast(5).reversed().forEach { (name, track) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { TrackLine(track, label = "from $name") }
                    TextButton(onClick = { actions.onQueue(track) }) { Text("Add") }
                }
            }
        }

        // People
        SheetHeading("In the room · ${room.members.size}")
        room.members.forEach { member -> MemberRow(member, isYou = member.code == room.you, canManage = room.isHost, actions) }

        // Invite
        Spacer(Modifier.height(Spacing.sm))
        OutlinedButton(onClick = { inviting = !inviting }, modifier = Modifier.fillMaxWidth()) { Text(if (inviting) "Done inviting" else "Invite friends") }
        if (inviting) {
            val inRoom = room.members.map { it.code }.toSet()
            val candidates = friends.filterNot { it.code in inRoom }
            if (candidates.isEmpty()) {
                Text("All your friends are here, or you haven't added any yet.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = Spacing.sm))
            }
            candidates.forEach { friend ->
                Row(Modifier.fillMaxWidth().padding(vertical = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(friend, size = 36.dp)
                    Spacer(Modifier.width(Spacing.md))
                    Text(friend.displayName, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    FilledTonalButton(onClick = { actions.onInvite(friend) }) { Text("Invite") }
                }
            }
        }

        // Chat
        SheetHeading("Chat")
        if (room.chat.isEmpty()) {
            Text("Say hi. Only people in the room right now see this. Room chat isn't end-to-end encrypted and isn't saved.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        room.chat.takeLast(30).forEach { line ->
            Text(
                "${if (line.from == room.you) "You" else line.displayName}: ${line.text}",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 2.dp),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = Spacing.sm)) {
            OutlinedTextField(
                value = message,
                onValueChange = { message = it.take(300) },
                placeholder = { Text("Message the room") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (message.isNotBlank()) { actions.onChat(message); message = "" } }),
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { if (message.isNotBlank()) { actions.onChat(message); message = "" } }, enabled = message.isNotBlank()) {
                Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "Send")
            }
        }

        Spacer(Modifier.height(Spacing.lg))
        TextButton(onClick = actions.onLeave, modifier = Modifier.fillMaxWidth()) {
            Text("Leave room", color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun SheetHeading(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = Spacing.lg, bottom = Spacing.xs))
}

@Composable
private fun TrackLine(track: Track, label: String?) {
    Row(Modifier.fillMaxWidth().padding(vertical = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
        ArtworkImage(url = track.albumArtUrl, contentDescription = null, placeholderKey = track.id, shape = RoundedCornerShape(Radii.sm), modifier = Modifier.size(40.dp))
        Spacer(Modifier.width(Spacing.md))
        Column(Modifier.weight(1f)) {
            Text(track.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(track.artist, label).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun MemberRow(member: RoomMember, isYou: Boolean, canManage: Boolean, actions: RoomActions) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(vertical = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
        Text(
            member.displayName.take(1).uppercase().ifBlank { "?" },
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer)
                .padding(top = 8.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.width(Spacing.md))
        Column(Modifier.weight(1f)) {
            Text(if (isYou) "${member.displayName} (you)" else member.displayName, style = MaterialTheme.typography.titleSmall, maxLines = 1)
            Text(
                when (member.role) { "host" -> "Host"; "dj" -> "DJ"; else -> "Listener" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (canManage && !isYou) {
            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "Options for ${member.displayName}") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (member.role != "dj") DropdownMenuItem(text = { Text("Make DJ") }, onClick = { menu = false; actions.onMakeDj(member.code) })
                DropdownMenuItem(text = { Text("Make host") }, onClick = { menu = false; actions.onMakeHost(member.code) })
            }
        }
    }
}
