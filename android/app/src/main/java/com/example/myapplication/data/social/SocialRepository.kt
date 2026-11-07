package com.moodify.app.data.social

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.moodify.app.BuildConfig
import com.moodify.app.data.BrowseEndpoint
import com.moodify.app.data.CatalogItem
import com.moodify.app.data.Track
import com.moodify.app.player.PlaybackState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.File

/** What a friend is doing right now (or last did). */
data class FriendPresence(
    val online: Boolean,
    val track: Track? = null,
    val playing: Boolean = false,
    val positionMs: Long = 0,
    val receivedAtMs: Long = System.currentTimeMillis(),
    val lastSeenAtSeconds: Long? = null,
)

/** A song or collection a friend sent you (decrypted on this phone). */
data class SharedItem(
    val id: Long,
    val fromCode: String,
    val fromName: String,
    val sentAtSeconds: Long,
    val track: Track? = null,
    val collection: CatalogItem? = null,
    /** An invite to a listening room (rooms.md §5). */
    val roomId: String? = null,
)

data class SocialState(
    val serverConfigured: Boolean = false,
    val myCode: String? = null,
    val myName: String = "",
    val registered: Boolean = false,
    val connected: Boolean = false,
    val lists: FriendLists = FriendLists(),
    val presence: Map<String, FriendPresence> = emptyMap(),
    val shared: List<SharedItem> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

/**
 * Everything social on this phone (M2): the live connection to your hub,
 * friends and their "listening now", and songs friends send (end-to-end
 * encrypted; see docs/social-protocol/friends.md).
 */
class SocialRepository(
    context: Context,
    private val playback: StateFlow<PlaybackState>,
) {
    private val appContext = context.applicationContext
    private val store = IdentityStore(appContext)
    private val client = SocialClient(BuildConfig.SOCIAL_BASE_URL)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val gson = Gson()
    private val inboxFile = File(appContext.filesDir, "social-inbox.json")
    private val sessionLock = Mutex()
    private val inboxLock = Mutex()
    private val chat by lazy { com.moodify.app.data.MoodifyDatabase.get(appContext).chatDao() }
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    /** This install's id for the server inbox (each device reads from its own place). */
    private val deviceId: String = prefs.getString(KEY_DEVICE, null)
        ?: java.util.UUID.randomUUID().toString().replace("-", "").also { prefs.edit().putString(KEY_DEVICE, it).apply() }
    private val notifier = ChatNotifier(appContext)
    /** The conversation on screen right now: no notification for it, and it's read. */
    @Volatile private var activeThread: String? = null

    private var identity: StoredIdentity? = null
    private var session: SocialSession? = null
    private var socket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var foreground = false
    private var backoffMs = INITIAL_BACKOFF_MS
    @Volatile private var shareListening = true

    private val _state = MutableStateFlow(SocialState(serverConfigured = client.isConfigured, shared = loadInbox()))
    val state: StateFlow<SocialState> = _state.asStateFlow()

    init {
        scope.launch {
            reloadIdentity()
            // The activity may have come to the foreground before the identity loaded.
            if (foreground) connect()
            if (identity != null) {
                InboxWorker.schedule(appContext)
                registerPush()
            }
            // Sends that never finished (app closed mid-send) show as failed, ready to retry.
            runCatching { chat.stuckSending(System.currentTimeMillis() - 60_000).forEach { chat.setStatus(it.mid, STATUS_FAILED) } }
        }
        // Publish "listening now" when the song or play/pause changes, plus a
        // position refresh every 15 s while playing (for listening along later).
        scope.launch {
            playback.map { PresenceKey(it.currentTrack?.id, it.isPlaying) }.distinctUntilChanged().collect { sendPresence() }
        }
        scope.launch {
            while (isActive) {
                delay(15_000)
                if (playback.value.isPlaying) sendPresence()
            }
        }
    }

    private data class PresenceKey(val trackId: String?, val playing: Boolean)

    // ── Lifecycle ───────────────────────────────────────────────────────────

    /** Call from the activity: live connection only while the app is on screen. */
    fun setForeground(isForeground: Boolean) {
        foreground = isForeground
        if (isForeground) {
            connect()
            // Catch up on anything that arrived while the app was away.
            if (socket != null) scope.launch { fetchInbox() }
        } else scope.launch {
            delay(BACKGROUND_GRACE_MS)
            if (!foreground) disconnect()
        }
    }

    /** After the identity was created, restored or deleted (Account screen). */
    fun identityChanged() {
        scope.launch {
            disconnect()
            session = null
            val before = identity?.identity?.code
            reloadIdentity()
            val after = identity?.identity?.code
            if (before != after) {
                // Another identity: its conversations and inbox place aren't this one's.
                runCatching { chat.deleteAll() }
                prefs.edit().remove(KEY_CURSOR).apply(); checkedIn = false
                notifier.cancelAll()
            }
            prefs.edit().remove(KEY_PUSH).apply()
            if (identity == null) {
                inboxFile.delete()
                InboxWorker.cancel(appContext)
                PushTokens.forget(appContext)
                _state.update { SocialState(serverConfigured = client.isConfigured) }
            } else {
                InboxWorker.schedule(appContext)
                connect()
                registerPush()
            }
        }
    }

    fun setShareListening(enabled: Boolean) {
        shareListening = enabled
        send("settings.update", JSONObject().put("sharing", enabled))
        if (enabled) sendPresence()
    }

    private suspend fun reloadIdentity() {
        identity = runCatching { store.load() }.getOrNull()
        _state.update {
            it.copy(myCode = identity?.identity?.code, myName = identity?.displayName.orEmpty(), registered = identity?.registered == true)
        }
    }

    // ── Friends ─────────────────────────────────────────────────────────────

    suspend fun refresh() {
        val token = token() ?: return
        _state.update { it.copy(loading = true) }
        try {
            val lists = client.friends(token)
            _state.update { it.copy(lists = lists, loading = false, error = null) }
            fetchInbox()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(loading = false, error = e.message) }
        }
    }

    /** Sends a request; returns a short status line for the UI. */
    suspend fun addFriend(code: String, note: String?): String {
        val normalized = MoodifyIdentity.normalizeCode(code) ?: return "That doesn't look like a Moodify code."
        if (normalized == _state.value.myCode) return "That's your own code."
        val token = token() ?: return notReadyMessage()
        val (status, user) = client.requestFriend(token, normalized, note)
        refresh()
        return if (status == "friends") "You and ${user.displayName} are now friends." else "Request sent to ${user.displayName}."
    }

    suspend fun answer(code: String, accept: Boolean) = withToken { client.answerRequest(it, code, accept); refresh() }
    suspend fun remove(code: String) = withToken { client.removeFriend(it, code); refresh() }
    suspend fun block(code: String) = withToken {
        client.block(it, code)
        runCatching { chat.deleteThread(code) }
        notifier.cancel(code)
        _state.update { s -> s.copy(presence = s.presence - code, shared = s.shared.filterNot { item -> item.fromCode == code }) }
        persistInbox()
        refresh()
    }
    suspend fun report(code: String, reason: String) = withToken { client.report(it, code, reason, evidence = null) }

    // ── Sharing (end-to-end encrypted) ──────────────────────────────────────

    suspend fun sendTrack(to: SocialUser, track: Track) = sendCard(to, "track") { SharePayload.track(track, it) }
    suspend fun sendCollection(to: SocialUser, item: CatalogItem) = sendCard(to, "collection") { SharePayload.collection(item, it) }
    suspend fun sendRoomInvite(to: SocialUser, roomId: String, track: Track?) = sendCard(to, "room") { SharePayload.room(roomId, track, it) }

    // ── Chat (docs/social-protocol/chat.md) ─────────────────────────────────

    fun conversations() = chat.observeConversations()
    fun thread(peer: String) = chat.observeThread(peer)
    fun unreadTotal() = chat.observeUnreadTotal()

    /** The conversation now on screen (null when none): it's read, and doesn't notify. */
    fun setActiveThread(peer: String?) {
        activeThread = peer
        if (peer != null) {
            notifier.cancel(peer)
            scope.launch { runCatching { chat.markRead(peer) } }
        }
    }

    suspend fun sendText(to: SocialUser, text: String) {
        val clean = text.trim().take(SharePayload.MAX_TEXT)
        if (clean.isEmpty()) return
        val mid = SharePayload.newMid()
        chat.insert(ChatMessageEntity(mid, to.code, fromMe = true, kind = "text", body = clean, sentAtMs = System.currentTimeMillis(), status = STATUS_SENDING, read = true))
        deliver(to, mid, SharePayload.text(clean, mid))
    }

    private suspend fun sendCard(to: SocialUser, kind: String, payload: (String) -> String) {
        val mid = SharePayload.newMid()
        val plain = payload(mid)
        chat.insert(ChatMessageEntity(mid, to.code, fromMe = true, kind = kind, body = plain, sentAtMs = System.currentTimeMillis(), status = STATUS_SENDING, read = true))
        deliver(to, mid, plain)
    }

    private suspend fun deliver(to: SocialUser, mid: String, plaintext: String) {
        try {
            send(to, plaintext)
            chat.setStatus(mid, STATUS_SENT)
        } catch (e: Exception) {
            chat.setStatus(mid, STATUS_FAILED)
            throw e
        }
    }

    /** Sends a failed message again (same id, so it never shows twice). */
    suspend fun retry(mid: String) {
        val message = chat.get(mid)?.takeIf { it.fromMe } ?: return
        val friend = _state.value.lists.friends.firstOrNull { it.code == message.peer }
            ?: throw SocialException("not_friends", "You're no longer friends.")
        chat.setStatus(mid, STATUS_SENDING)
        deliver(friend, mid, if (message.kind == "text") SharePayload.text(message.body, mid) else message.body)
    }

    /** Your reaction to a message ("" removes it). */
    suspend fun react(to: SocialUser, target: String, emoji: String) {
        val me = identity?.identity?.code ?: return
        applyReaction(target, me, emoji)
        send(to, SharePayload.reaction(target, emoji))
    }

    private suspend fun applyReaction(target: String, who: String, emoji: String) {
        val message = chat.get(target) ?: return
        chat.setReactions(target, Reactions.apply(message.reactions, who, emoji))
    }

    /** For the background check (InboxWorker): fetch, store and notify while the app is closed. */
    /** Firebase gave this install a new token. */
    fun onPushToken(pushToken: String) {
        scope.launch { registerPush(pushToken) }
    }

    /**
     * Tells the server where to send wake-up pings for this phone (chat.md §3).
     * Only with a Moodify code: no token is even requested before that. Refreshed weekly.
     */
    private suspend fun registerPush(known: String? = null) {
        val me = identity?.identity?.code ?: return
        val pushToken = known ?: PushTokens.current(appContext) ?: return
        val key = "$me|$pushToken"
        val now = System.currentTimeMillis()
        if (prefs.getString(KEY_PUSH, null) == key && now - prefs.getLong(KEY_PUSH_AT, 0) < PUSH_REFRESH_MS) return
        val session = token() ?: return
        runCatching { client.registerPush(session, deviceId, pushToken) }
            .onSuccess { prefs.edit().putString(KEY_PUSH, key).putLong(KEY_PUSH_AT, now).apply() }
    }

    suspend fun syncInBackground() {
        if (identity == null) reloadIdentity()
        fetchInbox()
    }

    /** For the listening-room connection, which shares this phone's session. */
    val socialClient: SocialClient get() = client
    suspend fun sessionToken(): String? = token()

    private suspend fun send(to: SocialUser, payload: String) {
        val me = identity?.identity ?: throw SocialException("no_identity", "Create your Moodify code first.")
        val token = token() ?: throw SocialException("offline", notReadyMessage())
        client.sendMessage(token, to.code, me.sealFor(to.code, to.boxPub, payload))
    }

    fun dismissShared(id: Long) {
        _state.update { it.copy(shared = it.shared.filterNot { item -> item.id == id }) }
        scope.launch { persistInbox() }
    }

    /**
     * Reads the inbox from this device's own place: decrypts, stores chat
     * messages, applies reactions, notifies, then confirms (chat.md §2).
     */
    @Volatile private var checkedIn = false

    private suspend fun fetchInbox() = inboxLock.withLock {
        val me = identity?.identity ?: return@withLock
        val token = token() ?: return@withLock
        var after = prefs.getLong(KEY_CURSOR, 0)
        while (true) {
            val messages = runCatching { client.inbox(token, after) }.getOrElse { return@withLock }
            if (messages.isEmpty()) {
                // Once per run: make sure the server keeps this phone's messages for it (chat.md §2).
                if (!checkedIn) runCatching { client.ack(token, after, deviceId) }.onSuccess { checkedIn = true }
                break
            }
            checkedIn = true
            var friends = _state.value.lists.friends
            if (messages.any { m -> friends.none { it.code == (m.to ?: m.from) } }) {
                friends = runCatching { client.friends(token) }.getOrNull()?.also { lists -> _state.update { it.copy(lists = lists) } }?.friends ?: friends
            }
            val shared = mutableListOf<SharedItem>()
            for (message in messages) {
                val own = message.to != null
                val friend = friends.firstOrNull { it.code == (message.to ?: message.from) } ?: continue
                val plain = runCatching {
                    if (own) me.openSent(friend.code, friend.boxPub, message.nonce, message.ciphertext)
                    else me.openFrom(friend.code, friend.boxPub, message.nonce, message.ciphertext)
                }.getOrNull() ?: continue
                val parsed = SharePayload.parse(plain) ?: continue
                val mid = parsed.mid ?: "srv-${message.id}" // from a version without message ids
                if (parsed.reactionTarget != null) {
                    applyReaction(parsed.reactionTarget, if (own) me.code else friend.code, parsed.emoji.orEmpty())
                    continue
                }
                val kind = when {
                    parsed.text != null -> "text"
                    parsed.roomId != null -> "room"
                    parsed.track != null -> "track"
                    parsed.collection != null -> "collection"
                    else -> continue
                }
                val entity = ChatMessageEntity(
                    mid = mid, peer = friend.code, fromMe = own, kind = kind,
                    body = parsed.text ?: plain, sentAtMs = message.sentAtSeconds * 1000,
                    status = if (own) STATUS_SENT else STATUS_RECEIVED,
                    read = own || activeThread == friend.code,
                )
                val isNew = runCatching { chat.insert(entity) }.getOrDefault(-1L) != -1L
                if (own) {
                    if (!isNew) chat.setStatus(mid, STATUS_SENT) // this phone's own send, confirmed
                    continue
                }
                if (!isNew) continue
                if (kind != "text") shared += SharedItem(message.id, friend.code, friend.displayName, message.sentAtSeconds, parsed.track, parsed.collection, parsed.roomId)
                if (!foreground || activeThread != friend.code) notifier.show(friend.code, friend.displayName, ChatNotifier.preview(kind, parsed))
            }
            if (shared.isNotEmpty()) {
                _state.update { s -> s.copy(shared = (shared.reversed() + s.shared).distinctBy { it.id }.take(MAX_SHARED)) }
                persistInbox()
            }
            after = messages.maxOf { it.id }
            prefs.edit().putLong(KEY_CURSOR, after).apply()
            runCatching { client.ack(token, after, deviceId) }
            if (messages.size < INBOX_PAGE) break
        }
    }

    // ── Live connection ─────────────────────────────────────────────────────

    private fun connect() {
        if (!client.isConfigured || identity == null || socket != null || !foreground) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            val token = token() ?: return@launch scheduleReconnect()
            refresh()
            val request = Request.Builder().url(client.liveUrl(token, deviceId)).build()
            socket = client.webSocketClient.newWebSocket(request, listener)
        }
    }

    private fun disconnect() {
        reconnectJob?.cancel()
        socket?.close(1000, "background")
        socket = null
        _state.update { it.copy(connected = false) }
    }

    private fun scheduleReconnect() {
        if (!foreground) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
            socket = null
            connect()
        }
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            backoffMs = INITIAL_BACKOFF_MS
            _state.update { it.copy(connected = true) }
            if (!shareListening) send("settings.update", JSONObject().put("sharing", false))
            sendPresence()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            scope.launch { handle(text) }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = lost(webSocket, null)

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = lost(webSocket, response)

        private fun lost(webSocket: WebSocket, response: Response?) {
            if (socket !== webSocket) return
            socket = null
            if (response?.code == 401) session = null
            _state.update { it.copy(connected = false) }
            scheduleReconnect()
        }
    }

    private suspend fun handle(text: String) {
        val message = runCatching { JSONObject(text) }.getOrNull() ?: return
        val body = message.optJSONObject("body") ?: JSONObject()
        when (message.optString("type")) {
            "hello" -> {
                val friends = body.optJSONArray("friends")
                val presence = (0 until (friends?.length() ?: 0)).associate { index ->
                    val entry = friends!!.getJSONObject(index)
                    entry.getString("code") to parsePresence(entry)
                }
                _state.update { it.copy(presence = presence) }
            }
            "presence.friend" -> {
                val code = body.optString("code").takeIf(String::isNotBlank) ?: return
                _state.update { it.copy(presence = it.presence + (code to parsePresence(body))) }
            }
            "friend.request", "friend.accepted", "friend.removed" -> refresh()
            "msg.new" -> fetchInbox()
        }
    }

    private fun parsePresence(body: JSONObject): FriendPresence {
        val p = body.optJSONObject("presence")
        val track = p?.let {
            Track(
                id = it.optString("trackId"),
                title = it.optString("title"),
                artist = it.optString("artist"),
                albumArtUrl = it.optString("artworkUrl"),
                durationSeconds = (it.optLong("durationMs") / 1000).toInt(),
            )
        }
        return FriendPresence(
            online = body.optBoolean("online"),
            track = track,
            playing = p?.optBoolean("playing") == true,
            positionMs = p?.optLong("positionMs") ?: 0,
            lastSeenAtSeconds = if (body.isNull("lastSeenAt")) null else body.optLong("lastSeenAt"),
        )
    }

    private fun sendPresence() {
        if (!shareListening) return
        val s = playback.value
        val track = s.currentTrack
        val body = if (track == null) {
            JSONObject().put("cleared", true)
        } else {
            JSONObject()
                .put("trackId", track.id)
                .put("title", track.title)
                .put("artist", track.artist)
                .put("artworkUrl", track.albumArtUrl)
                .put("durationMs", track.durationSeconds * 1000L)
                .put("positionMs", s.positionMs)
                .put("playing", s.isPlaying)
        }
        send("presence.update", body)
    }

    private fun send(type: String, body: JSONObject) {
        socket?.send(JSONObject().put("v", 1).put("type", type).put("body", body).toString())
    }

    // ── Session ─────────────────────────────────────────────────────────────

    private suspend fun token(): String? = sessionLock.withLock {
        val stored = identity ?: return@withLock null
        if (!client.isConfigured) return@withLock null
        val now = System.currentTimeMillis() / 1000
        session?.takeIf { it.expiresAtSeconds > now + 60 }?.let { return@withLock it.token }
        val signedIn = runCatching {
            client.login(stored.identity) ?: client.register(stored.identity, stored.displayName)
        }.onFailure { Log.w(TAG, "sign-in failed: ${it.message}") }.getOrNull() ?: return@withLock null
        if (!stored.registered) {
            store.setRegistered(true)
            identity = stored.copy(registered = true)
            _state.update { it.copy(registered = true) }
        }
        session = signedIn
        signedIn.token
    }

    private suspend fun withToken(block: suspend (String) -> Unit) {
        val token = token() ?: throw SocialException("offline", notReadyMessage())
        block(token)
    }

    private fun notReadyMessage() = when {
        identity == null -> "Create your Moodify code first."
        !client.isConfigured -> "Friends go live soon. Your code is saved on this phone."
        else -> "Couldn't reach Moodify. Check your connection."
    }

    // ── Local inbox (decrypted, on this phone only) ─────────────────────────

    private fun loadInbox(): List<SharedItem> = runCatching {
        if (!inboxFile.exists()) emptyList() else gson.fromJson(inboxFile.readText(), Array<SharedItem>::class.java).toList()
    }.getOrDefault(emptyList())

    private fun persistInbox() {
        runCatching { inboxFile.writeText(gson.toJson(_state.value.shared)) }
    }

    companion object {
        const val TAG = "MoodifySocial"
        const val INITIAL_BACKOFF_MS = 2_000L
        const val MAX_BACKOFF_MS = 60_000L
        const val BACKGROUND_GRACE_MS = 30_000L
        const val MAX_SHARED = 100
        const val INBOX_PAGE = 100
        const val PREFS = "moodify_social"
        const val KEY_DEVICE = "inbox_device"
        const val KEY_PUSH = "push_registered"
        const val KEY_PUSH_AT = "push_registered_at"
        const val PUSH_REFRESH_MS = 7L * 24 * 60 * 60 * 1000
        const val KEY_CURSOR = "inbox_after"
        const val STATUS_SENDING = "sending"
        const val STATUS_SENT = "sent"
        const val STATUS_FAILED = "failed"
        const val STATUS_RECEIVED = "received"
    }
}

