package com.moodify.app.data.social

import com.moodify.app.data.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** A signed-in session with the moodify-social server. */
data class SocialSession(val token: String, val expiresAtSeconds: Long, val code: String, val displayName: String, val avatar: ProfileAvatar? = null)

/** A profile picture without a photo: an emoji on a colour, or a "profile song" whose cover is the picture. */
sealed interface ProfileAvatar {
    data class Emoji(val emoji: String, val color: Int) : ProfileAvatar
    data class Song(val id: String, val title: String, val artist: String, val artworkUrl: String) : ProfileAvatar

    fun toJson(): JSONObject = when (this) {
        is Emoji -> JSONObject().put("kind", "emoji").put("emoji", emoji).put("color", color)
        is Song -> JSONObject().put("kind", "song").put("id", id).put("title", title).put("artist", artist).put("artworkUrl", artworkUrl)
    }

    companion object {
        fun fromJson(json: JSONObject?): ProfileAvatar? = when (json?.optString("kind")) {
            "emoji" -> json.optString("emoji").takeIf(String::isNotEmpty)?.let { Emoji(it, json.optInt("color", 0)) }
            "song" -> json.optString("artworkUrl").takeIf { it.startsWith("https://") }?.let {
                Song(json.optString("id"), json.optString("title"), json.optString("artist"), it)
            }
            else -> null
        }

        fun parse(text: String?): ProfileAvatar? = text?.let { runCatching { fromJson(JSONObject(it)) }.getOrNull() }
    }
}

class SocialException(val errorCode: String, message: String, val status: Int = 0) : Exception(message)

/** Someone on Moodify, as the server describes them. */
data class SocialUser(
    val code: String,
    val displayName: String,
    val boxPub: ByteArray,
    val since: Long = 0,
    val note: String? = null,
    val avatar: ProfileAvatar? = null,
) {
    override fun equals(other: Any?) = other is SocialUser && other.code == code && other.displayName == displayName && other.since == since && other.note == note && other.avatar == avatar
    override fun hashCode() = code.hashCode()
}

data class FriendLists(
    val friends: List<SocialUser> = emptyList(),
    val incoming: List<SocialUser> = emptyList(),
    val outgoing: List<SocialUser> = emptyList(),
)

/**
 * An encrypted message waiting in the inbox. [to] is set on copies of your
 * own messages (sent from another of your devices): who they went to.
 */
data class InboxMessage(val id: Long, val from: String, val nonce: ByteArray, val ciphertext: ByteArray, val sentAtSeconds: Long, val to: String? = null)

