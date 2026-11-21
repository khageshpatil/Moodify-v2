package com.moodify.app.data

import java.net.URI
import java.net.URLDecoder

/** Something a link to Moodify itself points at. */
sealed interface MoodifyLink {
    /** A friend's invite; [code] is as written in the link (not yet normalised). */
    data class AddFriend(val code: String) : MoodifyLink
    data class Room(val roomId: String) : MoodifyLink
    /** A message notification: open the conversation with [code]. */
    data class Chat(val code: String) : MoodifyLink
    /** A browser's QR code asking to link to this phone. */
    data class LinkDevice(val linkId: String, val key: String?) : MoodifyLink
}

/**
 * Invites and rooms are shared as https links to the web app, so they work for
 * people without Moodify. Android opens them straight in Moodify when it's
 * installed (App Links, verified by /.well-known/assetlinks.json on [WEB]).
 * moodify:// links still work: older versions shared them, and notifications
 * and the browser-linking QR use them.
 */
object MoodifyLinks {
    const val WEB = "https://moodify-v2-two.vercel.app"
    private const val WEB_HOST = "moodify-v2-two.vercel.app"
    private val FRIEND_LINK = Regex(
        """(?:moodify://add/|https://${Regex.escape(WEB_HOST)}/add/)([A-Za-z0-9-]+)""",
        RegexOption.IGNORE_CASE,
    )

    fun addFriend(code: String) = "$WEB/add/$code"
    fun room(roomId: String) = "$WEB/room/$roomId"

    fun parse(link: String?): MoodifyLink? {
        val uri = runCatching { URI(link?.trim().orEmpty()) }.getOrNull() ?: return null
        val web = uri.scheme.equals("https", ignoreCase = true) && uri.host.equals(WEB_HOST, ignoreCase = true)
        // moodify://add/CODE names the kind as the host; on the web it's the first path segment.
        val parts = when {
            uri.scheme.equals("moodify", ignoreCase = true) -> listOfNotNull(uri.host) + segments(uri.path)
            web -> segments(uri.path)
            else -> return null
        }
        val id = parts.getOrNull(1) ?: return null
        return when (parts.first().lowercase()) {
            "add" -> MoodifyLink.AddFriend(id)
            "room" -> MoodifyLink.Room(id)
            // Only the app makes these, so they never come as web links.
            "chat" -> if (web) null else MoodifyLink.Chat(id)
            "link" -> if (web) null else MoodifyLink.LinkDevice(id, query(uri.rawQuery)["k"])
            else -> null
        }
    }

    /** The code in a friend link somewhere in [text] (a scanned QR, pasted text), or null. */
    fun friendCodeIn(text: String): String? = FRIEND_LINK.find(text)?.groupValues?.get(1)

    private fun segments(path: String?): List<String> = path.orEmpty().split('/').filter(String::isNotBlank)

    private fun query(raw: String?): Map<String, String> =
        raw.orEmpty().split('&').mapNotNull { pair ->
            val key = pair.substringBefore('=', "").takeIf(String::isNotBlank) ?: return@mapNotNull null
            key to runCatching { URLDecoder.decode(pair.substringAfter('='), "UTF-8") }.getOrDefault("")
        }.toMap()
}