/**
 * The plaintext inside an encrypted message (friends.md §3.2, chat.md §1).
 * Every message carries a [mid]: the sender's id for it, the same on all devices.
 */
object SharePayload {
    const val MAX_TEXT = 2000

    fun newMid(): String = java.util.UUID.randomUUID().toString()

    private fun envelope(type: String, mid: String) = JSONObject().put("v", 1).put("type", type).put("mid", mid)

    private fun trackJson(track: Track) = JSONObject()
        .put("id", track.id).put("title", track.title).put("artist", track.artist)
        .put("album", track.album).put("artworkUrl", track.albumArtUrl).put("durationSeconds", track.durationSeconds)
        .apply { track.artistId?.let { put("artistId", it) } }

    fun text(text: String, mid: String = newMid()): String =
        envelope("text", mid).put("text", text.take(MAX_TEXT)).toString()

    /** [emoji] empty removes your reaction. */
    fun reaction(target: String, emoji: String, mid: String = newMid()): String =
        envelope("reaction", mid).put("target", target).put("emoji", emoji).toString()

    fun track(track: Track, mid: String = newMid()): String =
        envelope("track", mid).put("track", trackJson(track)).toString()

    fun collection(item: CatalogItem, mid: String = newMid()): String = envelope("collection", mid)
        .put("collection", JSONObject()
            .put("kind", item.type).put("browseId", item.endpoint?.browseId ?: item.id).put("title", item.title)
            .put("artworkUrl", item.artworkUrl)
            .apply { item.endpoint?.params?.let { put("params", it) }; item.endpoint?.pageType?.let { put("pageType", it) } })
        .toString()

