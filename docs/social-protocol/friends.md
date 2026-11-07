# Moodify friends, presence and shares protocol, v1 (M2)

This document builds on [identity.md](identity.md).

**Reference implementations:**
- **Server:** `social/src/api.mjs` and `social/src/hub.mjs`
- **JavaScript client:** `social/src/client-keys.mjs`
- **Android:** `data/social/SocialRepository.kt` and `MoodifyIdentity.sealFor` / `openFrom`

## 1. Friends (HTTP, Bearer auth)

| Method & path | Body → response | Notes |
|---|---|---|
| `GET /v1/friends` | → `{friends, incoming, outgoing}` | Each entry is a user `{code, displayName, boxPub, since, note?}` |
| `POST /v1/friends/requests` | `{code, note?}` → `{status, user}` | See the status list below. |
| `POST /v1/friends/requests/{code}/accept` | → `{status:"friends"}` | Both people's hubs get `friend.accepted`. |
| `POST /v1/friends/requests/{code}/ignore` | → `{status:"ignored"}` | The sender isn't told. Repeat requests are dropped silently for 7 days. |
| `DELETE /v1/friends/{code}` | → `204` | Removes the friend, or cancels a request. |
| `PUT /v1/blocks/{code}` / `DELETE` / `GET /v1/blocks` | → `204` / `{blocked}` | A block is silent: it ends the friendship, drops their queued messages, and makes their future requests and messages look sent without delivering them. |
| `POST /v1/reports` | `{code, reason, evidence?}` → `201` | Evidence is plain text the reporter chooses to include. |

The possible statuses for a friend request:

| Status | When |
|---|---|
| `201 pending` | The request was sent. |
| `200 friends` | Either you were already friends, or they had already asked you, in which case the server accepts automatically. |
| `202 pending` | The request was silently dropped, because you're blocked or were ignored. |
| `400` | The code is your own, or malformed. |
| `404` | No one has that code. |
| `429` | A limit was reached. |

**Limits:**
- 500 friends
- 30 outgoing requests per day
- 60-character notes

## 2. Encrypted inbox (HTTP)

| Method & path | Body → response |
|---|---|
| `POST /v1/messages` | `{to, nonce, ciphertext}` (base64url; the nonce is 12 bytes) → `{id, sentAt}`. `403 not_friends` if you aren't friends. Plaintext is refused. |
| `GET /v1/messages?after={id}` | → `{messages: [{id, from, nonce, ciphertext, sentAt}]}`, oldest first, up to 100 |
| `POST /v1/messages/ack` | `{upTo}` → `204`. Deletes your messages up to and including that id. |

**What the server keeps:** messages wait until the recipient acknowledges them, or 30 days at most. The server never sees plaintext.

**Known limit:** acknowledging deletes a message for *all* of the recipient's devices. Delivery to each device separately comes with M6 (library sync).

## 3. Encryption

### 3.1 The cipher

```
shared = X25519(myBoxPrivate, theirBoxPub)
key    = HKDF-SHA256(ikm = shared, salt = "moodify-box-v1", info = min(codeA, codeB) + "|" + max(codeA, codeB), L = 32)
cipher = AES-256-GCM(key, nonce = 12 random bytes, aad = "moodify-msg:v1:{senderCode}:{recipientCode}")
```

The associated data (`aad`) binds the direction of the message. A ciphertext can't be replayed as if the other person had sent it.

**Test vector:**

| Input | Value |
|---|---|
| Sender | Seed `00`×32 (`MOOD-CQHV-YGY4`) |
| Recipient | Seed `000102…1f` (`MOOD-67AF-HHK3`) |
| Nonce | `01 02 … 0c` |
| Plaintext | `{"v":1,"type":"track","track":{"id":"JGwWNGJdvx8","title":"Shape of You","artist":"Ed Sheeran"}}` |

Expected ciphertext:

```
zwI4gG8-xcvFfq7dUM7Q8QtLynjZWv8pzPUZMiePwuUZmftS6BxqaecYXeen0Ym26zBZtQzMyBIydVUifi8octyXbEr5nu-uBO2Q7XPj-GOJcKrimfoq8JJjUtIneLyW6wOKm9bEq5Ebi3FoeG_vFA
```

### 3.2 What goes inside (the plaintext)

```json
{"v":1,"type":"track","track":{"id","title","artist","album","artworkUrl","durationSeconds","artistId?"}}
{"v":1,"type":"collection","collection":{"kind":"album|playlist|artist","browseId","title","artworkUrl","params?","pageType?"}}
```

Clients drop types or versions they don't know. Artwork must use `https://`. Chat (M5) adds `text` and `reaction` types to this same envelope.

## 4. Live connection (WebSocket)

**Connecting:** `GET /v1/live?token={session}&device={id}` upgrades to a WebSocket held by that user's hub (a Durable Object). Every frame looks like this:

```json
{"v": 1, "type": "…", "ts": 1762400000000, "body": {}}
```

**Sent by the client:**

| Type | Body | Behaviour |
|---|---|---|
| `ping` | `{t}` | Answered with `pong {t}`; the frame's `ts` is the server time, so clients measure their clock offset from the round trip. |
| `presence.update` | `{trackId, title, artist, artworkUrl, durationMs, positionMs, playing, seek?}` or `{cleared:true}` | The server keeps only these fields, and only in memory. A position-only update reaches friends at most once a second; one marked `seek: true` (the position jumped) at most 4 times a second. A song change or play/pause goes out at once. Clients send the player's live position, and refresh it every 15 s while playing. |
| `settings.update` | `{sharing}` | `false` makes you look offline to friends. |

**Sent by the server:**

| Type | Body |
|---|---|
| `hello` | `{code, friends: [{code, online, presence, lastSeenAt}]}`: what each friend is doing at the moment you connect |
| `presence.friend` | `{code, online, presence, lastSeenAt}`. `presence.at` is the server time (ms) when `positionMs` was reported, so a follower knows where the song is now (`rooms.md` §7). When a friend's last device goes offline, `presence` becomes their last song, paused, and `lastSeenAt` is set. That "last played" isn't saved, so it's forgotten if the hub restarts. |
| `friend.request` / `friend.accepted` / `friend.removed` | `{code, displayName?, note?}`. The client refreshes its friend lists. |
| `msg.new` | `{from, id}`. The client fetches and decrypts it from `/v1/messages`. |

**Who sees what:** a hub answers presence requests only from accepted friends.

**Android:** the app stays connected only while it's on screen. It disconnects 30 seconds after going to the background, and reconnects with backoff from 2 to 60 seconds.
