package com.moodify.app.data.social

import android.util.Log
import com.moodify.app.data.Track
import com.moodify.app.player.PlaybackManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

data class RoomMember(val code: String, val displayName: String, val role: String)
data class RoomChatLine(val from: String, val displayName: String, val text: String)

/** What the room is doing, as this phone understands it. */
data class RoomUiState(
    val roomId: String,
    val you: String = "",
    val role: String = "listener",
    val hostCode: String? = null,
    val members: List<RoomMember> = emptyList(),
    val track: Track? = null,
    val playing: Boolean = false,
    val anchorPositionMs: Long = 0,
    val anchorServerTime: Long = 0,
    val queue: List<Track> = emptyList(),
    val chat: List<RoomChatLine> = emptyList(),
    val suggestions: List<Pair<String, Track>> = emptyList(),
    val connected: Boolean = false,
    val error: String? = null,
) {
    val canControl: Boolean get() = role == "host" || role == "dj"
    val isHost: Boolean get() = role == "host"
}

/**
 * Listen together (docs/social-protocol/rooms.md). Every device plays the song
 * itself; the room shares a clock. The host's (and DJs') own player is the
 * source of truth; everyone else follows it and corrects drift by playing
 * ±3% faster for a moment, or seeking when far off.
 */
class RoomController(
    private val client: SocialClient,
    private val playback: PlaybackManager,
    private val token: suspend () -> String?,
    private val sendInvite: suspend (SocialUser, String, Track?) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _room = MutableStateFlow<RoomUiState?>(null)
    val room: StateFlow<RoomUiState?> = _room.asStateFlow()

    private var socket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var backoffMs = 2_000L
    private var lastFollowLog = 0L
    private var jobs = mutableListOf<Job>()
    private var clockOffsetMs = 0L
    private var bestRttMs = Long.MAX_VALUE
    private var nudging = false
    private var autoplayBefore: Boolean? = null
    /** After this phone changed the room itself, don't follow until the room echoes it. */
    private var localActionUntil = 0L

    private fun serverNow() = System.currentTimeMillis() + clockOffsetMs

    /** Where the room's song should be right now, by the room clock. */
    private fun expectedPositionMs(s: RoomUiState) =
        if (s.playing) s.anchorPositionMs + (serverNow() - s.anchorServerTime) else s.anchorPositionMs

    suspend fun create(): String {
        val t = token() ?: throw SocialException("offline", "Couldn't reach Moodify.")
        val roomId = client.createRoom(t)
        join(roomId)
        return roomId
    }

    fun join(roomId: String) {
        leave()
        Log.i(TAG, "joining room $roomId")
        _room.value = RoomUiState(roomId = roomId.uppercase())
        autoplayBefore = playback.autoplayEnabled
        playback.autoplayEnabled = false // the room decides what plays next
        jobs += scope.launch { connect() }
        jobs += scope.launch { syncClockForever() }
        jobs += scope.launch { followLoop() }
        jobs += scope.launch { mirrorLocalPlayback() }
        jobs += scope.launch { mirrorPlayIntents() }
    }

    private suspend fun connect() {
        val roomId = _room.value?.roomId ?: return
        val t = token() ?: return reconnectLater("Couldn't reach Moodify.")
        val request = Request.Builder().url(client.roomUrl(roomId, t)).build()
        socket = client.webSocketClient.newWebSocket(request, listener)
    }

    /** Lost the room: try again with backoff while the person is still in it. */
    private fun reconnectLater(error: String) {
        socket = null
        _room.update { it?.copy(connected = false, error = error) }
        if (_room.value == null) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(30_000)
            if (_room.value != null && socket == null) {
                bestRttMs = Long.MAX_VALUE
                connect()
            }
        }
    }

    fun leave() {
        if (_room.value != null) Log.i(TAG, "leaving room ${_room.value?.roomId}")
        reconnectJob?.cancel()
        backoffMs = 2_000
        socket?.close(1000, "left")
        socket = null
        jobs.forEach { it.cancel() }
        jobs.clear()
        playback.nudgeRate(1f)
        autoplayBefore?.let { playback.autoplayEnabled = it }
        autoplayBefore = null
        bestRttMs = Long.MAX_VALUE
        _room.value = null
    }

    fun chat(text: String) = send("room.chat", JSONObject().put("text", text))
    fun makeDj(code: String) = send("room.role", JSONObject().put("code", code).put("role", "dj"))
    fun makeHost(code: String) = send("room.role", JSONObject().put("code", code).put("role", "host"))
    fun queue(track: Track) = control("queueAdd", JSONObject().put("track", trackJson(track)))
    fun next() = control("next", JSONObject())

    suspend fun invite(friend: SocialUser) {
        val s = _room.value ?: return
        sendInvite(friend, s.roomId, s.track)
    }

    // ── Hosts and DJs: their own player drives the room ─────────────────────

    /** Pushes this phone's own song changes and play/pause to the room. */
    private suspend fun mirrorLocalPlayback() {
        playback.state
            .map { Triple(it.currentTrack, it.isPlaying, it.isLoading || it.isResolving) }
            .distinctUntilChanged()
            .collect { (track, playing, busy) ->
                val s = _room.value ?: return@collect
                if (!s.canControl || track == null) return@collect
                if (track.id != s.track?.id) pushLocalTrack(track, busy)
            }
    }

    /** Pushes play/pause the host or a DJ pressed (not ones made by following the room). */
    private suspend fun mirrorPlayIntents() {
        playback.playIntents.collect { play ->
            val s = _room.value ?: return@collect
            if (!s.canControl || s.track == null || playback.state.value.currentTrack?.id != s.track.id) return@collect
            if (play == s.playing) return@collect
            localAction()
            control(if (play) "play" else "pause", JSONObject().put("positionMs", playback.currentPositionMs()))
        }
    }

    /**
     * Makes the room play [track] from where this phone is. Still loading: the
     * room schedules the start instead. Paused here: the room starts paused too.
     */
    private fun pushLocalTrack(track: Track, busy: Boolean) {
        if (socket == null || _room.value?.connected != true) return // sent again once connected
        val body = JSONObject().put("track", trackJson(track))
        if (!busy) body.put("positionMs", playback.currentPositionMs())
        localAction()
        control("setTrack", body)
        if (!busy && !playback.state.value.isPlaying) {
            control("pause", JSONObject().put("positionMs", playback.currentPositionMs()))
        }
    }

    private fun localAction() {
        localActionUntil = System.currentTimeMillis() + LOCAL_ACTION_MS
    }

    // ── Everyone: follow the room clock ─────────────────────────────────────

    private suspend fun followLoop() {
        var lastPos = -1L
        var lastAt = 0L
        var lastTrackId: String? = null
        while (scope.isActive) {
            delay(1000)
            val s = _room.value ?: continue
            val track = s.track ?: continue
            val local = playback.state.value
            if (s.canControl && local.currentTrack?.id == track.id && !local.isLoading && !local.isResolving) {
                // A host or DJ who dragged the seek bar: move the room with them.
                val pos = playback.currentPositionMs()
                val now = System.currentTimeMillis()
                val expectedStep = if (local.isPlaying) now - lastAt else 0
                if (lastTrackId == track.id && lastPos >= 0 && abs((pos - lastPos) - expectedStep) > SEEK_DETECT_MS) {
                    localAction()
                    control("seek", JSONObject().put("positionMs", pos))
                }
                lastPos = pos
                lastAt = now
                lastTrackId = track.id
            } else {
                lastPos = -1
            }
            if (follow(s, track)) lastPos = -1
        }
    }

    /** Brings this phone's player to where the room is. True when it seeked or switched songs. */
    private fun follow(s: RoomUiState, track: Track): Boolean {
        if (System.currentTimeMillis() < localActionUntil) return false
        val local = playback.state.value
        if (local.currentTrack?.id != track.id) {
            playback.playTrack(track, listOf(track), source = "Listening room ${s.roomId}")
            return true
        }
        val expected = expectedPositionMs(s)
        val busy = local.isLoading || local.isResolving
        if (expected < 0) { // a scheduled start a moment from now
            if (!busy) setPlaying(false)
            return false
        }
        if (busy) return false
        setPlaying(s.playing)
        if (!s.playing) return false
        val drift = playback.currentPositionMs() - expected
        if (System.currentTimeMillis() - lastFollowLog > 5_000) {
            lastFollowLog = System.currentTimeMillis()
            Log.d(TAG, "follow ${track.id}: local=${playback.currentPositionMs()} room=$expected drift=$drift offset=$clockOffsetMs")
        }
        return when {
            abs(drift) > SEEK_THRESHOLD_MS -> {
                playback.nudgeRate(1f); nudging = false
                playback.seekTo(expected.coerceAtMost(track.durationSeconds * 1000L))
                true
            }
            abs(drift) > NUDGE_START_MS -> { playback.nudgeRate(if (drift > 0) 0.97f else 1.03f); nudging = true; false }
            nudging && abs(drift) <= NUDGE_STOP_MS -> { playback.nudgeRate(1f); nudging = false; false }
            else -> false
        }
    }

    private fun setPlaying(play: Boolean) = playback.setPlaying(play)

    // ── Wire ────────────────────────────────────────────────────────────────

    private suspend fun syncClockForever() {
        while (scope.isActive) {
            delay(60_000)
            bestRttMs = Long.MAX_VALUE // re-measure from scratch each minute
            repeat(5) {
                send("ping", JSONObject().put("t0", System.currentTimeMillis()))
                delay(200)
            }
        }
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            scope.launch {
                backoffMs = 2_000
                _room.update { it?.copy(connected = true, error = null) }
                repeat(5) { send("ping", JSONObject().put("t0", System.currentTimeMillis())); delay(200) }
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            scope.launch { handle(text) }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            scope.launch {
                if (socket !== webSocket) return@launch
                Log.w(TAG, "room closed: $code $reason")
                if (reason == "room_full") {
                    socket = null
                    _room.update { it?.copy(connected = false, error = "This room is full.") }
                } else reconnectLater("Reconnecting…")
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            scope.launch {
                if (socket !== webSocket) return@launch
                Log.w(TAG, "room connection lost: ${t.javaClass.simpleName} ${t.message} (HTTP ${response?.code})")
                if (response?.code == 404) {
                    // The room ended (or never existed): nothing to reconnect to.
                    socket = null
                    _room.update { it?.copy(connected = false, error = "This room has ended.") }
                    return@launch
                }
                reconnectLater(if (response?.code == 401) "Signing in again…" else "Reconnecting…")
            }
        }
    }

    private fun handle(text: String) {
        val message = runCatching { JSONObject(text) }.getOrNull() ?: return
        val body = message.optJSONObject("body") ?: JSONObject()
        when (message.optString("type")) {
            "pong" -> {
                val t0 = body.optLong("t0")
                val rtt = System.currentTimeMillis() - t0
                if (t0 > 0 && rtt in 0 until bestRttMs) {
                    bestRttMs = rtt
                    clockOffsetMs = body.optLong("serverTime") - (t0 + rtt / 2)
                }
            }
            "room.welcome" -> _room.update { it?.copy(you = body.optString("you"), role = body.optString("role")) }
            "room.state" -> _room.update { current ->
                val members = body.optJSONArray("members").toList { m ->
                    RoomMember(m.optString("code"), m.optString("displayName"), m.optString("role"))
                }
                val you = current?.you.orEmpty()
                current?.copy(
                    hostCode = body.optString("hostCode").takeIf(String::isNotBlank),
                    members = members,
                    role = members.firstOrNull { it.code == you }?.role ?: current.role,
                    track = body.optJSONObject("track")?.let(::parseTrack),
                    playing = body.optBoolean("playing"),
                    anchorPositionMs = body.optLong("anchorPositionMs"),
                    anchorServerTime = body.optLong("anchorServerTime"),
                    queue = body.optJSONArray("queue").toList(::parseTrack),
                )
            }.also {
                val s = _room.value ?: return@also
                val local = playback.state.value
                if (s.track == null && s.canControl) {
                    // A new room (or one that ran dry): start it with what this phone is playing.
                    local.currentTrack?.let { pushLocalTrack(it, local.isLoading || local.isResolving) }
                } else s.track?.let { follow(s, it) }
            }
            "room.chat" -> _room.update {
                it?.copy(chat = (it.chat + RoomChatLine(body.optString("from"), body.optString("displayName"), body.optString("text"))).takeLast(100))
            }
            "room.suggestion" -> _room.update { s ->
                body.optJSONObject("track")?.let(::parseTrack)?.let { track ->
                    s?.copy(suggestions = (s.suggestions + (body.optString("displayName") to track)).takeLast(20))
                } ?: s
            }
            "error" -> Log.w("MoodifyRoom", "room error: ${body.optString("code")}")
        }
    }

    private fun send(type: String, body: JSONObject) {
        socket?.send(JSONObject().put("v", 1).put("type", type).put("body", body).toString())
    }

    private fun control(action: String, body: JSONObject) = send("room.control", body.put("action", action))

    private fun trackJson(track: Track) = JSONObject()
        .put("trackId", track.id).put("title", track.title).put("artist", track.artist)
        .put("artworkUrl", track.albumArtUrl).put("durationMs", track.durationSeconds * 1000L)

    private fun parseTrack(json: JSONObject) = Track(
        id = json.optString("trackId"),
        title = json.optString("title"),
        artist = json.optString("artist"),
        albumArtUrl = json.optString("artworkUrl"),
        durationSeconds = (json.optLong("durationMs") / 1000).toInt(),
    )

    private fun <T> JSONArray?.toList(map: (JSONObject) -> T): List<T> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it)?.let(map) }

    private companion object {
        const val TAG = "MoodifyRoom"
        const val SEEK_THRESHOLD_MS = 1000L
        const val NUDGE_START_MS = 40L
        const val NUDGE_STOP_MS = 20L
        const val SEEK_DETECT_MS = 2500L
        const val LOCAL_ACTION_MS = 3000L
    }
}
