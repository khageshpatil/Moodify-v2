package com.example.myapplication.data

import com.example.myapplication.BuildConfig
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.IOException
import java.util.concurrent.TimeUnit

class MoodifyApiException(message: String, val statusCode: Int? = null) : IOException(message)

class MusicRepository {

    private val serverBaseUrl = BuildConfig.MOODIFY_SERVER_URL.trimEnd('/')

    private val api = Retrofit.Builder()
        .baseUrl("$serverBaseUrl/")
        .client(
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .addInterceptor(HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.BASIC
                })
                .build()
        )
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(MoodifyServerApi::class.java)

    suspend fun searchSongs(query: String): List<Track> {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isBlank()) return emptyList()

        val response = api.searchSongs(normalizedQuery)
        if (!response.isSuccessful) {
            throw MoodifyApiException(
                "Moodify search failed (${response.code()})",
                response.code()
            )
        }

        val tracks = response.body()
            ?.getAsJsonArray("tracks")
            ?.mapNotNull { it.toTrackOrNull() }
            ?.distinctBy { it.id }
            ?.take(20)
            .orEmpty()

        if (tracks.isEmpty()) throw MoodifyApiException("No songs found for \"$normalizedQuery\"")
        return tracks
    }

    suspend fun getTrendingSongs(): List<Track> = searchSongs("trending")

    // ── JSON helpers ────────────────────────────────────────────────

    private fun JsonElement.stringValue(): String? {
        if (!isJsonPrimitive || !asJsonPrimitive.isString) return null
        return asString.trim().takeIf { it.isNotEmpty() }
    }

    private fun JsonObject.stringValue(name: String): String? = get(name)?.stringValue()

    private fun JsonElement.toTrackOrNull(): Track? {
        if (!isJsonObject) return null
        val json = asJsonObject

        // The Moodify server normalizeTrack emits `id` as the YouTube video ID.
        val id = json.stringValue("id") ?: return null
        val title = json.stringValue("title") ?: return null

        val artist = json.stringValue("artist")
            ?: json.getAsJsonArray("artists")
                ?.firstOrNull()
                ?.asJsonObject
                ?.stringValue("name")
            ?: "Unknown artist"

        val albumObject = json.get("album")?.takeIf { it.isJsonObject }?.asJsonObject
        val album = albumObject?.stringValue("title")
            ?: albumObject?.stringValue("name")
            ?: ""

        // Artwork: the server emits { artwork: { url: "..." } }
        val artwork = json.get("artwork")
            ?.takeIf { it.isJsonObject }
            ?.asJsonObject
            ?.stringValue("url")
            .orEmpty()

        // Duration: server normalizeTrack returns `durationSeconds` (already in seconds).
        // Fall back to `durationMs` divided by 1000 for older server builds.
        val durationSeconds: Int = run {
            val secs = json.get("durationSeconds")
                ?.takeIf { it.isJsonPrimitive }
                ?.asInt
            if (secs != null && secs > 0) return@run secs

            json.get("durationMs")
                ?.takeIf { it.isJsonPrimitive }
                ?.asLong
                ?.div(1000L)
                ?.toInt()
                ?: 0
        }

        return Track(
            id = id,
            title = title,
            artist = artist,
            album = album,
            albumArtUrl = artwork,
            durationSeconds = durationSeconds,
            // Playback uses the YouTube video ID via the IFrame player.
            audioUrl = ""
        )
    }
}
