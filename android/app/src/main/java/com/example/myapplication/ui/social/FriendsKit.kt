package com.moodify.app.ui.social

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.moodify.app.data.social.FriendPresence
import com.moodify.app.data.social.MoodifyIdentity
import com.moodify.app.data.social.SocialUser
import com.moodify.app.ui.designsystem.ArtworkImage
import com.moodify.app.ui.designsystem.EqualizerBars
import com.moodify.app.ui.designsystem.pressable
import com.moodify.app.ui.theme.Radii
import com.moodify.app.ui.theme.Spacing

/** What a friend is doing, in a few words: never their raw code. */
fun friendStatus(presence: FriendPresence?): String = when {
    presence == null -> "Offline"
    presence.online && presence.playing && presence.track != null -> "Listening to ${presence.track.title}"
    presence.online -> "Online"
    presence.track != null && presence.lastSeenAtSeconds != null -> "Played ${presence.track.title} · ${timeAgo(presence.lastSeenAtSeconds)}"
    presence.lastSeenAtSeconds != null -> "Last seen ${timeAgo(presence.lastSeenAtSeconds)}"
    else -> "Offline"
}

/** The add-me link inside a code's QR; any camera app opens Moodify with it. */
fun addLink(code: String) = "moodify://add/$code"

/** A Moodify code from what a scanned QR (or pasted text) says, or null. */
fun codeFromScan(raw: String?): String? {
    val text = raw?.trim() ?: return null
    val fromLink = Regex("""moodify://add/([A-Za-z0-9-]+)""", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)
    return MoodifyIdentity.normalizeCode(fromLink ?: text)
}

/**
 * Scans a friend's QR with Google's scanner (Play services draws the camera
 * screen, so Moodify needs no camera permission). Calls [onCode] with their
 * code, or [onError] when it isn't one or scanning isn't available.
 */
fun scanFriendCode(context: Context, onCode: (String) -> Unit, onError: (String) -> Unit) {
    val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
    runCatching {
        GmsBarcodeScanning.getClient(context, options).startScan()
            .addOnSuccessListener { barcode -> codeFromScan(barcode.rawValue)?.let(onCode) ?: onError("That QR isn't a Moodify code.") }
            .addOnFailureListener { onError("Couldn't open the scanner. Type the code instead.") }
    }.onFailure { onError("Scanning needs Google Play services. Type the code instead.") }
}

fun shareCode(context: Context, code: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, "Add me on Moodify: $code\n${addLink(code)}")
    context.startActivity(Intent.createChooser(send, "Share your code"))
}

/** A QR code for [text], dark on white so every scanner reads it. */
@Composable
fun QrCode(text: String, modifier: Modifier = Modifier) {
    val bitmap = remember(text) {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 512, 512, mapOf(EncodeHintType.MARGIN to 1))
        val pixels = IntArray(matrix.width * matrix.height) { i -> if (matrix.get(i % matrix.width, i / matrix.width)) 0xFF0B0B0F.toInt() else 0xFFFFFFFF.toInt() }
        Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888).asImageBitmap()
    }
    Image(bitmap, contentDescription = "QR code for $text", modifier = modifier.clip(RoundedCornerShape(Radii.lg)))
}

