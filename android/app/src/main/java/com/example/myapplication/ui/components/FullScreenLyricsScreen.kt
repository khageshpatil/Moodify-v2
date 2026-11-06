package com.moodify.app.ui.components

import android.content.Context
import android.content.Intent
import android.app.SearchManager
import android.media.AudioManager
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.moodify.app.data.LyricsDocument
import com.moodify.app.data.LyricsLine
import com.moodify.app.data.LyricsRomanizer
import com.moodify.app.data.LyricsSearchResult
import com.moodify.app.data.Track
import com.moodify.app.player.PlaybackProgress
import com.moodify.app.player.PlaybackState
import kotlinx.coroutines.flow.StateFlow
import androidx.compose.runtime.collectAsState
import kotlin.math.abs

/** Full-screen synchronized lyrics player, implemented independently in Moodify. */
@Composable
fun FullScreenLyricsScreen(
    track: Track,
    state: PlaybackState,
    progress: StateFlow<PlaybackProgress>,
    lyrics: LyricsDocument?,
    lyricsErrorMessage: String?,
    actionErrorMessage: String?,
    isLoading: Boolean,
    isSearching: Boolean,
    searchResults: List<LyricsSearchResult>,
    searchErrorMessage: String?,
    isTranslating: Boolean,
    syncOffsetMs: Int,
    showControls: Boolean,
    showTranslation: Boolean,
    showRomanization: Boolean,
    onClose: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onRefresh: () -> Unit,
    onSearch: (String, String) -> Unit,
    onSelectSearchResult: (LyricsSearchResult) -> Unit,
    onSaveLyrics: (String) -> Unit,
    onTranslate: (String) -> Unit,
    onSyncOffsetChange: (Int) -> Unit,
    onShowControlsChange: (Boolean) -> Unit,
    onShowTranslationChange: (Boolean) -> Unit,
    onShowRomanizationChange: (Boolean) -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var showSearchDialog by remember { mutableStateOf(false) }
    var showResultsDialog by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }
    var showOffsetDialog by remember { mutableStateOf(false) }
    var showTranslateDialog by remember { mutableStateOf(false) }
    var titleQuery by remember(track.id) { mutableStateOf(track.title) }
    var artistQuery by remember(track.id) { mutableStateOf(track.artist) }
    var editedLyrics by remember(track.id, lyrics?.plainText) { mutableStateOf(lyrics.asEditableText()) }
    var translationLanguage by remember { mutableStateOf("en") }
    var offsetDraft by remember(syncOffsetMs) { mutableFloatStateOf(syncOffsetMs.toFloat()) }

    BackHandler(onBack = onClose)

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val configuration = LocalConfiguration.current
        val landscape = configuration.screenWidthDp > configuration.screenHeightDp
        val playbackProgress by progress.collectAsState()
        val position = playbackProgress.positionMs.coerceAtLeast(0L)
        val activeIndex = remember(lyrics, position, syncOffsetMs) {
            lyrics?.lines?.indexOfLast { line ->
                line.startMs?.let { it <= position + syncOffsetMs } == true
            } ?: -1
        }
        val lyricRows = remember(lyrics) { lyrics.lyricRows() }
        val activeRowIndex = remember(activeIndex, lyricRows) {
            lyricRows.indexOfFirst { row -> row.any { it.index == activeIndex } }
        }
        val context = LocalContext.current

        Box(modifier = Modifier.fillMaxSize().background(Color(0xFF101010))) {
            AsyncImage(
                model = track.albumArtUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().scale(1.12f).blur(36.dp),
            )
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        0f to Color(0xBB111111),
                        0.38f to Color(0x9C101010),
                        1f to Color(0xEE080808),
                    ),
                ),
            )

            Column(
                modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)
                    .padding(horizontal = if (landscape) 28.dp else 20.dp, vertical = 8.dp),
            ) {
                LyricsHeader(
                    track = track,
                    onClose = onClose,
                    menuExpanded = menuExpanded,
                    onMenuExpandedChange = { menuExpanded = it },
                    onSearch = { titleQuery = track.title; artistQuery = track.artist; showSearchDialog = true },
                    onEdit = { editedLyrics = lyrics.asEditableText(); showEditDialog = true },
                    onRefresh = onRefresh,
                    onOffset = { offsetDraft = syncOffsetMs.toFloat(); showOffsetDialog = true },
                    onTranslate = { showTranslateDialog = true },
                    onShare = { context.shareLyrics(track, lyrics) },
                    showControls = showControls,
                    onShowControlsChange = onShowControlsChange,
                    showTranslation = showTranslation,
                    hasTranslation = lyrics?.lines?.any { !it.translation.isNullOrBlank() } == true,
                    onShowTranslationChange = onShowTranslationChange,
                    showRomanization = showRomanization,
                    onShowRomanizationChange = onShowRomanizationChange,
                )

                if (landscape) {
                    Row(
                        Modifier.weight(1f).fillMaxWidth().padding(top = 6.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        LyricsTextPane(
                            lyrics = lyrics,
                            activeIndex = activeIndex,
                            activeRowIndex = activeRowIndex,
                            syncOffsetMs = syncOffsetMs,
                            showTranslation = showTranslation,
                            showRomanization = showRomanization,
                            isLoading = isLoading,
                            errorMessage = lyricsErrorMessage,
                            onRetry = onRefresh,
                            onSearch = { titleQuery = track.title; artistQuery = track.artist; showSearchDialog = true },
                            onEdit = { editedLyrics = lyrics.asEditableText(); showEditDialog = true },
                            onSeek = onSeek,
                            positionMs = position,
                            isPlaying = state.isPlaying,
                            modifier = Modifier.weight(1.2f).fillMaxHeight(),
                        )
                        if (showControls) {
                            PlayerControls(
                                state = state,
                                progress = playbackProgress,
                                progressFlow = progress,
                                onPlayPause = onPlayPause,
                                onNext = onNext,
                                onPrevious = onPrevious,
                                onSeek = onSeek,
                                modifier = Modifier.weight(0.8f).padding(start = 24.dp),
                            )
                        }
                    }
                } else {
                    LyricsTextPane(
                        lyrics = lyrics,
                        activeIndex = activeIndex,
                        activeRowIndex = activeRowIndex,
                        syncOffsetMs = syncOffsetMs,
                        showTranslation = showTranslation,
                        showRomanization = showRomanization,
                        isLoading = isLoading,
                        errorMessage = lyricsErrorMessage,
                        onRetry = onRefresh,
                        onSearch = { titleQuery = track.title; artistQuery = track.artist; showSearchDialog = true },
                        onEdit = { editedLyrics = lyrics.asEditableText(); showEditDialog = true },
                        onSeek = onSeek,
                        positionMs = position,
                        isPlaying = state.isPlaying,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                    if (showControls) {
                        PlayerControls(
                            state = state,
                            progress = playbackProgress,
                            progressFlow = progress,
                            onPlayPause = onPlayPause,
                            onNext = onNext,
                            onPrevious = onPrevious,
                            onSeek = onSeek,
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        )
                    }
                }
                if (isTranslating) {
                    Text("Translating lyrics…", color = Color.White.copy(alpha = .8f), modifier = Modifier.align(Alignment.CenterHorizontally).padding(4.dp))
                }
                (actionErrorMessage ?: searchErrorMessage)?.let { message ->
                    Text(message, color = Color(0xFFFFD4C8), style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), textAlign = TextAlign.Center)
                }
            }
        }

        if (showSearchDialog) {
            AlertDialog(
                onDismissRequest = { showSearchDialog = false },
                title = { Text("Find lyrics") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(titleQuery, { titleQuery = it }, label = { Text("Song title") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next), modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(artistQuery, { artistQuery = it }, label = { Text("Artist") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { onSearch(titleQuery, artistQuery); showSearchDialog = false; showResultsDialog = true }),
                            modifier = Modifier.fillMaxWidth())
                        TextButton(
                            onClick = {
                                runCatching {
                                    context.startActivity(Intent(Intent.ACTION_WEB_SEARCH).putExtra(
                                        SearchManager.QUERY,
                                        "${artistQuery.trim()} ${titleQuery.trim()} lyrics",
                                    ))
                                }
                                showSearchDialog = false
                            },
                        ) { Text("Search the web") }
                    }
                },
                confirmButton = {
                    TextButton(enabled = !isSearching && titleQuery.isNotBlank() && artistQuery.isNotBlank(), onClick = {
                        onSearch(titleQuery, artistQuery); showSearchDialog = false; showResultsDialog = true
                    }) { Text(if (isSearching) "Searching…" else "Search") }
                },
                dismissButton = { TextButton(onClick = { showSearchDialog = false }) { Text("Cancel") } },
            )
        }

        if (showResultsDialog) {
            AlertDialog(
                onDismissRequest = { showResultsDialog = false },
                title = { Text("Lyrics matches") },
                text = {
                    when {
                        isSearching -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) { androidx.compose.material3.CircularProgressIndicator(Modifier.size(24.dp)); Text("Searching lyric sources…") }
                        searchResults.isEmpty() -> Text(searchErrorMessage ?: "No results yet. Search by another title or artist.")
                        else -> LazyColumn(modifier = Modifier.heightIn(max = 440.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            itemsIndexed(searchResults) { _, result ->
                                Surface(
                                    color = Color.White.copy(alpha = .08f),
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier.fillMaxWidth().clickable {
                                        onSelectSearchResult(result); showResultsDialog = false
                                    },
                                ) {
                                    Column(Modifier.padding(14.dp)) {
                                        Text(result.title, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text("${result.document.source} · ${if (result.document.isSynced) "Synced" else "Plain"}", color = Color.White.copy(alpha = .68f), style = MaterialTheme.typography.bodySmall)
                                        Text(result.document.lines.take(2).joinToString("  ") { it.text }, color = Color.White.copy(alpha = .54f), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { showResultsDialog = false }) { Text("Done") } },
            )
        }

        if (showEditDialog) {
            AlertDialog(
                onDismissRequest = { showEditDialog = false },
                title = { Text("Edit lyrics") },
                text = {
                    OutlinedTextField(
                        value = editedLyrics,
                        onValueChange = { editedLyrics = it },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp, max = 440.dp),
                        placeholder = { Text("Paste lyrics or timed LRC lines") },
                    )
                },
                confirmButton = { TextButton(onClick = { onSaveLyrics(editedLyrics); showEditDialog = false }) { Text("Save") } },
                dismissButton = { TextButton(onClick = { showEditDialog = false }) { Text("Cancel") } },
            )
        }

        if (showOffsetDialog) {
            AlertDialog(
                onDismissRequest = { showOffsetDialog = false },
                title = { Text("Lyrics timing") },
                text = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("${if (offsetDraft > 0) "+" else ""}${(offsetDraft / 1000f).formatOneDecimal()} sec", style = MaterialTheme.typography.titleMedium)
                        Slider(value = offsetDraft, onValueChange = { offsetDraft = it }, valueRange = -5000f..5000f, steps = 99)
                        Text("Move the active line ahead or behind playback", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                confirmButton = { TextButton(onClick = { onSyncOffsetChange(offsetDraft.toInt()); showOffsetDialog = false }) { Text("Apply") } },
                dismissButton = { TextButton(onClick = { offsetDraft = 0f }) { Text("Reset") } },
            )
        }

        if (showTranslateDialog) {
            AlertDialog(
                onDismissRequest = { showTranslateDialog = false },
                title = { Text("Translate lyrics") },
                text = {
                    OutlinedTextField(
                        value = translationLanguage,
                        onValueChange = { translationLanguage = it },
                        label = { Text("Target language code") },
                        supportingText = { Text("Examples: es, hi, fr, ja") },
                        singleLine = true,
                    )
                },
                confirmButton = { TextButton(enabled = !isTranslating && translationLanguage.isNotBlank(), onClick = {
                    onTranslate(translationLanguage); showTranslateDialog = false
                }) { Text("Translate") } },
                dismissButton = { TextButton(onClick = { showTranslateDialog = false }) { Text("Cancel") } },
            )
        }
    }
}

@Composable
private fun LyricsHeader(
    track: Track,
    onClose: () -> Unit,
    menuExpanded: Boolean,
    onMenuExpandedChange: (Boolean) -> Unit,
    onSearch: () -> Unit,
    onEdit: () -> Unit,
    onRefresh: () -> Unit,
    onOffset: () -> Unit,
    onTranslate: () -> Unit,
    onShare: () -> Unit,
    showControls: Boolean,
    onShowControlsChange: (Boolean) -> Unit,
    showTranslation: Boolean,
    hasTranslation: Boolean,
    onShowTranslationChange: (Boolean) -> Unit,
    showRomanization: Boolean,
    onShowRomanizationChange: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp), verticalAlignment = Alignment.CenterVertically) {
        AsyncImage(
            model = track.albumArtUrl,
            contentDescription = "Album artwork for ${track.title}",
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)),
        )
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(track.title, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(track.artist, color = Color.White.copy(alpha = .76f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close lyrics", tint = Color.White) }
        Box {
            IconButton(onClick = { onMenuExpandedChange(true) }) { Icon(Icons.Default.MoreVert, "Lyrics options", tint = Color.White) }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { onMenuExpandedChange(false) }) {
                DropdownMenuItem(text = { Text("Find lyrics") }, leadingIcon = { Icon(Icons.Default.Search, null) }, onClick = { onMenuExpandedChange(false); onSearch() })
                DropdownMenuItem(text = { Text("Edit lyrics") }, leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = { onMenuExpandedChange(false); onEdit() })
                DropdownMenuItem(text = { Text("Refresh lyrics") }, leadingIcon = { Icon(Icons.Default.Refresh, null) }, onClick = { onMenuExpandedChange(false); onRefresh() })
                DropdownMenuItem(text = { Text("Timing offset") }, leadingIcon = { Icon(Icons.Default.Tune, null) }, onClick = { onMenuExpandedChange(false); onOffset() })
                DropdownMenuItem(text = { Text("Translate lyrics") }, leadingIcon = { Icon(Icons.Default.Translate, null) }, onClick = { onMenuExpandedChange(false); onTranslate() })
                DropdownMenuItem(text = { Text("Share lyrics") }, leadingIcon = { Icon(Icons.Default.Share, null) }, onClick = { onMenuExpandedChange(false); onShare() })
                DropdownMenuItem(
                    text = { Text(if (showRomanization) "Hide romanization" else "Show romanization") },
                    onClick = { onMenuExpandedChange(false); onShowRomanizationChange(!showRomanization) },
                )
                DropdownMenuItem(
                    text = { Text(if (showControls) "Hide player controls" else "Show player controls") },
                    onClick = { onMenuExpandedChange(false); onShowControlsChange(!showControls) },
                )
                if (hasTranslation) {
                    DropdownMenuItem(
                        text = { Text(if (showTranslation) "Hide translation" else "Show translation") },
                        onClick = { onMenuExpandedChange(false); onShowTranslationChange(!showTranslation) },
                    )
                }
            }
        }
    }
}

@Composable
private fun LyricsTextPane(
    lyrics: LyricsDocument?,
    activeIndex: Int,
    activeRowIndex: Int,
    syncOffsetMs: Int,
    showTranslation: Boolean,
    showRomanization: Boolean,
    isLoading: Boolean,
    errorMessage: String?,
    onRetry: () -> Unit,
    onSearch: () -> Unit,
    onEdit: () -> Unit,
    onSeek: (Long) -> Unit,
    positionMs: Long,
    modifier: Modifier = Modifier,
    isPlaying: Boolean = true,
) {
    val smoothTime = remember { androidx.compose.runtime.mutableLongStateOf(positionMs + syncOffsetMs) }
    // Interpolates between the player's 2 Hz position updates so the karaoke
    // fill moves at the display frame rate. Lives outside the list so it keeps
    // running whatever is scrolled into view.
    LaunchedEffect(positionMs, syncOffsetMs, isPlaying) {
        val base = positionMs + syncOffsetMs
        smoothTime.longValue = base
        if (!isPlaying) return@LaunchedEffect
        val startedAt = withFrameMillis { it }
        while (true) {
            withFrameMillis { now -> smoothTime.longValue = base + (now - startedAt) }
        }
    }
    val listState = rememberLazyListState()
    val rows = remember(lyrics) { lyrics.lyricRows() }
    LaunchedEffect(activeRowIndex, rows.size) {
        if (activeRowIndex >= 0 && lyrics?.isSynced == true) {
            val centerOffset = (listState.layoutInfo.viewportSize.height / 6).coerceAtLeast(0)
            listState.animateScrollToItem(
                activeRowIndex.coerceIn(0, rows.lastIndex),
                scrollOffset = -centerOffset,
            )
        }
    }

    when {
        // Placeholder lines while lyric sources are searched, so the screen says what's happening.
        isLoading -> Column(
            modifier.padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text("Finding lyrics…", color = Color.White.copy(alpha = .72f), style = MaterialTheme.typography.titleMedium)
            listOf(0.82f, 0.64f, 0.9f, 0.55f, 0.74f).forEach { width ->
                Box(
                    Modifier
                        .padding(top = 18.dp)
                        .fillMaxWidth(width)
                        .height(22.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(Color.White.copy(alpha = .12f)),
                )
            }
        }
        lyrics == null || lyrics.lines.isEmpty() -> Column(
            modifier = modifier.padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(if (errorMessage != null) "Lyrics could not be loaded" else "No lyrics found", color = Color.White, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Text(errorMessage ?: "Find a match or add your own lyrics.", color = Color.White.copy(alpha = .72f), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
            Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onSearch) { Text("Find lyrics", color = Color.White) }
                TextButton(onClick = onEdit) { Text("Edit", color = Color.White) }
                if (errorMessage != null) TextButton(onClick = onRetry) { Text("Retry", color = Color.White) }
            }
        }
        else -> LazyColumn(
            state = listState,
            modifier = modifier,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 110.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            if (!lyrics.isSynced) {
                item {
                    Text("PLAIN LYRICS · ${lyrics.source}", color = Color.White.copy(alpha = .6f), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(bottom = 8.dp))
                }
            }
            itemsIndexed(rows, key = { index, row -> "$index:${row.firstOrNull()?.value?.startMs}:${row.joinToString { it.value.text }.hashCode()}" }) { rowIndex, row ->
                val active = rowIndex == activeRowIndex && lyrics.isSynced
                val focusDistance = if (lyrics.isSynced && activeRowIndex >= 0) abs(rowIndex - activeRowIndex) else 0
                val targetAlpha = when (focusDistance) {
                    0 -> 1f
                    1 -> 0.72f
                    2 -> 0.46f
                    3 -> 0.27f
                    else -> 0.16f
                }
                val targetBlur = when (focusDistance) {
                    0 -> 0f
                    1 -> 0.8f
                    2 -> 1.8f
                    3 -> 3f
                    else -> 4.2f
                }
                val rowAlpha by animateFloatAsState(targetAlpha, label = "lyricsRowAlpha")
                val rowBlur by animateFloatAsState(targetBlur, label = "lyricsRowBlur")
                val currentTime = positionMs + syncOffsetMs
                val sourceText = row.joinToString(separator = "") { indexed ->
                    indexed.value.words.orEmpty().takeIf { it.isNotEmpty() }
                        ?.joinToString(separator = "") { it.text }
                        ?: indexed.value.text
                }
                val providerRomanization = row.mapNotNull { it.value.romanizedText?.takeIf(String::isNotBlank) }
                    .joinToString(separator = "")
                val romanizedText = remember(sourceText, providerRomanization, showRomanization) {
                    if (!showRomanization) null
                    else providerRomanization.takeIf { it.isNotBlank() }
                        ?: LyricsRomanizer.romanize(sourceText)
                }
                val lyricText = buildAnnotatedString {
                    row.forEach { indexedLine ->
                        val line = indexedLine.value
                        val words = line.words.orEmpty()
                        if (words.isNotEmpty()) {
                            words.forEach { word ->
                                val wordActive = word.startMs <= currentTime && (word.endMs == null || currentTime < word.endMs)
                                withStyle(
                                    SpanStyle(
                                        color = when {
                                            wordActive -> Color.White
                                            active -> Color.White.copy(alpha = .48f)
                                            else -> Color.White.copy(alpha = .83f)
                                        },
                                        fontWeight = if (wordActive) FontWeight.Bold else FontWeight.Medium,
                                    ),
                                ) { append(word.text) }
                            }
                        } else {
                            val lineActive = indexedLine.index == activeIndex && lyrics.isSynced
                            withStyle(
                                SpanStyle(
                                    color = when {
                                        lineActive -> Color.White
                                        lyrics.isSynced -> Color.White.copy(alpha = .48f)
                                        else -> Color.White.copy(alpha = .88f)
                                    },
                                    fontWeight = if (lineActive) FontWeight.Bold else FontWeight.Medium,
                                ),
                            ) { append(line.text) }
                        }
                    }
                }
                val targetSeek = row.firstOrNull { it.value.startMs != null }?.value?.startMs
                Column(
                    modifier = Modifier.fillMaxWidth()
                        .graphicsLayer { alpha = rowAlpha }
                        .blur(rowBlur.dp, edgeTreatment = BlurredEdgeTreatment.Unbounded)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(enabled = targetSeek != null) {
                            targetSeek?.let { onSeek((it - syncOffsetMs).coerceAtLeast(0L)) }
                        }.padding(vertical = 2.dp),
                ) {
                    if (active) {
                        // Karaoke: the sung part of the line fills in as it plays.
                        KaraokeLine(
                            segments = remember(row) { karaokeSegments(row.map { it.value }) },
                            time = { smoothTime.longValue },
                        )
                    } else {
                        Text(
                            text = lyricText,
                            color = Color.White,
                            fontSize = 22.sp,
                            lineHeight = 30.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    romanizedText?.let { romanization ->
                        Text(
                            text = romanization,
                            color = Color.White.copy(alpha = if (active) 0.78f else 0.62f),
                            fontSize = if (active) 16.sp else 14.sp,
                            lineHeight = if (active) 22.sp else 19.sp,
                            fontWeight = FontWeight.Normal,
                            modifier = Modifier.fillMaxWidth().padding(top = 3.dp),
                        )
                    }
                    val translation = row.mapNotNull { it.value.translation?.takeIf(String::isNotBlank) }.joinToString(" ")
                    if (showTranslation && translation.isNotBlank()) {
                        Text(translation, color = Color(0xFFD7D4FF), fontSize = 17.sp, lineHeight = 24.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayerControls(
    state: PlaybackState,
    progress: PlaybackProgress,
    progressFlow: StateFlow<PlaybackProgress>,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var draggingSeek by remember(state.currentTrack?.id) { mutableStateOf(false) }
    var seekValue by remember(state.currentTrack?.id) { mutableFloatStateOf(progress.positionMs.toFloat()) }
    LaunchedEffect(progress.positionMs, draggingSeek) { if (!draggingSeek) seekValue = progress.positionMs.toFloat() }

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        com.moodify.app.ui.designsystem.WavySeekBar(
            progress = progressFlow,
            isPlaying = state.isPlaying,
            accent = MaterialTheme.colorScheme.primary,
            trackColor = Color.White,
            onSeek = onSeek,
        )
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onPrevious, modifier = Modifier.size(60.dp)) { Icon(Icons.Default.SkipPrevious, "Previous track", tint = Color.White, modifier = Modifier.size(34.dp)) }
            IconButton(onClick = onPlayPause, modifier = Modifier.size(72.dp).clip(CircleShape).background(Color.White.copy(alpha = .16f))) {
                Icon(if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, if (state.isPlaying) "Pause" else "Play", tint = Color.White, modifier = Modifier.size(40.dp))
            }
            IconButton(onClick = onNext, modifier = Modifier.size(60.dp)) { Icon(Icons.Default.SkipNext, "Next track", tint = Color.White, modifier = Modifier.size(34.dp)) }
        }
    }
}

private fun LyricsDocument?.asEditableText(): String = this?.lines.orEmpty().joinToString("\n") { line ->
    val start = line.startMs
    if (start == null) line.text else "[${start / 60_000}:${((start % 60_000) / 1_000).toString().padStart(2, '0')}.${((start % 1_000) / 10).toString().padStart(2, '0')}]${line.text}"
}

private fun LyricsDocument?.lyricRows(): List<List<IndexedValue<LyricsLine>>> {
    val rows = mutableListOf<MutableList<IndexedValue<LyricsLine>>>()
    this?.lines?.forEachIndexed { index, line ->
        val previous = rows.lastOrNull()
        if (line.groupId != null && previous?.firstOrNull()?.value?.groupId == line.groupId) {
            previous.add(IndexedValue(index, line))
        } else {
            rows.add(mutableListOf(IndexedValue(index, line)))
        }
    }
    return rows
}

private fun Context.shareLyrics(track: Track, lyrics: LyricsDocument?) {
    val text = lyrics?.plainText?.takeIf(String::isNotBlank) ?: return
    val share = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "${track.title} — ${track.artist}")
        putExtra(Intent.EXTRA_TEXT, "${track.title} — ${track.artist}\n\n$text")
    }
    startActivity(Intent.createChooser(share, "Share lyrics"))
}

private fun formatLyricsTime(ms: Long): String = String.format("%d:%02d", ms.coerceAtLeast(0L) / 60_000, (ms.coerceAtLeast(0L) / 1_000) % 60)
private fun Float.formatOneDecimal(): String = String.format("%.1f", this)

/** A piece of a lyric line with its singing window. */
private data class KaraokeSegment(val text: String, val startMs: Long, val endMs: Long)

/**
 * Splits lines into timed segments: per word when the provider supplies word
 * timings, otherwise the whole line across its start..end window.
 */
private fun karaokeSegments(lines: List<LyricsLine>): List<KaraokeSegment> = buildList {
    lines.forEachIndexed { lineIndex, line ->
        val lineStart = line.startMs ?: return@forEachIndexed
        val lineEnd = line.endMs ?: lines.getOrNull(lineIndex + 1)?.startMs ?: (lineStart + 4_000L)
        val words = line.words.orEmpty()
        if (words.isNotEmpty()) {
            words.forEachIndexed { index, word ->
                val end = word.endMs ?: words.getOrNull(index + 1)?.startMs ?: lineEnd
                add(KaraokeSegment(word.text, word.startMs, end.coerceAtLeast(word.startMs + 1)))
            }
        } else {
            add(KaraokeSegment(line.text, lineStart, lineEnd.coerceAtLeast(lineStart + 1)))
        }
    }
}

/** Character offset (fractional) reached at [timeMs]. */
private fun karaokeProgress(segments: List<KaraokeSegment>, timeMs: Long): Float {
    var offset = 0f
    for (segment in segments) {
        val length = segment.text.length
        when {
            timeMs >= segment.endMs -> offset += length
            timeMs <= segment.startMs -> return offset
            else -> return offset + length * (timeMs - segment.startMs).toFloat() / (segment.endMs - segment.startMs)
        }
    }
    return offset
}

/**
 * The active lyric line, drawn twice: dimmed underneath and bright with a soft
 * glow on top, clipped to the part already sung. The clip is computed in the
 * draw phase from [time], so the fill animates every frame without recomposing.
 */
@Composable
private fun KaraokeLine(segments: List<KaraokeSegment>, time: () -> Long) {
    val text = remember(segments) { segments.joinToString(separator = "") { it.text } }
    var layout by remember { mutableStateOf<androidx.compose.ui.text.TextLayoutResult?>(null) }
    val style = MaterialTheme.typography.headlineSmall.copy(
        fontSize = 28.sp,
        lineHeight = 36.sp,
        fontWeight = FontWeight.Bold,
    )
    Box(Modifier.fillMaxWidth()) {
        Text(
            text = text,
            style = style,
            color = Color.White.copy(alpha = 0.5f),
            onTextLayout = { layout = it },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = text,
            style = style.copy(
                shadow = androidx.compose.ui.graphics.Shadow(
                    color = Color.White.copy(alpha = 0.55f),
                    blurRadius = 18f,
                ),
            ),
            color = Color.White,
            modifier = Modifier
                .fillMaxWidth()
                .drawWithContent {
                    val result = layout ?: return@drawWithContent
                    if (result.layoutInput.text.length != text.length) {
                        drawContent()
                        return@drawWithContent
                    }
                    val progress = karaokeProgress(segments, time())
                    if (progress <= 0f) return@drawWithContent
                    val path = androidx.compose.ui.graphics.Path()
                    for (line in 0 until result.lineCount) {
                        val lineStart = result.getLineStart(line)
                        val lineEnd = result.getLineEnd(line)
                        if (progress <= lineStart) break
                        val right = if (progress >= lineEnd) {
                            result.getLineRight(line)
                        } else {
                            val index = progress.toInt()
                            val fraction = progress - index
                            val x0 = result.getHorizontalPosition(index, usePrimaryDirection = true)
                            val x1 = if (index + 1 <= lineEnd) {
                                result.getHorizontalPosition(index + 1, usePrimaryDirection = true)
                            } else {
                                result.getLineRight(line)
                            }
                            x0 + (x1 - x0) * fraction
                        }
                        val left = result.getLineLeft(line)
                        // Right-to-left scripts fill from the right edge.
                        val rtl = result.getParagraphDirection(lineStart) == androidx.compose.ui.text.style.ResolvedTextDirection.Rtl
                        path.addRect(
                            if (rtl) {
                                androidx.compose.ui.geometry.Rect(right, result.getLineTop(line) - 8f, result.getLineRight(line), result.getLineBottom(line) + 8f)
                            } else {
                                androidx.compose.ui.geometry.Rect(left, result.getLineTop(line) - 8f, right, result.getLineBottom(line) + 8f)
                            },
                        )
                    }
                    clipPath(path) { this@drawWithContent.drawContent() }
                },
        )
    }
}