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
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.debounce
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

/** A message to show as a banner inside the app. */
data class InAppMessage(val peer: String, val name: String, val preview: String)

/** What a friend is doing right now (or last did). */
data class FriendPresence(
    val online: Boolean,
    val track: Track? = null,
    val playing: Boolean = false,
    val positionMs: Long = 0,
    /** Server time (ms) when [positionMs] was reported; 0 from older servers. */
    val atServerMs: Long = 0,
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
    val myAvatar: ProfileAvatar? = null,
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
    /** The player's live position; read on the main thread. */
    private val livePositionMs: () -> Long = { playback.value.positionMs },
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
                requestLibrarySync()
            }
            // Sends that never finished (app closed mid-send) show as failed, ready to retry.
            runCatching { chat.stuckSending(System.currentTimeMillis() - 60_000).forEach { chat.setStatus(it.mid, STATUS_FAILED) } }
        }
        // Your library changed here: sync it to your other devices a moment later.
        scope.launch {
            val db = com.moodify.app.data.MoodifyDatabase.get(appContext).moodifyDao()
            kotlinx.coroutines.flow.merge(
                db.observeFavorites().map { it.size to it.firstOrNull()?.trackId },
                db.observePlaylists().map { it.map { p -> p.id to p.updatedAtMs } },
                db.observePlaylistTracks().map { it.size },
                db.observeSavedCollections().map { it.size },
                db.observeHistory(1).map { it.firstOrNull()?.playedAtMs },
            ).drop(5).debounce(3_000).collect { requestLibrarySync() }
        }
        // Publish "listening now" when the song or play/pause changes, at once
        // after a seek (friends following you jump too), and every 15 s while playing.
        scope.launch {
            playback.map { PresenceKey(it.currentTrack?.id, it.isPlaying) }.distinctUntilChanged().collect { sendPresence() }
        }
        scope.launch(Dispatchers.Main) {
            var lastPos = -1L
            var lastAt = 0L
            var lastTrack: String? = null
            while (isActive) {
                delay(1_000)
                val s = playback.value
                val pos = livePositionMs()
                val now = System.currentTimeMillis()
                val step = if (s.isPlaying) now - lastAt else 0L
                val busy = s.isLoading || s.isResolving
                val seeked = !busy && s.currentTrack?.id != null && s.currentTrack.id == lastTrack && lastPos >= 0 &&
                    kotlin.math.abs((pos - lastPos) - step) > SEEK_DETECT_MS
                when {
                    seeked -> sendPresence(seek = true)
                    s.isPlaying && now - lastPresenceAt > 15_000 -> sendPresence()
                }
                lastPos = if (busy) -1 else pos
                lastAt = now
                lastTrack = s.currentTrack?.id
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

    /** Your name or picture changed (Account screen). */
    fun profileChanged() {
        scope.launch { reloadIdentity() }
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
                requestLibrarySync()
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
            it.copy(myCode = identity?.identity?.code, myName = identity?.displayName.orEmpty(), myAvatar = identity?.avatar, registered = identity?.registered == true)
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
    suspend fun sendPlaylist(to: SocialUser, name: String, tracks: List<Track>) = sendCard(to, "playlist") { SharePayload.playlist(name, tracks, it) }

    private val _inApp = kotlinx.coroutines.flow.MutableSharedFlow<InAppMessage>(extraBufferCapacity = 8)
    /** A message that arrived while you're in the app, on another screen. */
    val inAppMessages: kotlinx.coroutines.flow.SharedFlow<InAppMessage> = _inApp

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
        runLibrarySync()
    }

    // ── Library sync (sync.md) ──────────────────────────────────────────────

    private val librarySyncLock = Mutex()
    @Volatile private var librarySyncAgain = false
    private val libraryAdapter by lazy { AndroidLibraryAdapter(com.moodify.app.data.MoodifyDatabase.get(appContext).moodifyDao()) }

    /** Likes, playlists, saved items and recent plays, to and from your other devices. */
    fun requestLibrarySync() {
        scope.launch { runLibrarySync() }
    }

    private suspend fun runLibrarySync() {
        if (librarySyncLock.isLocked) { librarySyncAgain = true; return }
        librarySyncLock.withLock {
            do {
                librarySyncAgain = false
                val me = identity?.identity ?: return
                val session = token() ?: return
                val keys = SyncKeys.fromSeed(me.seed)
                val file = File(appContext.filesDir, "library_sync_${me.code}.json")
                val saveState = { s: SyncState -> runCatching { file.writeText(s.toJson()) }; Unit }
                runCatching {
                    LibrarySync.syncOnce(
                        adapter = libraryAdapter,
                        transport = object : SyncTransport {
                            override suspend fun pull(after: Long) = client.syncPull(session, after)
                            override suspend fun push(records: List<WireRecord>) = client.syncPush(session, records)
                        },
                        crypto = object : RecordCrypto {
                            override fun itemId(c: String, id: String) = keys.itemId(c, id)
                            override fun seal(item: String, plaintext: String) = keys.seal(me.code, item, plaintext)
                            override fun open(item: String, nonce: String, ciphertext: String) = keys.open(me.code, item, nonce, ciphertext)
                        },
                        state = SyncState.fromJson(file.takeIf(File::exists)?.readText()),
                        onState = saveState,
                    )
                }.onSuccess { result ->
                    saveState(result.state)
                    if (result.pulled + result.pushed > 0) android.util.Log.i("MoodifySync", "pulled ${result.pulled}, pushed ${result.pushed}")
                }.onFailure { android.util.Log.w("MoodifySync", "sync failed: ${it.message}") }
            } while (librarySyncAgain)
        }
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
                    parsed.playlist != null -> "playlist"
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
                // In the background: a notification. In the app but another screen: a quiet banner.
                val preview = ChatNotifier.preview(kind, parsed)
                if (!foreground) notifier.show(friend.code, friend.displayName, preview)
                else if (activeThread != friend.code) _inApp.tryEmit(InAppMessage(friend.code, friend.displayName, preview))
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
            syncClock()
            requestLibrarySync()
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
            "pong" -> {
                val t0 = body.optLong("t")
                val rtt = System.currentTimeMillis() - t0
                if (t0 > 0 && rtt in 0 until bestRttMs) {
                    bestRttMs = rtt
                    clockOffsetMs = message.optLong("ts") - (t0 + rtt / 2)
                }
            }
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
            "friend.request", "friend.accepted", "friend.removed", "friend.updated" -> refresh()
            "msg.new" -> fetchInbox()
            "sync.changed" -> requestLibrarySync()
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
            atServerMs = p?.optLong("at") ?: 0,
            lastSeenAtSeconds = if (body.isNull("lastSeenAt")) null else body.optLong("lastSeenAt"),
        )
    }

    @Volatile private var lastPresenceAt = 0L
    @Volatile private var clockOffsetMs: Long? = null
    @Volatile private var bestRttMs = Long.MAX_VALUE

    /** Server time minus this phone's, measured over the live connection; null until measured. */
    fun serverClockOffsetMs(): Long? = clockOffsetMs

    /** Measures the server clock a few times (the shortest round trip wins). */
    fun syncClock() {
        scope.launch {
            bestRttMs = Long.MAX_VALUE
            repeat(5) {
                send("ping", JSONObject().put("t", System.currentTimeMillis()))
                delay(200)
            }
        }
    }

    /** [seek]: the position jumped; the server passes it on at once. */
    private fun sendPresence(seek: Boolean = false) {
        if (!shareListening) return
        scope.launch(Dispatchers.Main) {
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
                    .put("positionMs", livePositionMs().coerceAtLeast(0))
                    .put("playing", s.isPlaying)
                    .apply { if (seek) put("seek", true) }
            }
            lastPresenceAt = System.currentTimeMillis()
            send("presence.update", body)
        }
    }

    /** One friend's "listening now", for following them. */
    fun presenceOf(code: String): kotlinx.coroutines.flow.Flow<FriendPresence?> =
        state.map { it.presence[code] }.distinctUntilChanged()

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
        // Your name and picture may have changed on another device.
        if (signedIn.displayName != stored.displayName || signedIn.avatar != stored.avatar) {
            store.setDisplayName(signedIn.displayName)
            store.setAvatar(signedIn.avatar)
            identity = identity?.copy(displayName = signedIn.displayName, avatar = signedIn.avatar)
            _state.update { it.copy(myName = signedIn.displayName, myAvatar = signedIn.avatar) }
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
        /** A position jump bigger than this (beyond normal playing) is a seek. */
        const val SEEK_DETECT_MS = 2_500L
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
    /** Songs per shared playlist (the rest are left out). */
    const val MAX_PLAYLIST = 300

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

    /** One of your own playlists, sent to a friend (chat.md §1): its name and songs. */
    fun playlist(name: String, tracks: List<Track>, mid: String = newMid()): String = envelope("playlist", mid)
        .put("playlist", JSONObject().put("name", name.take(100)).put("tracks", org.json.JSONArray(tracks.take(MAX_PLAYLIST).map(::trackJson))))
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
        /** A shared playlist: its name and songs. */
        val playlist: SharedPlaylist? = null,
    )

    data class SharedPlaylist(val name: String, val tracks: List<Track>)

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
            "playlist" -> json.optJSONObject("playlist")?.let { p ->
                val songs = p.optJSONArray("tracks")?.let { a -> (0 until minOf(a.length(), MAX_PLAYLIST)).mapNotNull { a.optJSONObject(it)?.let(::parseTrack) } }.orEmpty()
                if (songs.isEmpty()) return null
                Parsed(playlist = SharedPlaylist(p.optString("name").ifBlank { "Playlist" }.take(100), songs))
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
