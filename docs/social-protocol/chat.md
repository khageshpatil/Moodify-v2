# Chat, v1 (M5)

Chat between friends is end-to-end encrypted with the same scheme as sharing (`friends.md` §3). The server stores and relays ciphertext only. Conversation history lives on each device: Room on Android, and in memory on the web for now. Library sync (M6) can later back it up, encrypted.

## 1. Messages

Each message is one encrypted JSON object, `{"v": 1, "type", "mid", …}`.

`mid` is the sender's id for the message, 8–64 characters from `[A-Za-z0-9_-]` (a UUID). It is the same on every device, so:
- A retry or a device's own copy never shows twice.
- A reaction can point at a message.

| type | fields | notes |
|---|---|---|
| `text` | `text` | Up to 2,000 characters; emoji allowed; empty text is dropped. |
| `reaction` | `target` (a `mid`), `emoji` | One reaction per person per message; a new one replaces yours, `""` removes it. Up to 16 characters, no ASCII letters or digits. |
| `track` | `track {id, title, artist, album, artworkUrl, durationSeconds, artistId?}` | As in M2. |
| `collection` | `collection {kind, browseId, title, artworkUrl, params?, pageType?}` | As in M2. |
| `room` | `room {id, track?}` | A listening-room invite (`rooms.md` §1). |
| `playlist` | `playlist {name, tracks: [track]}` | One of your own playlists, up to 300 songs. The friend can play it or save their own copy. |

Messages from older versions have no `mid`. Clients use `srv-{server id}` for those. Unknown types, or a newer `v`, are skipped and not shown as garbage.

## 2. Inbox, devices and copies

**Sending** is `POST /v1/messages {to, nonce, ciphertext}`. The reply is `{id, copyId, sentAt}`. The server stores two rows:

- **The friend's row.** This is skipped silently if they have blocked you.
- **A copy in your own inbox.** It has `to` set to the friend, so your other devices see the conversation. The pair key is the same in both directions. Your devices open the copy with the sender's direction in the AAD: `moodify-msg:v1:{you}:{friend}` (`openOwn` / `openSent`).

Both inboxes get a `msg.new` event over the live connection; your own copy is marked `own: true`.

**Reading** is `GET /v1/messages?after={id}`, 100 at a time. Each device keeps its own `after` position.

**Confirming** is `POST /v1/messages/ack {upTo, device}`.
- `device` is a random id per install, matching `[A-Za-z0-9_-]{8,64}`. Older clients without one share the position `legacy`.
- A message is deleted once every device that confirmed something in the last 30 days has confirmed it.
- Every message is deleted after 30 days regardless.
- A device counts only after its first confirmation, so a device that never connects doesn't hold anything back. A new device with an empty inbox checks in with `upTo: 0`; from then on the server keeps messages until it has them too. Clients check in once per session when their inbox is empty.
- An account keeps at most 10 device positions; the oldest is dropped.

**Limits:**
- Each sender can send 600 messages an hour in total; above that the server answers `429 slow_down`.
- A message's ciphertext can be up to 96 KiB (room for a shared playlist).

## 3. Notifications

Notifications are built on the device from the decrypted message: "Ravi: hey!", "🎵 Chaleya". No push service ever sees content.

| When | How |
|---|---|
| The app is open or was just closed | The live connection gets `msg.new`. The app fetches, decrypts and notifies, unless that conversation is on screen. |
| The app is closed (Android) | A wake-up ping through Firebase Cloud Messaging (below). The phone fetches, decrypts and notifies within seconds. |
| No Google Play services | A background check about every 15 minutes, which is Android's minimum. It uses no Google services. |
| No Moodify tab open (browser) | A Web Push ping with **no payload at all**. The service worker (`/moodify-sw.js`) shows "New message from a friend"; tapping it opens Moodify, which fetches and decrypts. An open, visible tab shows its own notification with the friend's name instead. |

**Wake-up pings.**
- A device registers with `PUT /v1/push {device, platform: "fcm", token}`, or a browser with `{device, platform: "webpush", subscription}` (only `endpoint` and `keys` are kept; https endpoints only). `DELETE /v1/push/{device}` removes it. Browsers get the server's key from `GET /v1/push/vapid`. `device` is the same id as in §2. An account keeps at most 10 push devices.
- When a message is stored, the server pings the friend's devices that aren't connected live. Live connections say which device they are with `/v1/live?token=…&device=…`.
- The ping is a high-priority FCM data message `{kind: "message"}`, with no sender, no content and no message id. It is collapsed (`collapse_key: inbox`) and expires after a day; the 15-minute check covers anything older.
- The server forgets tokens FCM reports as unregistered. If another account registers the same token (a different Moodify code on the same phone), the old account stops pinging it.
- Blocked senders cause no ping.
- Android asks Firebase for a token only after you have a Moodify code, and deletes it when the code is deleted. No Firebase Analytics.
- Browser pings are signed with VAPID (RFC 8292) and sent with `Urgency: high`, `Topic: inbox`, `TTL: 86400`; 404/410 forgets the subscription.
- Server secrets: `FCM_SERVICE_ACCOUNT`, the service-account JSON (raw or base64); `VAPID_PUBLIC_KEY` + `VAPID_PRIVATE_KEY` (`social/scripts/vapid-keys.mjs`). Without them, that platform isn't pinged.

Tapping a notification opens `moodify://chat/{code}`.

While Moodify is open on another screen, a new message shows as a small in-app banner (with **Open**) instead of a system notification.

## 4. Privacy

- **What the server sees:** who messaged whom, when, and how large the ciphertext was. It never sees content or reactions.
- **Blocking:** blocking someone deletes the conversation from that device, and the server drops their pending messages.
- **Deleting your account:** removes every message to, from, or copied for you, your device positions and your push tokens.
- **What Google sees (Android pings):** that a device token got a ping at a certain time. Not who sent it, and nothing about the message.
- **Not in v1:**
  - Read receipts and typing indicators are optional and off. They aren't built.
  - There is no forward secrecy. See `SOCIAL_AND_WEB_DESIGN.md` §8.
