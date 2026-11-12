package com.moodify.app.ui.social

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.moodify.app.ui.designsystem.AuraGlow
import com.moodify.app.ui.theme.Radii
import com.moodify.app.ui.theme.Spacing
import kotlin.random.Random

/** Settings › Your Moodify code. */
@Composable
fun AccountScreen(
    state: AccountUiState,
    bottomPadding: Dp,
    onBack: () -> Unit,
    onCreate: (String) -> Unit,
    onRestore: (words: String, name: String) -> Unit,
    onConfirmBackedUp: () -> Unit,
    onRevealWords: () -> Unit,
    onHideWords: () -> Unit,
    onRename: (String) -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
    onDismissMessages: () -> Unit,
    onPrepareLink: (String) -> Unit = {},
    onConfirmLink: () -> Unit = {},
    onCancelLink: () -> Unit = {},
    profileSongs: List<com.moodify.app.data.Track> = emptyList(),
    onSetAvatar: (com.moodify.app.data.social.ProfileAvatar?) -> Unit = {},
) {
    var linking by rememberSaveable { mutableStateOf(false) }
    var picking by rememberSaveable { mutableStateOf(false) }
    if (picking && state.code != null) {
        AvatarPickerSheet(
            code = state.code,
            name = state.displayName,
            current = state.avatar,
            songs = profileSongs,
            onSave = { onSetAvatar(it); picking = false },
            onDismiss = { picking = false },
        )
    }
    if (state.pendingLink != null || linking) {
        LinkBrowserDialog(
            state = state,
            onPrepare = onPrepareLink,
            onConfirm = { onConfirmLink(); linking = false },
            onDismiss = { onCancelLink(); linking = false },
        )
    }
    val context = LocalContext.current
    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
        AuraGlow(intensity = 0.8f, height = 340.dp)
        LazyColumn(
            contentPadding = PaddingValues(bottom = bottomPadding + Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            item(key = "header") {
                Row(
                    Modifier.windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = Spacing.xs, vertical = Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                    Text("Your Moodify code", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
                }
            }
            state.error?.let { message -> item(key = "error") { Banner(message, isError = true, onDismiss = onDismissMessages) } }
            state.notice?.let { message -> item(key = "notice") { Banner(message, isError = false, onDismiss = onDismissMessages) } }

            when {
                state.loading -> item(key = "loading") {
                    Column(Modifier.fillMaxWidth().padding(Spacing.xl), horizontalAlignment = Alignment.CenterHorizontally) { CircularProgressIndicator() }
                }
                state.code == null -> item(key = "setup") { SetupCard(state.busy, onCreate, onRestore) }
                else -> {
                    item(key = "code") { CodeCard(state, context, onRetry, onPickAvatar = { picking = true }) }
                    val words = state.recoveryWords
                    if (words != null) {
                        item(key = "words") {
                            RecoveryCard(
                                words = words,
                                needsConfirmation = !state.backedUp,
                                onConfirmed = onConfirmBackedUp,
                                onHide = onHideWords,
                            )
                        }
                    } else {
                        item(key = "manage") {
                            ManageCard(state, onRevealWords, onRename, onDelete, onLinkBrowser = { linking = true })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SetupCard(busy: Boolean, onCreate: (String) -> Unit, onRestore: (String, String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var restoring by rememberSaveable { mutableStateOf(false) }
    var words by rememberSaveable { mutableStateOf("") }
    Section {
        Icon(Icons.Rounded.Shield, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(Spacing.sm))
        Text(
            if (restoring) "Restore your code" else "Get your code to connect with friends",
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(Modifier.height(Spacing.xs))
        Text(
            if (restoring) {
                "Enter the 24 words you saved when you made your code."
            } else {
                "No phone number, no email, no photos. Your code is your identity, and only people you accept can reach you."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Spacing.lg))
        if (restoring) {
            OutlinedTextField(
                value = words,
                onValueChange = { words = it },
                label = { Text("Recovery key (24 words)") },
                minLines = 3,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(Spacing.sm))
        }
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(24) },
            label = { Text(if (restoring) "Your name (if the server forgot it)" else "What friends see you as") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(Spacing.lg))
        Button(
            onClick = { if (restoring) onRestore(words, name) else onCreate(name) },
            enabled = !busy && (if (restoring) words.isNotBlank() else name.trim().length >= 2),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (busy) "Working…" else if (restoring) "Restore" else "Create my code") }
        TextButton(onClick = { restoring = !restoring }, modifier = Modifier.fillMaxWidth()) {
            Text(if (restoring) "I'm new here: create a code" else "I already have a code")
        }
        if (!restoring) {
            val context = LocalContext.current
            Text(
                "Friends features are for people 13 and older.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(
                onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(com.moodify.app.ui.screens.PRIVACY_URL))) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("How Moodify handles your data") }
        }
    }
}

@Composable
private fun CodeCard(state: AccountUiState, context: Context, onRetry: () -> Unit, onPickAvatar: () -> Unit) {
    val code = state.code ?: return
    Section {
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.foundation.layout.Box(
                Modifier.clip(androidx.compose.foundation.shape.CircleShape)
                    .then(if (state.serverConfigured && !state.busy) Modifier.clickable(onClickLabel = "Change your picture", onClick = onPickAvatar) else Modifier),
            ) {
                AvatarBubble(code, state.displayName, state.avatar, 64.dp)
            }
            Spacer(Modifier.width(Spacing.md))
            Column(Modifier.weight(1f)) {
                Text(state.displayName, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (state.serverConfigured) {
                    TextButton(onClick = onPickAvatar, enabled = !state.busy, contentPadding = PaddingValues(0.dp)) {
                        Text(if (state.avatar == null) "Add a picture" else "Change picture")
                    }
                }
            }
        }
        Text(
            code,
            style = MaterialTheme.typography.displaySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(Spacing.sm))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            FilledTonalButton(onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Moodify code", code))
            }) {
                Icon(Icons.Rounded.ContentCopy, contentDescription = null)
                Spacer(Modifier.width(Spacing.xs))
                Text("Copy")
            }
            FilledTonalButton(onClick = {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, "Add me on Moodify: $code")
                context.startActivity(Intent.createChooser(send, "Share your code"))
            }) {
                Icon(Icons.Rounded.Share, contentDescription = null)
                Spacer(Modifier.width(Spacing.xs))
                Text("Share")
            }
        }
        Spacer(Modifier.height(Spacing.md))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (state.registered) Icons.Rounded.CloudDone else Icons.Rounded.CloudOff,
                contentDescription = null,
                tint = if (state.registered) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(Spacing.sm))
            Text(
                when {
                    state.registered -> "Connected"
                    !state.serverConfigured -> "Saved on this phone. It connects when Moodify's social features go live."
                    else -> "Saved on this phone, not connected yet."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (!state.registered && state.serverConfigured) TextButton(onClick = onRetry, enabled = !state.busy) { Text("Retry") }
        }
    }
}

@Composable
private fun RecoveryCard(words: List<String>, needsConfirmation: Boolean, onConfirmed: () -> Unit, onHide: () -> Unit) {
    // Two random positions to check the words were really written down.
    val check = remember(words) { (0 until 24).shuffled(Random(words.hashCode())).take(2).sorted() }
    var answers by remember(words) { mutableStateOf(listOf("", "")) }
    var checking by remember(words) { mutableStateOf(false) }
    Section {
        Icon(Icons.Rounded.Key, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(Spacing.sm))
        Text("Your recovery key", style = MaterialTheme.typography.titleLarge)
        Text(
            "These 24 words are the only way back into your account on a new phone. Write them down and keep them private; anyone with them can be you.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Spacing.md))
        if (!checking) {
            words.chunked(2).forEachIndexed { row, pair ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    pair.forEachIndexed { column, word ->
                        val number = row * 2 + column + 1
                        Text(
                            "$number. $word",
                            style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier
                                .weight(1f)
                                .padding(vertical = 3.dp)
                                .clip(RoundedCornerShape(Radii.sm))
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                .padding(horizontal = Spacing.sm, vertical = 6.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(Spacing.lg))
            if (needsConfirmation) {
                Button(onClick = { checking = true }, modifier = Modifier.fillMaxWidth()) { Text("I've written them down") }
            } else {
                Button(onClick = onHide, modifier = Modifier.fillMaxWidth()) { Text("Hide recovery key") }
            }
        } else {
            Text("Quick check: which words were these?", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(Spacing.sm))
            check.forEachIndexed { index, position ->
                OutlinedTextField(
                    value = answers[index],
                    onValueChange = { value -> answers = answers.toMutableList().also { it[index] = value } },
                    label = { Text("Word ${position + 1}") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                )
            }
            val correct = check.indices.all { answers[it].trim().equals(words[check[it]], ignoreCase = true) }
            Spacer(Modifier.height(Spacing.md))
            Button(onClick = onConfirmed, enabled = correct, modifier = Modifier.fillMaxWidth()) { Text("Done") }
            TextButton(onClick = { checking = false }, modifier = Modifier.fillMaxWidth()) { Text("Show the words again") }
        }
    }
}

@Composable
private fun ManageCard(state: AccountUiState, onRevealWords: () -> Unit, onRename: (String) -> Unit, onDelete: () -> Unit, onLinkBrowser: () -> Unit) {
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmReveal by remember { mutableStateOf(false) }
    var newName by remember(state.displayName) { mutableStateOf(state.displayName) }
    Section {
        Text("Account", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(Spacing.xs))
        if (renaming) {
            OutlinedTextField(
                value = newName,
                onValueChange = { newName = it.take(24) },
                label = { Text("Display name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row {
                TextButton(onClick = { onRename(newName); renaming = false }, enabled = !state.busy) { Text("Save") }
                TextButton(onClick = { renaming = false }) { Text("Cancel") }
            }
        } else {
            ActionLine("Display name", state.displayName, "Change") { renaming = true }
        }
        ActionLine(
            "Recovery key",
            if (state.backedUp) "Saved by you" else "Not confirmed yet",
            "Show",
        ) { confirmReveal = true }
        if (state.registered) {
            ActionLine("Link a browser", "Use your code on the Moodify web app", "Link") { onLinkBrowser() }
        }
        ActionLine("Delete account", "Removes your code from Moodify", "Delete", destructive = true) { confirmDelete = true }
    }
    if (confirmReveal) {
        AlertDialog(
            onDismissRequest = { confirmReveal = false },
            title = { Text("Show recovery key?") },
            text = { Text("Make sure no one can see your screen. Anyone with these words can use your account.") },
            confirmButton = { TextButton(onClick = { confirmReveal = false; onRevealWords() }) { Text("Show") } },
            dismissButton = { TextButton(onClick = { confirmReveal = false }) { Text("Cancel") } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete your account?") },
            text = { Text("Your code, name and friends are removed from Moodify right away. Your music library on this phone stays.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ActionLine(label: String, value: String, action: String, destructive: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = onClick) {
            Text(action, color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun Banner(message: String, isError: Boolean, onDismiss: () -> Unit) {
    LaunchedEffect(message) { if (!isError) { kotlinx.coroutines.delay(4000); onDismiss() } }
    Text(
        message,
        style = MaterialTheme.typography.bodyMedium,
        color = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer,
        textAlign = TextAlign.Start,
        modifier = Modifier
            .padding(horizontal = Spacing.gutter)
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radii.md))
            .background(if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer)
            .padding(Spacing.md),
    )
}

@Composable
private fun Section(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        Modifier
            .padding(horizontal = Spacing.gutter)
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radii.lg))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(Radii.lg))
            .padding(Spacing.lg),
        content = content,
    )
}

/** Link a browser: type its code (or arrive from its QR code), compare two words, send. */
@Composable
private fun LinkBrowserDialog(state: AccountUiState, onPrepare: (String) -> Unit, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    var code by rememberSaveable { mutableStateOf("") }
    val link = state.pendingLink
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (link == null) "Link a browser" else "Check the words") },
        text = {
            Column {
                if (link == null) {
                    Text("On the web app, choose \"Link my phone\". Type the code it shows.", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(Spacing.md))
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it.uppercase().take(9) },
                        label = { Text("Link code") },
                        placeholder = { Text("K7P2-QXAB") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false),
                    )
                } else {
                    Text("Your browser should show these two words:", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(Spacing.md))
                    Text(
                        link.words.joinToString("  ·  "),
                        style = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(Spacing.md))
                    Text(
                        "If they're different, cancel: someone may be trying to copy your account.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                state.error?.let {
                    Spacer(Modifier.height(Spacing.sm))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            if (link == null) {
                TextButton(onClick = { onPrepare(code) }, enabled = !state.busy && code.count { it.isLetterOrDigit() } == 8) { Text("Next") }
            } else {
                TextButton(onClick = onConfirm, enabled = !state.busy) { Text("They match, link") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
