# Library sync, v1 (M6)

Your likes, playlists, saved albums/playlists/artists and recent plays follow you between your devices. Every record is end-to-end encrypted with a key only your devices can derive. The server stores and orders ciphertext; it can't tell a like from a playlist.

## 1. Keys and records

Keys come from the identity seed (`identity.md`) with the same HKDF-SHA256 and salt `moodify-identity-v1`:

| Key | HKDF info | Use |
|---|---|---|
| `enc` | `sync-enc` | AES-256-GCM for records |
| `id` | `sync-id` | HMAC-SHA256 for item ids |

**Item id** (what the server sees): `HMAC-SHA256(id, "{collection}\n{id}")`, first 16 bytes, base64url (22 characters). It's different for every account, so two people liking the same song get unrelated ids.

**Record:** AES-256-GCM over the plaintext JSON, 12-byte random nonce, aad `moodify-sync:v1:{code}:{item}`. A record can't be moved to another item or account.

**Plaintext:** `{"v": 1, "c": collection, "id": id, "data": {…}}`, or `{"v": 1, "c", "id", "deleted": true}` for a deletion.

**Test vectors** (seed = bytes 0…31): `enc` = `6SXc6zIZRuplpVDrbXz84sn1FU-QM-Pf3_hfaJ8rrbI`, `id` = `Gn0COqdc4VTYX46UU0FCqXrCD3faxApzqryMDBtno-Y`, item for `like` / `VAdGW7QDJiU` = `b9lMfPj_poq_PRc_NPGcdQ`. A full sealed record is in `android/…/LibrarySyncTest.kt`; the web and Android must produce the same bytes.

## 2. Server (HTTP, Bearer auth)

| Call | Body / reply |
|---|---|
| `PUT /v1/sync` | `{records: [{item, updatedAt, nonce, ciphertext}]}`, 1–200 per call, one version per item → `{accepted: [item], latest}` |
| `GET /v1/sync?after={seq}` | → `{records: [{item, seq, updatedAt, nonce, ciphertext}], latest, more}`, 500 per page, oldest change first |

- **Newest wins:** a record replaces the stored one only if its `updatedAt` (ms, the device's clock) is newer. Older ones are left out of `accepted`; the device then pulls the newer one.
- **Order:** every accepted change gets the account's next `seq`, so a device pulls "everything after where I was".
- **Live:** after an accepted push, your other devices get `sync.changed {latest}` on their live connection and pull.
- **Limits:** 20,000 records and 32 MiB per account; 512 KiB of ciphertext per record; `updatedAt` at most 10 minutes in the future.
- **Deleting your account** deletes all of it.

## 3. What syncs

| `c` | `id` | `data` | Android | Web |
|---|---|---|---|---|
| `like` | video id | `{track}` | ✓ | ✓ |
| `playlist` | the playlist's id | `{name, createdAt, pinned?, tracks: [track]}` | ✓ | ✓ |
| `saved` | `{kind}:{browseId}` | `{kind, browseId, title, subtitle?, artworkUrl?, params?, pageType?, savedAt}` | ✓ | not yet shown, left alone |
| `recent` | `plays` | `{plays: [{track, playedAt}]}`, newest first, at most 50 | ✓ | ✓ |

`track` is the shared song format (as in shares and chat): `{id, title, artist, album?, artworkUrl?, durationSeconds?, artistId?}`. A song order inside a playlist is part of the one record, so the latest reorder wins.

## 4. How a device syncs

Each device keeps a position (`seq`) and an index: for each item, its item id, the `updatedAt` it last synced, and a hash of the data it last synced. One pass:

1. **Pull** everything after the position and apply each record that's newer than the index says, then record the hash of what the library now holds for it. (So two apps that store an item slightly differently don't bounce it back and forth.)
2. **Compare** the library with the index. Push anything new or changed, with `updatedAt = max(now, last + 1)`, and a deletion for anything synced before that's gone now.
3. If the server kept a newer version of something, pull again.

Rules:
- A device only touches the collections it manages (the web leaves `saved` alone).
- A deletion from another device doesn't remove an item this device has but never synced; that item is pushed instead (a liked song on a new laptop survives an old unlike from the phone).
- Recent plays **merge** (union by song and time) instead of replacing.

When: Android on start, on connect, on `sync.changed`, 3 s after a library change, and in the 15-minute background check. The web on sign-in, on `sync.changed`, 2 s after a library change, when the tab comes back, and every 5 minutes.

Code: `src/aura/social/syncEngine.ts` + `librarySync.ts` (web), `data/social/LibrarySync.kt` + `AndroidLibrary.kt` + `SyncCrypto.kt` (Android), `social/src/api.mjs` (server).

## 5. Privacy

The server sees: how many records you have, their sizes, and when each changed. Not what they are, their names, or which songs.
