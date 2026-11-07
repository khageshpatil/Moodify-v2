# Moodify Social & Web Parity: Design

*Written 2026-11-06. Companion to [ANDROID_POLISH_ROADMAP.md](ANDROID_POLISH_ROADMAP.md).*

## 1. What we're building

Moodify becomes a **private, music-first social app** that works the same on Android and the web.

**Features:**
- **Your Moodify code is your identity.** No phone number, no email, no photos.
- **Friends:** add someone by code, QR or invite link, and see what they're listening to right now.
- **Sharing:** send songs, albums, artists and playlists to friends.
- **Chat:** music-first, end-to-end encrypted, text and emoji only.
- **Listen together:**
  - **Follow a friend:** your player mirrors theirs.
  - **Room:** one shared queue playing in sync on every device.
- **Web app** rebuilt to match the Android design, with your library synced between your devices.

**Not building:**
- photos, voice notes or video
- public profiles or a public feed
- contact-book discovery
- group chats outside listening rooms (maybe later)
- ads or analytics

## 2. Privacy promises

These are the rules every design choice below has to follow.

1. **We never ask for a phone number, email, real name, contacts or photos.**
2. **The server sees as little as possible.**
   - It never sees message contents or your synced library. Both are encrypted on your device.
   - "What you're playing" passes through memory only and is never written to disk.
3. **You control what you share.**
   - Sharing what you listen to can be turned off, and there's an invisible mode.
   - Read receipts are optional.
4. **Strangers can't reach you.** Messages need an accepted friend request, and anyone can be blocked.
5. **Deleting your account is immediate and complete.**
6. **The trade-off, stated plainly:** with no email, the **recovery key** is the only way back into your account. The app makes saving it part of setup and reminds you later.

## 3. What exists today and what happens to it

| Today | What happens |
|---|---|
| **Web anonymous identity:** browser storage, UUID, recovery code (`useAnonymousIdentity.ts`) | **Replaced** by key-based identity (§5). Existing users get a one-time "upgrade your identity" step. |
| **Web friends and chat:** direct browser-to-browser connections via PeerJS (`useFriends.ts`, `ChatDialog.tsx`) | **Replaced.** Direct connections can't deliver to offline friends, and phones can't keep them open in the background. |
| **Web listen together:** PeerJS, host plus up to 10 guests, heartbeats (`useEnhancedListenTogether.ts`) | **Replaced** by server rooms (§9). We keep the ideas: heartbeats, a guest list, a host role. |
| **Web password-protected playlist links** (LZ-compressed URLs) | **Kept** as "share outside Moodify" links. Inside Moodify, sharing goes through chat. |
| **`moodify-worker`** (music catalogue, playback relay) | **Untouched.** Social runs as a separate worker, `moodify-social`, so a problem in one can't take down the other. |
| **Android app** | Gets a new `social/` package, a Friends tab or entry point, and listen-together controls in the player. |

## 4. Architecture

```
 Android app ─┐                         ┌─ D1 (SQLite): accounts, friendships, blocks, reports
              │  HTTPS (account/friend  │
 Web app ─────┤  API) + WebSocket       ├─ UserHub (one Durable Object per user)
              ▼                         │    - live connections for that user's devices
        moodify-social (Worker) ────────┤    - presence: what you're playing (memory only)
                                        │    - encrypted message inbox (deleted after 30 days)
              Push (Google FCM) ◄───────┤    - encrypted library sync data
                                        └─ Room (one Durable Object per listening room)
                                             - members, roles, queue, playback clock
```

- **Why Cloudflare Workers with Durable Objects:**
  - We already deploy there.
  - Each Durable Object is a single place that holds a user's or a room's live connections, so fan-out and ordering are simple.
  - Hibernating WebSockets keep idle connections almost free.
  - There are no servers to manage.
- **One live connection per device.** Every client opens a single WebSocket to its own UserHub. Presence, chat and room events all travel over it. While you're in a room, the client also opens a WebSocket to that Room.
- **Cost:** the free plan should cover early use. Expect the $5/month paid plan once there are real users. *To check against current Cloudflare pricing before launch.*