/** HTTP client for moodify-social (protocol: docs/social-protocol/identity.md). */
class SocialClient(
    private val baseUrl: String,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build(),
) {
    val isConfigured: Boolean get() = baseUrl.isNotBlank()

    /** Creates the account (or, for an identity the server already has, just signs in). */
    suspend fun register(identity: MoodifyIdentity, displayName: String): SocialSession {
        val challenge = challenge()
        val message = "moodify-register:v1:$challenge:${identity.signPubText}:${identity.boxPubText}:$displayName"
        val body = JSONObject()
            .put("challenge", challenge)
            .put("signPub", identity.signPubText)
            .put("boxPub", identity.boxPubText)
            .put("displayName", displayName)
            .put("signature", identity.signText(message))
        return session(post("/v1/accounts", body))
    }

    /** Signs in; null when the server has no account for this identity (register instead). */
    suspend fun login(identity: MoodifyIdentity): SocialSession? {
        val challenge = challenge()
        val body = JSONObject()
            .put("challenge", challenge)
            .put("signPub", identity.signPubText)
            .put("signature", identity.signText("moodify-login:v1:$challenge"))
        return try {
            session(post("/v1/auth/verify", body))
        } catch (e: SocialException) {
            if (e.status == 404) null else throw e
        }
    }

    suspend fun rename(token: String, displayName: String): String =
        call("PATCH", "/v1/me", JSONObject().put("displayName", displayName), token)
            .getJSONObject("account").getString("displayName")

    /** Sets (or, with null, removes) your picture; returns what the server kept. */
    suspend fun setAvatar(token: String, avatar: ProfileAvatar?): ProfileAvatar? =
        call("PATCH", "/v1/me", JSONObject().put("avatar", avatar?.toJson() ?: JSONObject.NULL), token)
            .getJSONObject("account").let { ProfileAvatar.fromJson(it.optJSONObject("avatar")) }

    suspend fun deleteAccount(token: String) {
        call("DELETE", "/v1/me", null, token)
    }

    // ── Friends (M2) ─────────────────────────────────────────────────────────

    suspend fun friends(token: String): FriendLists {
        val json = call("GET", "/v1/friends", null, token)
        fun list(key: String) = json.optJSONArray(key)?.let { array ->
            (0 until array.length()).map { user(array.getJSONObject(it)) }
        }.orEmpty()
        return FriendLists(list("friends"), list("incoming"), list("outgoing"))
    }

    /** Returns "friends" or "pending" (also for requests the server quietly drops). */
    suspend fun requestFriend(token: String, code: String, note: String?): Pair<String, SocialUser> {
        val body = JSONObject().put("code", code).apply { note?.takeIf(String::isNotBlank)?.let { put("note", it) } }
        val json = call("POST", "/v1/friends/requests", body, token)
        return json.getString("status") to user(json.getJSONObject("user"))
    }

    suspend fun answerRequest(token: String, code: String, accept: Boolean) {
        call("POST", "/v1/friends/requests/$code/${if (accept) "accept" else "ignore"}", JSONObject(), token)
    }

    suspend fun removeFriend(token: String, code: String) {
        call("DELETE", "/v1/friends/$code", null, token)
    }

    suspend fun block(token: String, code: String) {
        call("PUT", "/v1/blocks/$code", JSONObject(), token)
    }

    suspend fun report(token: String, code: String, reason: String, evidence: String?) {
        call("POST", "/v1/reports", JSONObject().put("code", code).put("reason", reason).put("evidence", evidence), token)
    }

    // ── Encrypted inbox ──────────────────────────────────────────────────────

    suspend fun sendMessage(token: String, to: String, sealed: SealedMessage) {
        call(
            "POST", "/v1/messages",
            JSONObject().put("to", to)
                .put("nonce", MoodifyIdentity.base64Url(sealed.nonce))
                .put("ciphertext", MoodifyIdentity.base64Url(sealed.ciphertext)),
            token,
        )
    }

    suspend fun inbox(token: String, after: Long = 0): List<InboxMessage> {
        val array = call("GET", "/v1/messages?after=$after", null, token).optJSONArray("messages") ?: return emptyList()
        return (0 until array.length()).map { index ->
            val m = array.getJSONObject(index)
            InboxMessage(
                id = m.getLong("id"),
                from = m.getString("from"),
                nonce = fromBase64Url(m.getString("nonce")),
                ciphertext = fromBase64Url(m.getString("ciphertext")),
                sentAtSeconds = m.getLong("sentAt"),
                to = m.optString("to").takeIf { m.has("to") && it.isNotBlank() },
            )
        }
    }

    /** Confirms this device has everything up to [upTo] (each device keeps its own place). */
    suspend fun ack(token: String, upTo: Long, device: String) {
        call("POST", "/v1/messages/ack", JSONObject().put("upTo", upTo).put("device", device), token)
    }

    // ── Linking a new device (linking.md) ───────────────────────────────────

    /** The new device's one-time public key for a typed link code. */
    suspend fun linkDeviceKey(linkId: String): ByteArray =
        fromBase64Url(call("GET", "/v1/link/$linkId", null, null).getString("devicePub"))

    suspend fun sendLinkSeal(token: String, linkId: String, seal: LinkSeal) {
        call(
            "POST", "/v1/link/$linkId/seal",
            JSONObject()
                .put("senderPub", MoodifyIdentity.base64Url(seal.senderPub))
                .put("nonce", MoodifyIdentity.base64Url(seal.nonce))
                .put("ciphertext", MoodifyIdentity.base64Url(seal.ciphertext)),
            token,
        )
    }

    /** ws(s)://…/v1/live?token=…&device=… for the live connection; [device] isn't pinged while connected. */
    fun liveUrl(token: String, device: String? = null): String {
        val base = baseUrl.trimEnd('/').replaceFirst(Regex("^http"), "ws")
        val deviceParam = device?.let { "&device=${java.net.URLEncoder.encode(it, "UTF-8")}" }.orEmpty()
        return "$base/v1/live?token=${java.net.URLEncoder.encode(token, "UTF-8")}$deviceParam"
    }

    // ── Library sync (sync.md) ──────────────────────────────────────────────

    suspend fun syncPull(token: String, after: Long): SyncPage {
        val json = call("GET", "/v1/sync?after=$after", null, token)
        val rows = json.optJSONArray("records") ?: org.json.JSONArray()
        val records = (0 until rows.length()).map { i ->
            val r = rows.getJSONObject(i)
            WireRecord(item = r.getString("item"), updatedAt = r.getLong("updatedAt"), nonce = r.getString("nonce"), ciphertext = r.getString("ciphertext"), seq = r.optLong("seq"))
        }
        return SyncPage(records, json.optLong("latest", after), json.optBoolean("more"))
    }

    /** Returns the items the server accepted. */
    suspend fun syncPush(token: String, records: List<WireRecord>): List<String> {
        val body = JSONObject().put("records", org.json.JSONArray(records.map {
            JSONObject().put("item", it.item).put("updatedAt", it.updatedAt).put("nonce", it.nonce).put("ciphertext", it.ciphertext)
        }))
        val accepted = call("PUT", "/v1/sync", body, token).optJSONArray("accepted") ?: org.json.JSONArray()
        return (0 until accepted.length()).map { accepted.getString(it) }
    }

    /** Wake-up pings for this device (chat.md §3). */
    suspend fun registerPush(token: String, device: String, pushToken: String) {
        call("PUT", "/v1/push", JSONObject().put("device", device).put("platform", "fcm").put("token", pushToken), token)
    }

    // ── Listening rooms (rooms.md) ──────────────────────────────────────────

    /** Opens a new room with you as host; returns its 6-character code. */
    suspend fun createRoom(token: String): String =
        call("POST", "/v1/rooms", JSONObject(), token).getString("roomId")

    fun roomUrl(roomId: String, token: String): String {
        val base = baseUrl.trimEnd('/').replaceFirst(Regex("^http"), "ws")
        return "$base/v1/rooms/$roomId/live?token=${java.net.URLEncoder.encode(token, "UTF-8")}"
    }

    val httpClient: OkHttpClient get() = http

    /**
     * For the live connections: no read timeout (a quiet room is normal), and a
     * WebSocket ping every 20 s so dead connections are noticed and NATs keep them open.
     */
    val webSocketClient: OkHttpClient by lazy {
        http.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).pingInterval(20, TimeUnit.SECONDS).build()
    }

    private fun user(json: JSONObject) = SocialUser(
        code = json.getString("code"),
        displayName = json.getString("displayName"),
        boxPub = fromBase64Url(json.getString("boxPub")),
        since = json.optLong("since", 0),
        note = json.optString("note").takeIf { json.has("note") && !json.isNull("note") },
        avatar = ProfileAvatar.fromJson(json.optJSONObject("avatar")),
    )

    private fun fromBase64Url(value: String): ByteArray =
        android.util.Base64.decode(value, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)

    private suspend fun challenge(): String = post("/v1/auth/challenge", JSONObject()).getString("challenge")

    private fun session(json: JSONObject): SocialSession {
        val account = json.getJSONObject("account")
        return SocialSession(
            token = json.getString("token"),
            expiresAtSeconds = json.getLong("expiresAt"),
            code = account.getString("code"),
            displayName = account.getString("displayName"),
            avatar = ProfileAvatar.fromJson(account.optJSONObject("avatar")),
        )
    }

    private suspend fun post(path: String, body: JSONObject) = call("POST", path, body, null)

    private suspend fun call(method: String, path: String, body: JSONObject?, token: String?): JSONObject = withContext(Dispatchers.IO) {
        if (!isConfigured) throw SocialException("not_configured", "Moodify's social server isn't live yet.")
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + path)
            .method(method, body?.toString()?.toRequestBody(JSON))
            .apply { token?.let { header("Authorization", "Bearer $it") } }
            .build()
        val response = try {
            http.newCall(request).await()
        } catch (e: java.io.IOException) {
            throw SocialException("offline", "Couldn't reach Moodify. Check your connection.")
        }
        response.use {
            val text = it.body?.string().orEmpty()
            val json = text.takeIf(String::isNotBlank)?.let { raw -> runCatching { JSONObject(raw) }.getOrNull() } ?: JSONObject()
            if (!it.isSuccessful) {
                val error = json.optJSONObject("error")
                throw SocialException(
                    errorCode = error?.optString("code").orEmpty().ifBlank { "http_${it.code}" },
                    message = error?.optString("message").orEmpty().ifBlank { "Something went wrong (${it.code})." },
                    status = it.code,
                )
            }
            json
        }
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
