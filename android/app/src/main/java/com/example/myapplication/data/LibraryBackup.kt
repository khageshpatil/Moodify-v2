package com.moodify.app.data

import com.google.gson.Gson
import com.google.gson.JsonParseException

/**
 * Everything worth keeping when moving to a new phone: playlists, likes and
 * saved albums/playlists/artists. Plain JSON so it stays readable.
 */
data class LibraryBackup(
    val format: String = FORMAT,
    val version: Int = VERSION,
    val exportedAtMs: Long = System.currentTimeMillis(),
    val playlists: List<BackupPlaylist> = emptyList(),
    val likedSongs: List<Track> = emptyList(),
    val saved: List<CatalogItem> = emptyList(),
) {
    data class BackupPlaylist(val name: String, val isPinned: Boolean = false, val tracks: List<Track> = emptyList())

    fun toJson(): String = Gson().toJson(this)

    companion object {
        const val FORMAT = "moodify-library"
        const val VERSION = 1

        /** Parses a backup; null when the file isn't a Moodify backup (or is newer than this app). */
        fun fromJson(json: String): LibraryBackup? = try {
            // Check the raw file: Gson would fill a missing "format" with this class's default.
            val raw = com.google.gson.JsonParser.parseString(json).takeIf { it.isJsonObject }?.asJsonObject
            val declaredFormat = raw?.get("format")?.takeIf { it.isJsonPrimitive }?.asString
            val declaredVersion = raw?.get("version")?.takeIf { it.isJsonPrimitive }?.asInt
            if (declaredFormat != FORMAT || declaredVersion == null || declaredVersion !in 1..VERSION) return null
            Gson().fromJson(raw, LibraryBackup::class.java)
                ?.let { backup ->
                    // Gson leaves missing lists null; normalise so callers can iterate safely.
                    @Suppress("SENSELESS_COMPARISON")
                    backup.copy(
                        playlists = (backup.playlists ?: emptyList()).filter { it.name != null }
                            .map { it.copy(tracks = (it.tracks ?: emptyList()).filter(::isValidTrack)) },
                        likedSongs = (backup.likedSongs ?: emptyList()).filter(::isValidTrack),
                        saved = (backup.saved ?: emptyList()).filter { it.id != null && it.title != null },
                    )
                }
        } catch (e: JsonParseException) {
            null
        } catch (e: IllegalStateException) {
            null
        } catch (e: NumberFormatException) {
            null
        }

        @Suppress("SENSELESS_COMPARISON")
        private fun isValidTrack(track: Track?): Boolean =
            track != null && track.id != null && track.title != null && track.artist != null
    }
}