## 5. Identity

### Keys and the code
- **First launch:**
  - The app makes a random 32-byte **seed**.
  - Two key pairs come from the seed:
    - **Ed25519**, for signing in
    - **X25519**, for encryption
  - One crypto library on both platforms: **libsodium** (lazysodium on Android, libsodium.js on the web). Both apps then produce byte-identical results.
- **The Moodify code:**
  - The first 40 bits of `SHA-256(signing public key)`, written in Crockford base32 (no ambiguous characters such as I/1 or O/0), for example **`MOOD-7K3F-92QX`**.
  - The server enforces that codes are unique. On the rare clash, the client makes a new seed.
- **Display name:** 2–24 characters, can be changed at any time, with a light word filter. The avatar is generated from the code.

### Signing in
1. The client asks for a challenge (`POST /auth/challenge`) and gets back a random value (a nonce).
2. It signs the nonce with its signing key and sends it to `POST /auth/verify`.
3. It receives a **session token** valid for 24 hours, renewed silently.

No passwords are involved.

### Recovery and more devices
- **Recovery key:** the seed, shown as 24 words (BIP-39 word list) and as a QR code. You save it during setup, and Settings › Account › Recovery key shows it again.
- **Linking a device ("Link the web app"):**
  - The new device shows a QR code containing a short-lived X25519 public key.
  - The phone scans it and sends the seed encrypted to that key, through the server.
  - Both devices now share the same identity.
  - Each device keeps its own session and its own push token.
- **Lost device:** sign in elsewhere with the recovery key, then "Sign out other devices".
- **Delete account:** removes the account row, friendships, the inbox, sync data and push tokens. Friends see "Deleted user".

## 6. Friends

- **Adding a friend:** enter a code, scan a QR, or open an invite link (`https://<web-domain>/add/MOOD-7K3F-92QX`). The link opens the app if installed, otherwise the web app.
- **Requests:**
  - A request shows the sender's code, name and an optional note (60 characters).
  - The other person can accept, ignore (no notification is sent) or block.
- **Limits:**
  - 500 friends
  - 30 outgoing requests a day
  - repeated requests to the same person are blocked for 7 days after an ignore
- **Blocking:** silent. The blocked person sees nothing different, but their messages, requests and room invites are dropped.
- **Reporting:**
  - Because chat is end-to-end encrypted, the server can't read messages.
  - The person reporting chooses which messages to include, and their app sends that plain text with the report.
  - Reports go to a D1 table for manual review.
  - Required anyway for the Play Store's rules on apps with user content.

## 7. Listening now (presence)

- **When a song starts, pauses or is skipped,** the client sends to its UserHub:
  `presence.update { trackId, title, artist, artworkUrl, durationMs, positionMs, playing, sentAt }`
- **Who gets it:** the UserHub forwards it to friends who are online right now, and to nobody else. Nothing is written to disk.
- **Friends list display:**
  - "▶ Song – Artist" with a live progress bar
  - "Last played 20 min ago" (kept in memory only, cleared when the UserHub goes idle)
  - otherwise "Offline"
- **Controls in Settings › Privacy:**
  - **Share what I'm listening to:** Everyone / Selected friends / No one
  - **Invisible mode:** you appear offline but can still see others
- **From a friend's activity:** tap → Play this song, Start radio from it, Follow along (§9), or Send a message.

## 8. Sharing and chat

### Message types (inside the encrypted content)
| Type | Contents |
|---|---|
| `text` | Up to 2,000 characters, emoji allowed |
| `track` | id, title, artist, artwork, duration |
| `collection` | album or YouTube playlist: browse id, title, artwork, count |
| `playlist_snapshot` | your own playlist: name plus up to 500 songs. The friend can save a copy. |
| `artist` | browse id, name, artwork |
| `reaction` | emoji, plus the id of the message it reacts to |
| `room_invite` | room code and the song currently playing |

