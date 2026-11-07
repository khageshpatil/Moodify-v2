package com.moodify.app.ui.social

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.moodify.app.data.social.FriendPresence
import com.moodify.app.data.social.SharedItem
import com.moodify.app.data.social.SocialState
import com.moodify.app.data.social.SocialUser
import com.moodify.app.ui.designsystem.ArtworkImage
import com.moodify.app.ui.designsystem.AuraGlow
import com.moodify.app.ui.designsystem.EmptyState
import com.moodify.app.ui.designsystem.EqualizerBars
import com.moodify.app.ui.designsystem.LocalMoodifyActions
import com.moodify.app.ui.designsystem.SectionHeader
import com.moodify.app.ui.designsystem.placeholderHue
import com.moodify.app.ui.designsystem.pressable
import com.moodify.app.ui.theme.Radii
import com.moodify.app.ui.theme.Spacing

/** The Friends tab: requests, what friends are playing, songs they sent you. */
@Composable
fun FriendsScreen(
    state: SocialState,
    bottomPadding: Dp,
    onOpenAccount: () -> Unit,
    onAddFriend: (code: String, note: String) -> Unit,
    onAccept: (SocialUser) -> Unit,
    onIgnore: (SocialUser) -> Unit,
    onRemove: (SocialUser) -> Unit,
    onBlock: (SocialUser) -> Unit,
    onReport: (SocialUser, String) -> Unit,
    onDismissShared: (Long) -> Unit,
    onRefresh: () -> Unit,
    onJoinRoom: (String) -> Unit = {},
    onFollow: (SocialUser) -> Unit = {},
    conversations: List<com.moodify.app.data.social.ConversationRow> = emptyList(),
    onOpenChat: (String) -> Unit = {},
    onListenTogether: (SocialUser) -> Unit = {},
    nowPlayingTitle: String? = null,
    onSendNowPlaying: (SocialUser) -> Unit = {},
    prefillCode: String? = null,
    listState: LazyListState = rememberLazyListState(),
) {
    val actions = LocalMoodifyActions.current
    val context = LocalContext.current
    var adding by rememberSaveable { mutableStateOf(prefillCode != null) }
    var menuFor by remember { mutableStateOf<SocialUser?>(null) }
    var profileFor by remember { mutableStateOf<SocialUser?>(null) }
    var showingCode by rememberSaveable { mutableStateOf(false) }
    var scanned by rememberSaveable { mutableStateOf<String?>(null) }
    val scan = {
        scanFriendCode(
            context,
            onCode = { scanned = it; adding = true },
            onError = { android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show() },
        )
    }
    LaunchedEffect(state.myCode, state.serverConfigured) { if (state.myCode != null) onRefresh() }

    val friends = state.lists.friends
    val listening = friends.mapNotNull { friend -> state.presence[friend.code]?.takeIf { it.online && it.playing && it.track != null }?.let { friend to it } }

    Box(Modifier.fillMaxSize()) {
        AuraGlow(intensity = 0.7f, height = 320.dp)
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(bottom = bottomPadding + Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            item(key = "header") {
                Row(
                    Modifier.windowInsetsPadding(WindowInsets.statusBars).padding(start = Spacing.gutter, end = Spacing.sm, top = Spacing.lg),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Friends", style = MaterialTheme.typography.displaySmall, modifier = Modifier.weight(1f).semantics { heading() })
                    if (state.myCode != null) {
                        IconButton(
                            onClick = { adding = true },
                            modifier = Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                        ) { Icon(Icons.Rounded.PersonAdd, contentDescription = "Add a friend", tint = MaterialTheme.colorScheme.onPrimary) }
                    }
                }
            }

            if (state.myCode == null) {
                item(key = "setup") { FriendsIntro(onGetCode = onOpenAccount) }
                return@LazyColumn
            }

            item(key = "mycode") {
                Row(
                    Modifier
                        .padding(horizontal = Spacing.gutter)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Radii.lg))
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .pressable(pressedScale = 0.98f, onClick = onOpenAccount)
                        .padding(Spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Your code", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(state.myCode, style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace), color = MaterialTheme.colorScheme.primary)
                        Text(
                            when {
                                !state.serverConfigured -> "Friends go live soon. Your code is saved."
                                state.connected -> "Connected"
                                else -> "Connecting…"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { showingCode = true }) { Icon(Icons.Rounded.QrCode2, contentDescription = "Show your code as a QR") }
                    IconButton(onClick = { shareCode(context, state.myCode) }) { Icon(Icons.Rounded.Share, contentDescription = "Share your code") }
                }
            }

            if (state.lists.incoming.isNotEmpty()) {
                item(key = "requestsHeader") { SectionHeader(title = "Requests") }
                items(state.lists.incoming, key = { "in:${it.code}" }) { user ->
                    RequestRow(user, onAccept = { onAccept(user) }, onIgnore = { onIgnore(user) })
                }
            }

            if (listening.isNotEmpty()) {
                item(key = "listeningHeader") { SectionHeader(title = "Listening now") }
                items(listening, key = { "live:${it.first.code}" }) { (friend, presence) ->
                    ListeningRow(friend, presence, onPlay = { presence.track?.let { actions.play(it, listOf(it)) } }, onFollow = { onFollow(friend) })
                }
            }

            if (conversations.isNotEmpty()) {
                item(key = "chatsHeader") { SectionHeader(title = "Messages") }
                items(conversations.take(10), key = { "chat:${it.last.peer}" }) { row ->
                    val friend = state.lists.friends.firstOrNull { it.code == row.last.peer }
                    ConversationRowItem(row, name = friend?.displayName ?: row.last.peer, onOpen = { onOpenChat(row.last.peer) })
                }
            }



            item(key = "friendsHeader") { SectionHeader(title = if (friends.isEmpty()) "Your friends" else "Your friends · ${friends.size}") }
            if (friends.isEmpty() && state.lists.outgoing.isEmpty()) {
                item(key = "noFriends") { AddFirstFriend(onScan = scan, onShowCode = { showingCode = true }) }
            }
            items(friends, key = { "f:${it.code}" }) { friend ->
                FriendRow(friend, state.presence[friend.code], onChat = { onOpenChat(friend.code) }, onOpen = { profileFor = friend })
            }
            if (state.lists.outgoing.isNotEmpty()) {
                items(state.lists.outgoing, key = { "out:${it.code}" }) { user ->
                    FriendRow(user, presence = null, waiting = true, onOpen = { profileFor = user })
                }
            }
        }
    }

    if (adding) {
        AddFriendDialog(
            initialCode = scanned ?: prefillCode.orEmpty(),
            onScan = { adding = false; scan() },
            onDismiss = { adding = false; scanned = null },
            onAdd = { code, note -> onAddFriend(code, note); adding = false; scanned = null },
        )
    }
    if (showingCode && state.myCode != null) {
        MyCodeSheet(code = state.myCode, name = state.myName, onDismiss = { showingCode = false })
    }
    profileFor?.let { user ->
        val isFriend = friends.any { it.code == user.code }
        FriendProfileSheet(
            user = user,
            presence = state.presence[user.code],
            isFriend = isFriend,
            nowPlayingTitle = nowPlayingTitle,
            onMessage = { profileFor = null; onOpenChat(user.code) },
            onFollow = { profileFor = null; onFollow(user) },
            onListenTogether = { profileFor = null; onListenTogether(user) },
            onSendNowPlaying = { profileFor = null; onSendNowPlaying(user) },
            onMore = { profileFor = null; menuFor = user },
            onDismiss = { profileFor = null },
        )
    }
    menuFor?.let { user ->
        FriendMenu(
            user = user,
            onDismiss = { menuFor = null },
            onRemove = { onRemove(user); menuFor = null },
            onBlock = { onBlock(user); menuFor = null },
            onReport = { reason -> onReport(user, reason); menuFor = null },
            onMessage = null,
        )
    }
}