    /** An invite to listen together; [track] is what's playing, for the preview. */
    fun room(roomId: String, track: Track?, mid: String = newMid()): String = envelope("room", mid)
        .put("room", JSONObject().put("id", roomId).apply { track?.let { put("track", trackJson(it)) } })
        .toString()

    data class Parsed(
        val track: Track? = null,
        val collection: CatalogItem? = null,
        val roomId: String? = null,
        val text: String? = null,
        /** For reactions: the message reacted to, and the emoji ("" = removed). */
        val reactionTarget: String? = null,
        val emoji: String? = null,
        val mid: String? = null,
        val type: String = "",
    )

    private val MID = Regex("[A-Za-z0-9_-]{8,64}")
    private val EMOJI_MAX = 16

    /** Null for unknown or newer message types this app can't show. */
    fun parse(text: String): Parsed? {
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return null
        if (json.optInt("v") != 1) return null
        val type = json.optString("type")
        val mid = json.optString("mid").takeIf { it.matches(MID) }
        val parsed = when (type) {
            "text" -> json.optString("text").take(MAX_TEXT).takeIf { it.isNotBlank() }?.let { Parsed(text = it) }
            "reaction" -> {
                val target = json.optString("target").takeIf { it.matches(MID) } ?: return null
                val emoji = json.optString("emoji")
                if (emoji.length > EMOJI_MAX || emoji.any { it.isLetterOrDigit() && it.code < 128 }) return null
                Parsed(reactionTarget = target, emoji = emoji)
            }
            "track" -> json.optJSONObject("track")?.let(::parseTrack)?.let { Parsed(track = it) }
            "room" -> json.optJSONObject("room")?.let { r ->
                val id = r.optString("id").uppercase().takeIf { it.matches(Regex("[0-9A-HJKMNP-TV-Z]{6}")) } ?: return null
                Parsed(track = r.optJSONObject("track")?.let(::parseTrack), roomId = id)
            }
            "collection" -> json.optJSONObject("collection")?.let { c ->
                val browseId = c.optString("browseId").takeIf { it.matches(Regex("[A-Za-z0-9_-]{2,80}")) } ?: return null
                Parsed(collection = CatalogItem(
                    type = c.optString("kind").takeIf { it in setOf("album", "playlist", "artist") } ?: "playlist",
                    id = browseId,
                    title = c.optString("title").take(200),
                    artworkUrl = c.optString("artworkUrl").takeIf { it.startsWith("https://") }.orEmpty(),
                    endpoint = BrowseEndpoint(browseId, null, c.optString("params").takeIf(String::isNotBlank), c.optString("pageType").takeIf(String::isNotBlank)),
                ))
            }
            else -> null
        }
        return parsed?.copy(mid = mid, type = type)
    }

    private fun parseTrack(t: JSONObject): Track? {
        val id = t.optString("id").takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) } ?: return null
        return Track(
            id = id,
            title = t.optString("title").take(200),
            artist = t.optString("artist").take(200),
            album = t.optString("album").take(200),
            albumArtUrl = t.optString("artworkUrl").takeIf { it.startsWith("https://") }.orEmpty(),
            durationSeconds = t.optInt("durationSeconds"),
            artistId = t.optString("artistId").takeIf { it.startsWith("UC") },
        )
    }
}
