package com.moodify.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.moodify.app.data.AppUpdates
import com.moodify.app.data.UpdateState
import com.moodify.app.ui.theme.Spacing

/**
 * "Moodify 1.1.18 is available": asks once per version (Later hides it for a
 * few days), then shows the download and opens Android's installer.
 */
@Composable
fun UpdatePrompt(updates: AppUpdates) {
    val prompt by updates.prompt.collectAsStateWithLifecycle()
    val state by updates.state.collectAsStateWithLifecycle()

    prompt?.let { release ->
        AlertDialog(
            onDismissRequest = { updates.snooze(release) },
            title = { Text("Update available") },
            text = {
                Column {
                    Text("Moodify ${release.versionName} is ready. You have ${updates.currentVersion}.", style = MaterialTheme.typography.bodyMedium)
                    if (release.notes.isNotEmpty()) {
                        Spacer(Modifier.height(Spacing.md))
                        Text("What's new", style = MaterialTheme.typography.titleSmall)
                        release.notes.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                    }
                    Spacer(Modifier.height(Spacing.md))
                    Text(
                        "It installs over this version and keeps your music, playlists and friends.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { updates.download(release) }) { Text("Update") } },
            dismissButton = { TextButton(onClick = { updates.snooze(release) }) { Text("Later") } },
        )
    }

    when (val s = state) {
        is UpdateState.Downloading -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Downloading ${s.release.versionName}") },
            text = {
                Column {
                    LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(Spacing.sm))
                    Text("${(s.progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {},
        )
        is UpdateState.ReadyToInstall -> AlertDialog(
            onDismissRequest = updates::dismiss,
            title = { Text("Ready to install") },
            text = {
                Text(
                    "Moodify ${s.release.versionName} is downloaded. If Android asks, allow Moodify to install apps, then tap Install.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = { TextButton(onClick = { updates.install() }) { Text("Install") } },
            dismissButton = { TextButton(onClick = updates::dismiss) { Text("Not now") } },
        )
        is UpdateState.Failed -> if (s.release != null) AlertDialog(
            onDismissRequest = updates::dismiss,
            title = { Text("Update didn't download") },
            text = { Text(s.message) },
            confirmButton = { TextButton(onClick = { updates.download(s.release) }) { Text("Try again") } },
            dismissButton = { TextButton(onClick = updates::dismiss) { Text("Later") } },
        )
        else -> Unit
    }
}
