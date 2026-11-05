package com.moodify.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

/**
 * Device-side lyrics provider. It is deliberately behind a small interface
 * boundary so the catalog/playback provider can change without coupling the
 * UI to a server DTO. LRCLIB returns plain and timestamped lyrics without
 * requiring an account. Open metadata and video-ID providers are queried as
 * device-side fallbacks; each provider can fail independently.
 */
class NativeLyricsRepository(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .build(),
) {
    suspend fun get(track: Track): LyricsDocument? = withContext(Dispatchers.IO) {
        val exactUrl = "https://lrclib.net/api/get".toHttpUrl().newBuilder()
            .addQueryParameter("track_name", track.title)
            .addQueryParameter("artist_name", track.artist)
            .apply { track.album.takeIf { it.isNotBlank() }?.let { addQueryParameter("album_name", it) } }
            .apply { track.durationSeconds.takeIf { it > 0 }?.let { addQueryParameter("duration", it.toString()) } }
            .build()
        val exactMatch = runCatching { requestObject(exactUrl)?.let { parseDocument(it) } }
            .getOrNull()
        // Word-timed lyrics are the highest quality result, so they can safely
        // short-circuit. For line-timed lyrics, check the other providers too:
        // one may have word-level timing for the same recording.
        if (exactMatch?.let(::lyricsQuality) == WORD_SYNCED_QUALITY) return@withContext exactMatch

        // YouTube often decorates a canonical title with "slowed", "live", or
        // "remix" suffixes. LRCLIB's exact endpoint correctly returns 404 for
        // those variants even when the canonical song has lyrics. Search all
        // providers and prefer a close-matching synced result over plain text.
        val candidates = search(track.title, track.artist, track.album, track.durationSeconds, track.id)
        // LRCLIB's exact endpoint already matched title, artist and length (±2 s),
        // so it competes as a fully trusted candidate rather than a last resort.
        val exactCandidate = exactMatch?.let {
            LyricsSearchResult(track.title, track.artist, track.album, track.durationSeconds, it)
        }
        selectBestSearchResult(track, listOfNotNull(exactCandidate) + candidates)?.document ?: exactMatch
    }

    suspend fun search(
        title: String,
        artist: String,
        album: String = "",
        durationSeconds: Int = 0,
        videoId: String? = null,
    ): List<LyricsSearchResult> = withContext(Dispatchers.IO) {
        coroutineScope {
            listOf(
                async { safeSearch { searchBetterLyrics(title, artist, album, durationSeconds) } },
                async { safeSearch { searchLrclib(title, artist, durationSeconds) } },
                async { safeSearch { searchSimpMusic(videoId, title, artist, durationSeconds) } },
                async { safeSearch { searchUnison(title, artist, album, durationSeconds, videoId) } },
                async { safeSearch { searchYouLyPlus(title, artist, album, durationSeconds) } },
            ).awaitAll()
                .flatten()
                .distinctBy { "${it.document.source}:${it.title.lowercase()}:${it.artist.lowercase()}:${it.document.plainText.hashCode()}" }
                .sortedByDescending { result ->
                    lyricsResultRank(
                        Track("", title, artist, album, durationSeconds = durationSeconds),
                        result,
                    )
                }
            }
    }

    internal fun selectBestSearchResult(
        track: Track,
        candidates: List<LyricsSearchResult>,
    ): LyricsSearchResult? = candidates
        .filter { matchScore(track, it.title, it.artist) > 0 }
        .map { it.withTrustworthyTiming(track) }
        .maxByOrNull { lyricsResultRank(track, it) }

    /**
     * Keeps a result's timestamps only when it was timed against a recording of
     * the same length; otherwise the words are still right but the timing would
     * drift, so it is shown as plain (unsynced) lyrics instead.
     */
    private fun LyricsSearchResult.withTrustworthyTiming(track: Track): LyricsSearchResult {
        val lengthDiffers = track.durationSeconds > 0 && durationSeconds > 0 &&
            kotlin.math.abs(track.durationSeconds - durationSeconds) > MAX_SYNC_LENGTH_DIFFERENCE_SECONDS
        if (!document.isSynced || !lengthDiffers) return this
        return copy(
            document = document.copy(
                lines = document.lines.map { it.copy(startMs = null, endMs = null, words = null) },
                isSynced = false,
            ),
        )
    }

    private fun lyricsResultRank(track: Track, result: LyricsSearchResult): Int {
        val titleArtistScore = matchScore(track, result.title, result.artist)
        val timingPreference = when (lyricsQuality(result.document)) {
            WORD_SYNCED_QUALITY -> WORD_SYNCED_LYRICS_RANK_BONUS
            LINE_SYNCED_QUALITY -> SYNCED_LYRICS_RANK_BONUS
            else -> 0
        }
        val durationDifference = if (track.durationSeconds > 0 && result.durationSeconds > 0) {
            kotlin.math.abs(track.durationSeconds - result.durationSeconds).coerceAtMost(DURATION_RANK_PENALTY_CAP_SECONDS)
        } else {
            0
        }
        return titleArtistScore * LYRICS_MATCH_SCORE_WEIGHT + timingPreference - durationDifference
    }

    private fun lyricsQuality(document: LyricsDocument): Int = when {
        document.lines.any { !it.words.isNullOrEmpty() } -> WORD_SYNCED_QUALITY
        document.isSynced -> LINE_SYNCED_QUALITY
        else -> PLAIN_LYRICS_QUALITY
    }

    private suspend fun searchLrclib(title: String, artist: String, durationSeconds: Int): List<LyricsSearchResult> {
        // YouTube Music's "album" metadata is often an artist/credit string,
        // while LRCLIB indexes the release or single name. Filtering by that
        // field drops valid synced matches, so search by title/artist and rank
        // the returned candidates by duration below.
        val url = buildLrclibSearchUrl(title, artist)
        return requestArray(url)
            .filter(::hasUsableLyrics)
            .mapNotNull { root ->
                val document = parseDocument(root) ?: return@mapNotNull null
                LyricsSearchResult(
                    title = root.optString("trackName", title),
                    artist = root.optString("artistName", artist),
                    album = root.optString("albumName"),
                    durationSeconds = root.optInt("duration", durationSeconds),
                    document = document,
                )
            }
    }

    private fun searchBetterLyrics(title: String, artist: String, album: String, durationSeconds: Int): List<LyricsSearchResult> {
        val endpoints = listOf(
            "getLyrics" to "BetterLyrics",
            "kugou/getLyrics" to "BetterLyrics · KuGou",
        )
        for ((path, source) in endpoints) {
            val url = "https://lyrics-api.boidu.dev/$path".toHttpUrl().newBuilder()
                .addQueryParameter("s", title)
                .addQueryParameter("a", artist)
                .apply { album.takeIf(String::isNotBlank)?.let { addQueryParameter("al", it) } }
                .apply { durationSeconds.takeIf { it > 0 }?.let { addQueryParameter("d", it.toString()) } }
                .build()
            val payload = requestText(url) ?: continue
            parseLyricsPayload(payload, source)?.let { document ->
                return listOf(LyricsSearchResult(title, artist, album, durationSeconds, document))
            }
        }
        return emptyList()
    }

    private fun parseLyricsPayload(payload: String, source: String): LyricsDocument? {
        val raw = payload.trim().removePrefix("\uFEFF")
        val isJsonObject = runCatching { JSONObject(raw) }.isSuccess
        val isJsonArray = raw.startsWith("[") && runCatching { JSONArray(raw) }.isSuccess
        if (!isJsonObject && !isJsonArray) {
            LyricsParser.parse(raw, source, detectLyricsFormat(raw))?.let { return it }
        }
        val root = runCatching { JSONObject(raw) }.getOrNull()
        val keys = listOf("ttml", "lyrics", "data", "result", "response", "content", "plainLyrics", "syncedLyrics", "lrc", "text")
        for (key in keys) {
            val value = root?.opt(key) ?: continue
            val candidate = when (value) {
                is JSONObject -> value.optString("lyrics").takeIf(String::isNotBlank)
                    ?: value.optString("ttml").takeIf(String::isNotBlank)
                    ?: value.optString("content").takeIf(String::isNotBlank)
                is JSONArray -> buildString {
                    for (index in 0 until value.length()) {
                        val item = value.optJSONObject(index)
                        append(item?.optString("text") ?: value.optString(index))
                        if (index < value.length() - 1) append('\n')
                    }
                }.takeIf(String::isNotBlank)
                else -> value.toString().takeIf { it.isNotBlank() && it != "null" }
            } ?: continue
            LyricsParser.parse(candidate, source, detectLyricsFormat(candidate))?.let { return it }
        }
        return null
    }

    private fun searchSimpMusic(
        videoId: String?,
        title: String,
        artist: String,
        durationSeconds: Int,
    ): List<LyricsSearchResult> {
        val id = videoId?.takeIf(String::isNotBlank) ?: return emptyList()
        val root = requestObject("https://api-lyrics.simpmusic.org/v1/$id".toHttpUrl()) ?: return emptyList()
        if (root.optString("type") != "success") return emptyList()
        val data = root.optJSONArray("data") ?: return emptyList()
        return buildList {
            for (index in 0 until data.length()) {
                val item = data.optJSONObject(index) ?: continue
                val synced = item.optString("syncedLyrics").takeIf(String::isNotBlank)
                val plain = item.optString("plainLyric").takeIf(String::isNotBlank)
                    ?: item.optString("plainLyrics").takeIf(String::isNotBlank)
                val raw = synced ?: plain ?: continue
                val parsed = LyricsParser.parse(raw, source = "SimpMusic", format = detectLyricsFormat(raw)) ?: continue
                add(
                    LyricsSearchResult(
                        title = item.optString("songTitle", title),
                        artist = item.optString("artistName", artist),
                        album = item.optString("albumName"),
                        durationSeconds = item.optInt("durationSeconds", durationSeconds),
                        document = parsed,
                    ),
                )
            }
        }
    }

    private fun searchUnison(title: String, artist: String, album: String, durationSeconds: Int, videoId: String?): List<LyricsSearchResult> {
        val url = "https://unison.boidu.dev/lyrics/search".toHttpUrl().newBuilder()
            .addQueryParameter("song", title)
            .addQueryParameter("artist", artist)
            .apply { album.takeIf(String::isNotBlank)?.let { addQueryParameter("album", it) } }
            .apply { durationSeconds.takeIf { it > 0 }?.let { addQueryParameter("duration", it.toString()) } }
            .addQueryParameter("limit", "5")
            .build()
        val result = requestObject(url)?.let { root ->
            val entries = root.optJSONArray("data")
            if (!root.optBoolean("success") || entries == null) emptyList() else buildList {
                for (index in 0 until entries.length()) {
                    val entry = entries.optJSONObject(index) ?: continue
                    val raw = entry.optString("lyrics").takeIf(String::isNotBlank) ?: continue
                    val parsed = LyricsParser.parse(raw, source = "Unison", format = entry.optString("format")) ?: continue
                    add(LyricsSearchResult(
                        title = entry.optString("song", title),
                        artist = entry.optString("artist", artist),
                        album = album,
                        durationSeconds = durationSeconds,
                        document = parsed,
                    ))
                }
            }
        }.orEmpty()
        if (result.isNotEmpty()) return result
        val id = videoId?.takeIf(String::isNotBlank) ?: return emptyList()
        val byVideoId = "https://unison.boidu.dev/lyrics".toHttpUrl().newBuilder()
            .addQueryParameter("v", id)
            .build()
        val data = requestObject(byVideoId)?.optJSONObject("data") ?: return emptyList()
        val raw = data.optString("lyrics").takeIf(String::isNotBlank) ?: return emptyList()
        val document = LyricsParser.parse(raw, source = "Unison", format = data.optString("format")) ?: return emptyList()
        return listOf(LyricsSearchResult(title, artist, album, durationSeconds, document))
    }

    private fun searchYouLyPlus(title: String, artist: String, album: String, durationSeconds: Int): List<LyricsSearchResult> {
        for (base in YOULYPLUS_MIRRORS.take(2)) {
            val ttmlUrl = lyricsRequestUrl(base, "v1/ttml/get", title, artist, album, durationSeconds)
            val ttmlBody = requestText(ttmlUrl)
            val ttml = when {
                ttmlBody.isNullOrBlank() -> null
                ttmlBody.trimStart().startsWith("<") -> ttmlBody
                else -> runCatching { JSONObject(ttmlBody).optString("ttml") }.getOrNull()
            }
            if (!ttml.isNullOrBlank()) {
                LyricsParser.parse(ttml, source = "YouLyPlus", format = "ttml")?.let { document ->
                    return listOf(LyricsSearchResult(title, artist, album, durationSeconds, document))
                }
            }

            val response = requestText(lyricsRequestUrl(base, "v2/lyrics/get", title, artist, album, durationSeconds))
                ?.let { runCatching { JSONObject(it) }.getOrNull() } ?: continue
            val lines = response.optJSONArray("lyrics") ?: continue
                val parsedLines = buildList {
                for (index in 0 until lines.length()) {
                    val line = lines.optJSONObject(index) ?: continue
                    val text = line.optString("text").takeIf(String::isNotBlank) ?: continue
                    val start = line.optLong("time", -1L).takeIf { it >= 0L }
                    val duration = line.optLong("duration", -1L).takeIf { it > 0L }
                        val syllables = line.optJSONArray("syllabus")?.let { syllableArray ->
                            buildList {
                                for (wordIndex in 0 until syllableArray.length()) {
                                    val word = syllableArray.optJSONObject(wordIndex) ?: continue
                                    val wordText = word.optString("text").takeIf(String::isNotBlank) ?: continue
                                    val wordStart = word.optLong("time", -1L).takeIf { it >= 0L } ?: continue
                                    val wordDuration = word.optLong("duration", -1L).takeIf { it > 0L }
                                    add(LyricsWord(wordText, wordStart, wordDuration?.let { wordStart + it }))
                                }
                            }
                        }.orEmpty()
                        add(LyricsLine(text, start, start?.let { duration?.plus(it) }, words = syllables))
                }
            }
            if (parsedLines.isNotEmpty()) {
                return listOf(
                    LyricsSearchResult(
                        title,
                        artist,
                        album,
                        durationSeconds,
                        LyricsDocument(parsedLines, source = "YouLyPlus", isSynced = parsedLines.any { it.startMs != null }),
                    ),
                )
            }
        }
        return emptyList()
    }

    private suspend fun safeSearch(fetch: suspend () -> List<LyricsSearchResult>): List<LyricsSearchResult> = try {
        fetch()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        emptyList()
    }

    private fun lyricsRequestUrl(base: String, path: String, title: String, artist: String, album: String, durationSeconds: Int) =
        "$base$path".toHttpUrl().newBuilder()
            .addQueryParameter("title", title)
            .addQueryParameter("artist", artist)
            .apply { album.takeIf(String::isNotBlank)?.let { addQueryParameter("album", it) } }
            .apply { durationSeconds.takeIf { it > 0 }?.let { addQueryParameter("duration", it.toString()) } }
            .build()

    private fun detectLyricsFormat(raw: String): String? = when {
        raw.contains("<tt", ignoreCase = true) -> "ttml"
        Regex("\\[\\d+,\\d+]\\(").containsMatchIn(raw) -> "qrc"
        else -> "lrc"
    }

    private fun requestText(url: okhttp3.HttpUrl): String? {
        httpClient.newCall(request(url)).execute().use { response ->
            if (!response.isSuccessful) return null
            return response.body?.string()?.takeIf(String::isNotBlank)
        }
    }

    /** Translate in bounded batches while keeping the original timed lines intact. */
    suspend fun translate(document: LyricsDocument, targetLanguage: String): LyricsDocument = withContext(Dispatchers.IO) {
        val lang = targetLanguage.trim().lowercase().take(8)
        require(lang.matches(Regex("[a-z]{2,3}(-[a-z0-9]{2,8})?"))) { "Enter a valid language code, such as es or hi." }
        val translated = document.lines.toMutableList()
        document.lines.indices.chunked(8).forEach { batchIndices ->
            val delimiter = "\n\uE000MOODIFY_LYRICS_BOUNDARY\uE001\n"
            val input = batchIndices.joinToString(delimiter) { document.lines[it].text }
            val output = translateText(input, lang)
            val pieces = output.split(delimiter)
            if (pieces.size == batchIndices.size) {
                batchIndices.forEachIndexed { index, lineIndex ->
                    translated[lineIndex] = translated[lineIndex].copy(translation = pieces[index].trim())
                }
            } else if (batchIndices.size == 1) {
                val index = batchIndices.single()
                translated[index] = translated[index].copy(translation = output.trim())
            } else {
                // If the translation service altered our separator, fall back to individual lines.
                batchIndices.forEach { lineIndex ->
                    translated[lineIndex] = translated[lineIndex].copy(
                        translation = translateText(document.lines[lineIndex].text, lang).trim(),
                    )
                }
            }
        }
        document.copy(lines = translated, source = "${document.source} · translated")
    }

    private fun translateText(text: String, language: String): String {
        val form = FormBody.Builder()
            .add("client", "at")
            .add("dt", "t")
            .add("ie", "UTF-8")
            .add("oe", "UTF-8")
            .add("sl", "auto")
            .add("tl", language)
            .add("q", text)
            .build()
        val request = Request.Builder()
            .url("https://translate.googleapis.com/translate_a/single")
            .header("User-Agent", "Moodify-Android/1.0 (lyrics)")
            .post(form)
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Translation service HTTP ${response.code}")
            val segments = JSONArray(response.body?.string().orEmpty()).optJSONArray(0)
                ?: throw IOException("Translation service returned no translated text")
            return buildString {
                for (index in 0 until segments.length()) {
                    append(segments.optJSONArray(index)?.optString(0).orEmpty())
                }
            }.takeIf(String::isNotBlank) ?: throw IOException("Translation service returned empty text")
        }
    }

    private fun requestObject(url: okhttp3.HttpUrl): JSONObject? {
        val request = request(url)
        httpClient.newCall(request).execute().use { response ->
            if (response.code == 404) return null
            if (!response.isSuccessful) throw IOException("Lyrics provider HTTP ${response.code}")
            return JSONObject(response.body?.string().orEmpty())
        }
    }

    private fun requestArray(url: okhttp3.HttpUrl): List<JSONObject> {
        val request = request(url)
        httpClient.newCall(request).execute().use { response ->
            if (response.code == 404) return emptyList()
            if (!response.isSuccessful) throw IOException("Lyrics provider HTTP ${response.code}")
            val array = JSONArray(response.body?.string().orEmpty())
            return buildList(array.length()) {
                for (index in 0 until array.length()) {
                    array.optJSONObject(index)?.let(::add)
                }
            }
        }
    }

    private fun request(url: okhttp3.HttpUrl): Request = Request.Builder()
        .url(url)
        .header("Accept", "application/json")
        .header("User-Agent", "Moodify-Android/1.0 (lyrics)")
        .build()

    private fun parseDocument(root: JSONObject): LyricsDocument? {
        val synced = root.optString("syncedLyrics").takeIf { it.isNotBlank() }
        val plain = root.optString("plainLyrics").takeIf { it.isNotBlank() }
        if (listOfNotNull(synced, plain).all(::isPlaceholderLyrics)) return null
        return when {
            synced != null -> LyricsParser.parse(synced, source = "LRCLIB", format = "lrc")
            plain != null -> LyricsParser.parse(plain, source = "LRCLIB")
            else -> null
        }
    }

    private fun hasUsableLyrics(root: JSONObject): Boolean =
        listOf(root.optString("syncedLyrics"), root.optString("plainLyrics"))
            .any { it.isNotBlank() && !isPlaceholderLyrics(it) }

    private fun isPlaceholderLyrics(value: String): Boolean = value
        .replace(Regex("\\[\\d{1,3}:\\d{2}(?:[.:]\\d{1,3})?]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .equals("probe", ignoreCase = true)

    /**
     * How well a provider's title/artist matches the song; 0 means "not this song".
     * The title must match: the same artist's other songs have the wrong lyrics.
     */
    private fun matchScore(track: Track, titleValue: String, artistValue: String): Int {
        val title = normalize(titleValue)
        val requestedTitle = normalize(track.title)
        // An empty normalised title (e.g. only punctuation) must never match everything.
        if (title.isEmpty() || requestedTitle.isEmpty()) return 0
        val titleScore = when {
            title == requestedTitle -> 5
            " $title ".contains(" $requestedTitle ") || " $requestedTitle ".contains(" $title ") -> 3
            else -> return 0
        }
        return titleScore + artistScore(track.artist, artistValue)
    }

    /** Credits are compared as sets: "A, B & C" matches "C/A/B" and "A, B, C and D". */
    private fun artistScore(requested: String, found: String): Int {
        val requestedNames = artistNames(requested)
        val foundNames = artistNames(found)
        if (requestedNames.isEmpty() || foundNames.isEmpty()) return 0
        return when {
            requestedNames == foundNames -> 4
            requestedNames.any { it in foundNames } -> 3
            normalize(found).contains(normalize(requested)) || normalize(requested).contains(normalize(found)) -> 2
            else -> 0
        }
    }

    private fun artistNames(value: String): Set<String> = value.lowercase()
        .split(ARTIST_SEPARATORS)
        .map(::normalize)
        .filter(String::isNotBlank)
        .toSet()

    /**
     * Comparable form of a title or name: lower case, Latin accents folded,
     * bracketed extras ("(From …)", "[Live]") dropped. Letters of every script
     * are kept, so Hindi or Punjabi titles stay comparable instead of collapsing
     * to an empty string.
     */
    private fun normalize(value: String): String = java.text.Normalizer
        .normalize(value.lowercase(), java.text.Normalizer.Form.NFD)
        .replace(LATIN_ACCENTS, "")
        .replace(Regex("\\([^)]*\\)|\\[[^]]*]"), " ")
        .replace(Regex("[^\\p{L}\\p{N}\\p{M}]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")

    private companion object {
        const val LYRICS_MATCH_SCORE_WEIGHT = 100
        const val SYNCED_LYRICS_RANK_BONUS = 250
        const val WORD_SYNCED_LYRICS_RANK_BONUS = 500
        const val DURATION_RANK_PENALTY_CAP_SECONDS = 25
        /**
         * Timings from a recording whose length differs by more than this are
         * for a different version (video cut, radio edit, slowed…) and drift.
         */
        const val MAX_SYNC_LENGTH_DIFFERENCE_SECONDS = 3
        val LATIN_ACCENTS = Regex("[\\u0300-\\u036f]")
        val ARTIST_SEPARATORS = Regex("\\s*(?:,|&|/|;|\\||\\bfeat\\.?|\\bft\\.?|\\band\\b|\\bwith\\b|\\bx\\b)\\s*")
        const val PLAIN_LYRICS_QUALITY = 0
        const val LINE_SYNCED_QUALITY = 1
        const val WORD_SYNCED_QUALITY = 2

        val YOULYPLUS_MIRRORS = listOf(
            "https://lyricsplus.binimum.org/",
            "https://lyricsplus.prjktla.my.id/",
            "https://lyricsplus.prjktla.workers.dev/",
            "https://lyricsplus.atomix.one/",
            "https://lyricsplus-seven.vercel.app/",
        )
    }
}

internal fun buildLrclibSearchUrl(title: String, artist: String) =
    "https://lrclib.net/api/search".toHttpUrl().newBuilder()
        .addQueryParameter("track_name", title)
        .addQueryParameter("artist_name", artist)
        .build()

data class LyricsSearchResult(
    val title: String,
    val artist: String,
    val album: String,
    val durationSeconds: Int,
    val document: LyricsDocument,
)