@Composable
internal fun Avatar(user: SocialUser, size: Dp = 44.dp) {
    // Generated from the code: no photos in Moodify.
    val hue = placeholderHue(user.code)
    Box(
        Modifier.size(size).clip(CircleShape).background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(hue, hue.copy(alpha = 0.55f)))),
        contentAlignment = Alignment.Center,
    ) {
        Text(user.displayName.take(1).uppercase(), style = MaterialTheme.typography.titleMedium, color = Color.White)
    }
}

@Composable
private fun RequestRow(user: SocialUser, onAccept: () -> Unit, onIgnore: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter), verticalAlignment = Alignment.CenterVertically) {
        Avatar(user)
        Spacer(Modifier.width(Spacing.md))
        Column(Modifier.weight(1f)) {
            Text(user.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(user.note ?: user.code, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        }
        TextButton(onClick = onIgnore) { Text("Ignore") }
        Button(onClick = onAccept) { Text("Accept") }
    }
}

@Composable
private fun SharedRow(item: SharedItem, onOpen: () -> Unit, onDismiss: () -> Unit) {
    val title = if (item.roomId != null) "Listen together" else item.track?.title ?: item.collection?.title.orEmpty()
    val subtitle = when {
        item.roomId != null -> item.track?.let { "${it.title} · room ${item.roomId}" } ?: "Room ${item.roomId}"
        else -> item.track?.artist ?: item.collection?.type?.replaceFirstChar { it.uppercase() }.orEmpty()
    }
    val art = item.track?.albumArtUrl ?: item.collection?.artworkUrl.orEmpty()
    Row(
        Modifier
            .fillMaxWidth()
            .pressable(pressedScale = 0.98f, onClick = onOpen)
            .padding(start = Spacing.gutter, end = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtworkImage(url = art, contentDescription = null, placeholderKey = title, shape = RoundedCornerShape(Radii.sm), modifier = Modifier.size(52.dp))
        Spacer(Modifier.width(Spacing.md))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("$subtitle · from ${item.fromName} · ${timeAgo(item.sentAtSeconds)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (item.roomId != null) FilledTonalButton(onClick = onOpen) { Text("Join") }
        IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, contentDescription = "Dismiss", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun ListeningRow(friend: SocialUser, presence: FriendPresence, onPlay: () -> Unit, onFollow: () -> Unit) {
    val track = presence.track ?: return
    Row(
        Modifier
            .padding(horizontal = Spacing.gutter)
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radii.lg))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .pressable(pressedScale = 0.98f, onClick = onPlay)
            .padding(Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtworkImage(url = track.albumArtUrl, contentDescription = null, placeholderKey = track.id, shape = RoundedCornerShape(Radii.md), modifier = Modifier.size(60.dp))
        Spacer(Modifier.width(Spacing.md))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                EqualizerBars(playing = true, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(Spacing.xs))
                Text(friend.displayName, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, maxLines = 1)
            }
            Text(track.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(track.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(horizontalAlignment = Alignment.End) {
            FilledTonalButton(onClick = onFollow) { Text("Follow") }
            TextButton(onClick = onPlay) { Text("Just play") }
        }
    }
}

@Composable
private fun FriendRow(user: SocialUser, presence: FriendPresence?, waiting: Boolean = false, onChat: (() -> Unit)? = null, onOpen: (() -> Unit)? = null) {
    val status = if (waiting) "Request sent · waiting for them" else friendStatus(presence)
    Row(
        Modifier.fillMaxWidth()
            .then(if (onOpen != null) Modifier.pressable(pressedScale = 0.98f, onClick = onOpen) else Modifier)
            .padding(start = Spacing.gutter, end = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            Avatar(user)
            if (presence?.online == true) OnlineDot(Modifier.align(Alignment.BottomEnd))
        }
        Spacer(Modifier.width(Spacing.md))
        Column(Modifier.weight(1f)) {
            Text(user.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (onChat != null) IconButton(onClick = onChat) { Icon(Icons.AutoMirrored.Rounded.Chat, contentDescription = "Message ${user.displayName}") }
        else IconButton(onClick = { onOpen?.invoke() }) { Icon(Icons.Rounded.MoreVert, contentDescription = "Options for ${user.displayName}") }
    }
}

@Composable
private fun AddFriendDialog(initialCode: String, onScan: () -> Unit, onDismiss: () -> Unit, onAdd: (String, String) -> Unit) {
    var code by rememberSaveable { mutableStateOf(initialCode) }
    var note by rememberSaveable { mutableStateOf("") }
    val valid = com.moodify.app.data.social.MoodifyIdentity.normalizeCode(code) != null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a friend") },
        text = {
            Column {
                androidx.compose.material3.OutlinedButton(onClick = onScan, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.QrCode2, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(Spacing.sm))
                    Text("Scan their QR code")
                }
                Text(
                    "or type it",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = Spacing.sm),
                )
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.take(20) },
                    label = { Text("Their Moodify code") },
                    placeholder = { Text("MOOD-XXXX-XXXX") },
                    singleLine = true,
                    isError = code.length >= 8 && !valid,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false),
                )
                Spacer(Modifier.height(Spacing.sm))
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(60) },
                    label = { Text("Note (optional)") },
                    placeholder = { Text("It's Asha from college") },
                    singleLine = true,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onAdd(code, note) }, enabled = valid) { Text("Send request") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FriendMenu(user: SocialUser, onDismiss: () -> Unit, onRemove: () -> Unit, onBlock: () -> Unit, onReport: (String) -> Unit, onMessage: (() -> Unit)? = null) {
    var confirm by remember { mutableStateOf<String?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = Spacing.gutter).padding(bottom = Spacing.xl)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(user, 52.dp)
                Spacer(Modifier.width(Spacing.md))
                Column {
                    Text(user.displayName, style = MaterialTheme.typography.titleLarge)
                    Text(user.code, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(Spacing.lg))
            onMessage?.let { TextButton(onClick = it, modifier = Modifier.fillMaxWidth()) { Text("Message") } }
            TextButton(onClick = { confirm = "remove" }, modifier = Modifier.fillMaxWidth()) { Text("Remove friend") }
            TextButton(onClick = { confirm = "block" }, modifier = Modifier.fillMaxWidth()) { Text("Block", color = MaterialTheme.colorScheme.error) }
            TextButton(onClick = { confirm = "report" }, modifier = Modifier.fillMaxWidth()) { Text("Report", color = MaterialTheme.colorScheme.error) }
        }
    }
    when (confirm) {
        "remove" -> Confirm("Remove ${user.displayName}?", "You'll stop seeing each other's music.", "Remove", onRemove) { confirm = null }
        "block" -> Confirm("Block ${user.displayName}?", "They won't be able to add you or send you anything, and they won't be told.", "Block", onBlock) { confirm = null }
        "report" -> ReportDialog(onReport = onReport, onDismiss = { confirm = null })
    }
}

@Composable
private fun Confirm(title: String, text: String, action: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = { onConfirm(); onDismiss() }) { Text(action, color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ReportDialog(onReport: (String) -> Unit, onDismiss: () -> Unit) {
    var reason by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Report") },
        text = {
            OutlinedTextField(value = reason, onValueChange = { reason = it.take(500) }, label = { Text("What happened?") }, minLines = 3)
        },
        confirmButton = { TextButton(onClick = { onReport(reason); onDismiss() }, enabled = reason.isNotBlank()) { Text("Send report") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Pick a friend to send a song or collection to. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SendToFriendSheet(
    title: String,
    friends: List<SocialUser>,
    hasIdentity: Boolean,
    onPick: (SocialUser) -> Unit,
    onGetCode: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(bottom = Spacing.xl)) {
            Text("Send \"$title\"", style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = Spacing.gutter))
            Text("End-to-end encrypted: only your friend can see it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = Spacing.gutter))
            Spacer(Modifier.height(Spacing.md))
            when {
                !hasIdentity -> EmptyState(icon = Icons.Rounded.Group, title = "Get your Moodify code", message = "Add friends to send them music.", actionLabel = "Get your code", onAction = onGetCode)
                friends.isEmpty() -> Text("Add friends on the Friends tab first.", modifier = Modifier.padding(Spacing.gutter))
                else -> friends.forEach { friend ->
                    Row(
                        Modifier.fillMaxWidth().pressable(pressedScale = 0.98f) { onPick(friend) }.padding(horizontal = Spacing.gutter, vertical = Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Avatar(friend)
                        Spacer(Modifier.width(Spacing.md))
                        Text(friend.displayName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun ConversationRowItem(row: com.moodify.app.data.social.ConversationRow, name: String, onOpen: () -> Unit) {
    val last = row.last
    val preview = when (last.kind) {
        "text" -> last.body
        "track" -> "🎵 " + (com.moodify.app.data.social.SharePayload.parse(last.body)?.track?.title ?: "A song")
        "collection" -> "💿 " + (com.moodify.app.data.social.SharePayload.parse(last.body)?.collection?.title ?: "A collection")
        "playlist" -> "📃 " + (com.moodify.app.data.social.SharePayload.parse(last.body)?.playlist?.name ?: "A playlist")
        "room" -> "🎧 Listen together"
        else -> "Message"
    }
    Row(
        Modifier.fillMaxWidth().pressable(pressedScale = 0.98f, onClick = onOpen).padding(horizontal = Spacing.gutter, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(SocialUser(code = last.peer, displayName = name, boxPub = ByteArray(0)))
        Spacer(Modifier.width(Spacing.md))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                (if (last.fromMe) "You: " else "") + preview,
                style = MaterialTheme.typography.bodySmall,
                color = if (row.unread > 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(Spacing.sm))
        Column(horizontalAlignment = Alignment.End) {
            Text(timeAgo(last.sentAtMs / 1000), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (row.unread > 0) {
                Text(
                    if (row.unread > 99) "99+" else row.unread.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.padding(top = 2.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary).padding(horizontal = 6.dp, vertical = 1.dp),
                )
            }
        }
    }
}

internal fun timeAgo(epochSeconds: Long): String {
    val minutes = ((System.currentTimeMillis() / 1000 - epochSeconds) / 60).coerceAtLeast(0)
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "${minutes}m ago"
        minutes < 24 * 60 -> "${minutes / 60}h ago"
        else -> "${minutes / (24 * 60)}d ago"
    }
}
