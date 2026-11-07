# Listening rooms, v1 (M4)

A listening room lets several people hear the same song at the same moment. Every device streams and plays the song itself; no audio goes through Moodify's servers. The room shares only a **clock**, the current song, a queue, who is in it, and a short-lived chat.

The server code is `social/src/room.mjs` (logic, tested in Node) and the `Room` Durable Object in `social/src/index.mjs`. The Android client is `data/social/Rooms.kt`; the web client is `src/aura/social/RoomProvider.tsx`.

## 1. Opening and joining

| Call | What it does |
|---|---|
| `POST /v1/rooms` (Bearer) | Opens a room with you as host. Replies `201 {roomId, invite}`. `roomId` is 6 Crockford characters, for example `K7P2QX`; `invite` is `moodify://room/K7P2QX`. |
| `GET /v1/rooms/{roomId}/live?token=…` (WebSocket) | Joins the room. Replies `404 room_not_found` for a room that was never opened or has ended. |

- Anyone signed in who has the code can join. Room codes are random and short-lived, and a room ends 2 minutes after its last member leaves.
- A room holds at most **20 people**. A join over the limit is accepted and then closed with code `4003`, reason `room_full`.
- One person on several devices counts once, and keeps their role.

Invites to friends travel as an ordinary end-to-end encrypted message (`friends.md` §3.2) with this payload:

```json
{"v":1,"type":"room","room":{"id":"K7P2QX","track":{"id":"JGwWNGJdvx8","title":"…","artist":"…","artworkUrl":"https://…","durationSeconds":263}}}
```

`track` is optional and only used for the preview. Clients drop an invite whose `id` isn't 6 Crockford characters.

## 2. Roles

| Role | Can |
|---|---|
| **host** | Everything a DJ can, plus make others DJ, listener or host. |
| **dj** | Play, pause, seek, skip, change the song, add to or remove from the queue. |
| **listener** | Listen and chat. Their queue picks go to everyone as `room.suggestion`; a host or DJ decides whether to add them. |

The person who opened the room is host. When the host leaves, the longest-present DJ becomes host, or else the longest-present listener.

## 3. The room clock

The room state carries an **anchor**: the song position `anchorPositionMs` at server time `anchorServerTime`. While `playing`, the position now is

```
positionMs = anchorPositionMs + (serverNow − anchorServerTime)
```

and while paused it is just `anchorPositionMs`. A negative position means the song is scheduled to start a moment from now.

**Clock offset.** Each client sends `ping {t0}` five times when it connects and again every minute. The server answers `pong {t0, serverTime}`. The client keeps the reply with the shortest round trip and sets `offset = serverTime − (t0 + rtt / 2)`, so `serverNow = localNow + offset`.

**Who sets the anchor.** A host's or DJ's own player is the source of truth:

- `setTrack` with `positionMs` anchors the room to where their player already is. Without `positionMs` (their player is still loading), the room schedules the start 1.5 s ahead so every device can buffer.
- `play` and `pause` carry the exact `positionMs` their player resumed or stopped at.
- A host or DJ who drags the seek bar sends `seek` with their new position.
- Only play/pause the person asked for is sent (Android: `PlaybackManager.playIntents`). Pauses the app makes itself, such as waiting for a scheduled start or loading a song, are never sent.

**Following.** Once a second, every device compares its player with the room:

| Difference | Action |
|---|---|
| A different song | Play the room's song. |
| More than 1 s off | Seek to the room's position. |
| 40 ms – 1 s off | Play 3% faster or slower (pitch kept) until within 20 ms. |
| Room paused or playing | Match it. |

In testing on the emulator, a phone that joined 38 s behind was within 0.5 s after its first seek, and within 50 ms about 15 s later. A browser held about 15 ms from the room clock once it had caught up.

**The web.** The web player follows the same rules (`src/aura/social/roomSync.ts`, tested against the Android constants), with three differences:

- **Tap to start.** Browsers block sound until the person taps, so a browser that joined from a link shows "Tap to start listening" and doesn't retry playback until then.
- **Seeking reopens the stream.** Moodify's media relay streams a song from start to end without byte ranges, so the browser can't jump ahead in it. The player reopens the stream at the target time (`/api/media/{id}?startMs=`), and the new stream keeps the song's own timeline. Because that takes a few seconds, the player aims ahead of the room by its measured startup time.
- **Early means wait.** A browser up to 6 s ahead pauses until the room catches up, rather than seeking back, which would mean reopening again.

A browser tab that reloads rejoins the room it was in.

**End of a song.** The server sets an alarm for the end of the song on the room clock and then starts the next song in the queue, scheduled 1.5 s ahead. With an empty queue, the room pauses at the end.

## 4. Messages

Every message is an envelope `{"v":1, "type", "body"}`. The server adds a `sentAt` to the ones it sends.

**Client → server**

| type | body |
|---|---|
| `ping` | `{t0}` |
| `room.chat` | `{text}`: whitespace collapsed, at most 300 characters |
| `room.role` | `{code, role}`: host only; `role` is `host`, `dj` or `listener` |
| `room.control` | `{action, …}`: see below |

`room.control` actions (host and DJs, except as noted):

| action | body |
|---|---|
| `setTrack` | `{track, positionMs?}` |
| `play` | `{positionMs?}` |
| `pause` | `{positionMs?}` |
| `seek` | `{positionMs}` |
| `next` | `{}` |
| `queueAdd` | `{track}`. From a listener this becomes a `room.suggestion`. |
| `queueRemove` | `{index}` |

A `track` is `{trackId, title, artist, artworkUrl, durationMs}` and is cleaned like presence (`friends.md`): an 11-character YouTube id, text trimmed, https artwork only. A queue holds at most 200 songs.

**Server → client**

| type | body |
|---|---|
| `room.welcome` | `{you, role}`, sent on joining |
| `room.state` | `{roomId, hostCode, track, playing, anchorPositionMs, anchorServerTime, queue, members[{code, displayName, role, joinedAt}], positionMs, serverTime, version}`, sent after every change |
| `track.scheduled` | `{track, startAtServerTime}` |
| `room.chat` | `{from, displayName, text}` |
| `room.suggestion` | `{from, displayName, track}` |
| `pong` | `{t0, serverTime}` |
| `error` | `{code}`: `not_allowed`, `bad_role`, `bad_track`, `bad_json`, `unknown_type` or `unknown_action` |

## 5. Privacy

- The server sees which songs a room plays and who is in it, as it must to keep the clock. Room chat is **not** end-to-end encrypted; the app says so in the chat box. It is never stored: it goes only to whoever is connected at that moment.
- Room state (song, clock, queue) is kept only while the room exists, and deleted 2 minutes after the last person leaves.
- Display names, never Moodify codes, are shown to other members in the app. Codes are in the messages so a host can assign roles.

## 6. Clients

- **Reconnecting.** A dropped connection is retried with backoff from 2 to 30 seconds while the person is still in the room. `404` means the room has ended; the client stops.
- **Keep-alive.** Clients send a WebSocket ping every 20 s, with no read timeout; a quiet room is normal.
- **Links.** A `moodify://room/{id}` link is joined once. Android replays a task's original link when it relaunches the app, and room codes are single-use, so a replayed link is ignored.