/** Your code as a big QR, for a friend standing next to you. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyCodeSheet(code: String, name: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = Spacing.gutter).padding(bottom = Spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(name, style = MaterialTheme.typography.titleLarge)
            Text(code, style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace), color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(Spacing.lg))
            QrCode(addLink(code), Modifier.size(240.dp))
            Spacer(Modifier.height(Spacing.md))
            Text(
                "Your friend scans this in Moodify (Friends › Add › Scan), or with their camera.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(Spacing.md))
            FilledTonalButton(onClick = { shareCode(context, code) }) {
                Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(Spacing.sm))
                Text("Share a link instead")
            }
        }
    }
}

/**
 * A friend's page: what they're doing and what you can do together. Remove,
 * block and report stay one level down.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FriendProfileSheet(
    user: SocialUser,
    presence: FriendPresence?,
    isFriend: Boolean,
    nowPlayingTitle: String?,
    onMessage: () -> Unit,
    onFollow: () -> Unit,
    onListenTogether: () -> Unit,
    onSendNowPlaying: () -> Unit,
    onMore: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = Spacing.gutter).padding(bottom = Spacing.xl)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box {
                    Avatar(user, 64.dp)
                    if (presence?.online == true) OnlineDot(Modifier.align(Alignment.BottomEnd), 16.dp)
                }
                Spacer(Modifier.width(Spacing.md))
                Column(Modifier.weight(1f)) {
                    Text(user.displayName, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (isFriend) friendStatus(presence) else "Request sent",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            val live = presence?.takeIf { it.online && it.playing && it.track != null }
            live?.track?.let { track ->
                Spacer(Modifier.height(Spacing.md))
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(Radii.lg)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ArtworkImage(url = track.albumArtUrl, contentDescription = null, placeholderKey = track.id, shape = RoundedCornerShape(Radii.sm), modifier = Modifier.size(48.dp))
                    Spacer(Modifier.width(Spacing.md))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            EqualizerBars(playing = true, color = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(Spacing.xs))
                            Text("Playing now", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        }
                        Text(track.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(track.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (isFriend) {
                Spacer(Modifier.height(Spacing.lg))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    ProfileAction(Icons.AutoMirrored.Rounded.Chat, "Message", enabled = true, onMessage)
                    ProfileAction(Icons.Rounded.Sensors, "Follow", enabled = live != null, onFollow)
                    ProfileAction(Icons.Rounded.Headphones, "Listen", enabled = true, onListenTogether)
                    ProfileAction(Icons.Rounded.MusicNote, "Send song", enabled = nowPlayingTitle != null, onSendNowPlaying)
                }
                if (live == null) {
                    Text(
                        "Follow works while ${user.displayName} is listening.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = Spacing.sm),
                    )
                }
            }
            if (onMore != null) {
                Spacer(Modifier.height(Spacing.md))
                TextButton(onClick = onMore, modifier = Modifier.fillMaxWidth()) {
                    Text(if (isFriend) "Remove, block or report" else "Block or report", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun ProfileAction(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    val alpha = if (enabled) 1f else 0.38f
    Column(
        Modifier.width(76.dp).clip(RoundedCornerShape(Radii.md)).pressable(pressedScale = 0.94f, enabled = enabled, onClick = onClick).padding(vertical = Spacing.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f * alpha)),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary.copy(alpha = alpha)) }
        Spacer(Modifier.height(Spacing.xs))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
    }
}

@Composable
fun OnlineDot(modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 12.dp) {
    Box(
        modifier.size(size).clip(CircleShape)
            .background(MaterialTheme.colorScheme.background).padding(2.dp).clip(CircleShape).background(Color(0xFF3DDC84)),
    )
}

/** First run: what friends are for, and one button. */
@Composable
fun FriendsIntro(onGetCode: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter, vertical = Spacing.lg), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.Group, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp)) }
        Spacer(Modifier.height(Spacing.md))
        Text("Music is better with friends", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(Spacing.lg))
        IntroLine(Icons.AutoMirrored.Rounded.Chat, "Chat and send songs", "Private: only you and your friend can read it.")
        IntroLine(Icons.Rounded.Sensors, "Follow a friend", "Hear what they're playing, as they play it.")
        IntroLine(Icons.Rounded.Headphones, "Listen together", "Same song, same moment, on every phone.")
        Spacer(Modifier.height(Spacing.lg))
        androidx.compose.material3.Button(onClick = onGetCode, modifier = Modifier.fillMaxWidth()) { Text("Get your Moodify code") }
        Text(
            "No phone number, email or photos: just a code you share.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = Spacing.sm),
        )
    }
}

@Composable
private fun IntroLine(icon: ImageVector, title: String, text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(Spacing.md))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** No friends yet: the two ways to add one. */
@Composable
fun AddFirstFriend(onScan: () -> Unit, onShowCode: () -> Unit) {
    Column(
        Modifier.padding(horizontal = Spacing.gutter).fillMaxWidth().clip(RoundedCornerShape(Radii.lg))
            .background(MaterialTheme.colorScheme.surfaceContainer).padding(Spacing.lg),
    ) {
        Text("Add your first friend", style = MaterialTheme.typography.titleMedium)
        Text(
            "Together: scan each other's QR. Apart: share your code.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Spacing.md))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            androidx.compose.material3.Button(onClick = onScan) { Text("Scan a code") }
            FilledTonalButton(onClick = onShowCode) { Text("Show my code") }
        }
    }
}