No images and no file uploads, by design.

### Encryption
- **For each friend pair:** a shared key from X25519 (libsodium `crypto_box`). Each message gets a random nonce.
- **Ciphertext is all the server sees:**
  - Each message is stored as `{to, from, id, sentAt, ciphertext}` in the recipient's UserHub.
  - Every device of the recipient pulls new messages from its own position in the inbox and confirms what it has received.
  - A message is deleted once all the recipient's devices have confirmed it, or **after 30 days**, whichever comes first.
- **No forward secrecy in v1,** because Signal's Double Ratchet is a big job. If a seed leaks, past messages stored on devices could be read. Acceptable for a music-sharing chat. A later upgrade is noted in §15.
- **Message history:** stored only on each device (Room on Android, IndexedDB on the web). Library sync (§11) can optionally back it up, encrypted.

### Notifications
- **Android:** Firebase Cloud Messaging data messages with **no content**, only `{kind: "message"}` (not even who it's from). The app wakes up, downloads and decrypts the message, then shows "Asha: 🎵 Kesariya" on the device.
- **Web:** Web Push with the same content-free payload. This is optional and comes later; in v1 the web gets messages while it's open.
- **What Google sees:** only that a notification went to a device token. The FCM token is per device and carries no personal information. Still, it's the one Google touchpoint, and the privacy page says so.

### Chat screen
- A conversation list, each with the friend's code-generated avatar and what they're playing now.
- A thread view where song cards are playable inline: tap ▶ to play, long-press for the song menu.
- Composer: text field, emoji, a **"+" button to attach a song** (search or pick from Library), and a quick "Share what I'm playing" button.
- Optional read receipts and an optional typing indicator, both off by default.

## 9. Listen together

### Modes
- **Follow a friend:** a one-way room opened from a friend's activity. Your player mirrors theirs, and you can leave at any time.
- **Room:**
  - Created from the player ("Start a room") with a 6-character code and an invite link.
  - Roles:
    - **Host:** controls everything.
    - **DJ:** can add to the queue and skip.
    - **Listener:** can suggest songs.
  - Up to 20 people.
  - Includes a room chat that is temporary and not stored.
  - Only people who are invited can join. There are no public rooms.

### How sync works
No audio is ever streamed between devices. **Every device plays the song from YouTube itself.** The Room only shares a clock and the queue.

1. **Clock offset:**
   - When joining, each client sends 5 pings: `t0` is when the ping is sent, `ts` is the server time in the reply, `t1` is when the reply arrives.
   - Using the sample with the fastest round trip: `offset = ts − (t0 + t1)/2`.
   - This is re-measured every 60 seconds.
2. **Room state:**
   `{ trackId, anchorPositionMs, anchorServerTime, playing, queue[], version }`
   The expected position right now is `anchorPositionMs + (serverNow − anchorServerTime)` while playing.
3. **Correcting drift** (checked every second):
   | How far off | What we do |
   |---|---|
   | Less than 40 ms | Nothing |
   | 40 ms to 1 s | Play at 1.03× or 0.97× until within 20 ms (Android: ExoPlayer `PlaybackParameters(speed, pitch = 1f)` changes speed without changing pitch) |
   | More than 1 s | Seek |
4. **Changing songs:**
   - The host's action becomes `track.scheduled { trackId, startAtServerTime = now + 1.5 s }`.
   - Every device has already pre-loaded the next song from the shared queue, so they all start on the same beat.
5. **Joining mid-song:** load the stream, buffer it, then seek to the expected position plus the time spent resolving. Everyone else carries on.
6. **Buffering or a weak network:** a device that falls behind never pauses the room. It catches up by seeking and shows "Catching up…".
7. **Song not available in a guest's region:** that device stays silent for this song, shows "Not available in your region", and rejoins on the next song.
8. **Host leaves:** the longest-present DJ becomes host. If there's none, the longest-present listener. The room ends when it's empty for 2 minutes.

### Platform notes
- **Android:**
  - While in a room, the PlaybackManager is driven by the Room instead of the local queue.
  - The notification shows "Listening with Asha's room".
  - Leaving restores your own queue.
- **Web:**
  - Syncs precisely with the `HTMLAudioElement` engine (`PlaybackEngine.ts`), using `playbackRate` with `preservesPitch`.
  - The YouTube iframe engine only supports coarse speed steps. With it, drift is corrected by seeking only, so expect slightly looser sync.

## 10. Protocol

Every WebSocket frame uses the same JSON envelope:

```json
{ "v": 1, "type": "presence.update", "id": "01J…", "ts": 1762400000000, "body": { } }
```

| Group | Types |
|---|---|
| Session | `hello`, `ping` / `pong`, `error` |
| Presence | `presence.update`, `presence.friend` |
| Friends | `friend.request`, `friend.accepted`, `friend.removed` |
| Messages | `msg.new` (only tells you something arrived), `msg.ack` |
| Room | `room.state`, `room.join`, `room.leave`, `room.control` (play/pause/seek/skip/queue ops), `track.scheduled`, `room.chat`, `room.role` |
| Sync | `sync.changed` (a device should pull library changes) |

Account and friend operations go over HTTPS:
- `/auth/*`
- `/me`
- `/friends/*`
- `/messages?after=`
- `/sync/*`
- `/rooms`
- `/report`

All of it is described in one shared schema file in `shared/social-protocol/`, so Android and the web can't drift apart. The version number `v` lets old apps keep working while new types are added.

**Rate limits** (enforced by each UserHub):
- 30 messages a minute
- 10 friend requests a minute
- 4 room controls a second
- 1 presence update a second

## 11. Web rebuild and library sync

### Web rebuild
- **Keep:** React, Vite, Tailwind and the playback engines. **Rebuild:** the interface, using Android's "Aura" design.
- **Shared design values:** colours, fonts, spacing and corner radii are exported from Android's theme into one `design-tokens.json`. CSS variables and Tailwind's settings are built from that file, so a colour change happens once and applies to both apps.
- **Screens to match:**
  - Home: mood pills, quick picks, shelves
  - Search: filters, recent searches
  - Library: tabs, saved items
  - Album and artist detail pages
  - Full player with queue and lyrics
  - Settings
  - New: Friends, Chat and Rooms
- **Remove:** PeerJS, the old identity and its onboarding, the live-streaming sync (YouTube/Twitch), and other features nobody uses. A web-app audit will produce the full list before the rebuild starts.
- **Responsive:**
  - Phone-width layout first.
  - On desktop: a sidebar for navigation, plus a right-hand panel for the queue, lyrics or friends.

### Library sync between your devices
- **What syncs:** likes, playlists, saved albums, playlists and artists (Android Sprint 2.4), history, settings, and optionally the chat backup.
- **How:**
  - Each item is an encrypted record: `{collection, itemId, updatedAt, deleted, ciphertext}`.
  - The key comes from your seed, so only your devices can read it.
  - Each device pushes its changes and pulls everything after its last position.
- **Conflicts:** the newest change wins, for each item. A playlist's song order is one item, so the latest reorder wins.
- **The server stores** only ciphertext, which it can't read.

## 12. What is stored, where, and for how long

| Data | Where | How long | Can the server read it? |
|---|---|---|---|
| Moodify code, public keys, display name | D1 | Until the account is deleted | Yes, it's needed to find you |
| Friend list, blocks | D1 | Until removed | Yes |
| What you're playing now | UserHub memory | While you're online | Yes, but it's never saved |
| Messages | UserHub storage | Until delivered, at most 30 days | No, encrypted |
| Library sync | UserHub storage | Until deleted | No, encrypted |
| Room state and room chat | Room memory | While the room is active | Yes, never saved |
| Push token | D1 | Until sign-out | Yes, but it's only a device token |
| Reports | D1 | 90 days | Only the messages the reporter chose to include |
| IP addresses | Not logged by us. Cloudflare keeps standard edge logs. | — | — |

The app needs a short privacy page that says this in plain words, and a 13+ age statement.

## 13. Milestones

Each milestone ends with an installable build and a test on your phone.

| # | Milestone | Done when |
|---|---|---|
| **M0** | **Prerequisites:** Android Sprint 1, plus 2.4 (saving albums, playlists and artists) | Search and artist bugs fixed; Library holds saved collections |
| **M1** | **Social server and identity:** the `moodify-social` worker, D1 schema, auth, UserHub; Android onboarding for the code and recovery key | A new install gets a code; the recovery key restores it on a second emulator |
| **M2** | **Friends, listening now and sharing (Android):** requests, block, report, presence, sending song and playlist cards (no free text yet) | Two emulators add each other, see each other's song live, and send a song that plays on tap |
| **M3** | **Web rebuild, part 1:** design values, the new interface, new identity and friends, linking via QR | The web app looks like Android; linking a phone by QR shows the same code and friends |
| **M4** | **Listen together (Android + web):** rooms, follow mode, clock sync, scheduled song starts | A phone and a browser stay within about 50 ms over a 10-minute room, including skips, seeks and a guest rejoining |
| **M5** | **Chat:** encrypted text, reactions, Android notifications, read receipts and typing (optional) | A message to a phone with the app closed arrives as a notification; the server stores only ciphertext |
| **M6** | **Library sync:** likes, playlists, saved items, history | A like on the phone shows on the web within seconds; edits made offline merge correctly |
| **M7** | **Hardening:** a load test with 20 people in one room, rate limits, report review, privacy page, account deletion | A full pass of the privacy checklist (§2); deleting an account removes everything |

**Suggested order:** M0 → M1 → M2 → (M3 alongside M4) → M5 → M6 → M7.

**Status (2026-11-07):** M0–M3 done and tested on the emulator. M4 rooms are done on Android and the web (protocol: `docs/social-protocol/rooms.md`): host, DJ and listener roles, clock sync, queue, suggestions, room chat, invites, and rejoining. Each device was tested against a scripted second listener, and each held within 50 ms of the room. Following a friend (a one-way room fed by their "listening now") is done on both. Not done yet: the 10-minute phone-and-browser test in one room, which needs a real phone because the emulator can't run the app and Chrome together. Nothing is deployed: the social server has only run locally.

M2 alone already makes Moodify social: friends, live activity and song sharing.

## 14. Decisions needed

1. **Recovery key format:** 24 words (recommended; easiest to write down) or a QR image or file?
2. **Notifications through Google (FCM):** decided 2026-11-07: yes, content-free wake-up pings (Firebase project `moodify-2082c`). Phones without Google services keep the 15-minute background check. Built as described in `social-protocol/chat.md` §3.
3. **Chat from the start (M5), or social without chat first (M2 to M4)?** I recommend shipping M2 first and seeing how friends use it.
4. **Web domain:** where will the web app and invite links live? This decides the `https://<domain>/add/…` links.
5. **Room size:** is 20 enough?

## 15. Risks

| Risk | Plan |
|---|---|
| **The music comes from YouTube through unofficial access.** A social app gets more visible as it grows, and YouTube changes can break playback. | Share the app by APK or invite link, not the Play Store. Keep the social server separate, so friends, chat and sharing keep working even if playback breaks for a while. |
| **Sync drifts on weak networks.** | Catch-up by seeking; the room never waits for the slowest device; a visible "Catching up…" state. |
| **Abuse even without photos** (spam, harassment through names or text) | Friend-only messaging, silent blocking, rate limits, a name filter, reports. |
| **A lost recovery key means a lost account.** | Saving it is a required setup step, with a reminder after 7 days and a "Have you saved your key?" check in Settings. |
| **No forward secrecy in v1** | Documented. Possible upgrade later: per-session keys, or Signal's protocol libraries. |
| **Durable Object or Cloudflare limits or pricing change** | All protocol logic lives in plain modules, so the server could move to a Node + Redis service if needed. |
