package com.moodify.app.data

data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val albumArtUrl: String = "",
    val durationSeconds: Int = 0,
    val audioUrl: String = "",
    val isFavorite: Boolean = false,
    val explicit: Boolean = false,
    /**
     * Browse ids of the song's (first) artist and album, when the catalogue
     * linked them. Nullable because Gson leaves fields missing from older
     * stored JSON as null.
     */
    val artistId: String? = null,
    val albumId: String? = null,
)

private val CREDIT_SEPARATORS = Regex("""\s*(?:,|&|/|;|\bfeat\.?|\bft\.?|\bx\b|\band\b|\bwith\b)\s*""", RegexOption.IGNORE_CASE)

/** The first name in an artist credit: "Arijit Singh, Pritam & Amitabh" -> "Arijit Singh". */
fun primaryArtist(credit: String): String =
    credit.split(CREDIT_SEPARATORS).map(String::trim).firstOrNull(String::isNotBlank) ?: credit.trim()
