# Moodify — product and engineering plan

This plan turns Moodify into a dependable, catalog-first native music client while keeping its own identity. It uses ArchiveTune as architectural evidence, not as source code to copy.

## North-star experience

Open Moodify, understand the listening context immediately, find a real song or catalog entity in seconds, start playback reliably, and continue from the mini-player, lock screen, notification, queue, lyrics, or a detail page without losing state.

## Phase 0 — reliability gate (current)

Playback is the gate for every other feature.

- Treat every InnerTube result as an immutable stream contract: URL, format, MIME type, expiry, client profile, user agent, request headers, and content length.
- Prefer a validated audio format instead of always selecting MP4/itag 140.
- Probe a direct stream with the same headers before handing it to ExoPlayer.
- Include a contract fingerprint in cache keys so an expired or rejected URL cannot reuse a previous cache span.
- On HTTP 403, stop the old source, discard its cache span and contract, resolve a fresh candidate, and retry once per distinct client/format.
- Keep direct Android playback device-to-YouTube. Use the server for discovery and metadata, not for media bytes.
- Add resolver diagnostics: client, format, expiry, response code, range support, and fallback reason.

Acceptance: three different tracks from search and Home reach `PLAYING`, seek does not restart, next-track preload does not create a stale URL, and a 403 produces a visible retry state rather than a blank player.

## Phase 1 — catalog foundation

Replace flattened discovery-only assumptions with typed catalog entities:

- Track
- Album
- Artist
- Playlist
- Video
- Mood
- Genre
- Chart

Build the surfaces ArchiveTune users expect: Home shelves, Explore, moods/genres, typed search filters, album detail, artist detail, playlist detail, and continuation pagination. Every entity must carry its browse endpoint and preserve provider metadata.

Acceptance: search can filter Songs, Albums, Artists and Playlists; tapping any result opens a functional detail surface; every shelf has loading, empty and retry states.

## Phase 2 — listening session

- Persistent queue with drag/reorder, remove, play-next and clear.
- Room-backed history, favorites and local playlists.
- In-flight resolution deduplication for playback and preload.
- Next-track preload with expiry validation.
- Recovery from network change, CDN expiry, app backgrounding and audio becoming noisy.
- Complete MediaSession metadata and notification controls.

Acceptance: background playback, lock-screen controls, queue transitions and seek survive rotation and app relaunch.

## Phase 3 — lyrics and expression

- Provider cascade with source, confidence and timeout metadata.
- Persistent lyrics cache.
- LRC/TTML/QRC/plain parsing into one prepared model.
- Synced line highlighting, word timing where available, click-to-seek, translation and romanization.
- Dynamic artwork color and a light/dark Material 3 theme that keeps readable contrast.

Acceptance: lyrics never block playback, missing lyrics explain the state, and clicking a timed line seeks correctly.

## Phase 4 — product depth

- Region-aware discovery and charts.
- Account/library sync where authentication is available.
- Downloads/offline playback with the same stream contract metadata.
- Android Auto, widgets, local music and scrobbling integrations.
- Analytics limited to useful local product signals: starts, skips, completion, failed resolutions and recovery success.

## Quality gates

Every phase must pass:

1. `:app:assembleDebug`.
2. Emulator smoke test on Home, Search, Detail, Player, Queue, Lyrics and Library.
3. Light theme, dark theme, 1.3x font scale and system Back.
4. A clean log for resolver client/format/HTTP status and no uncaught playback crash.
5. No source copy from GPL projects; reimplement behavior in Moodify-owned models and interfaces.

## Current implementation order

1. Stream contract and 403 recovery.
2. Typed catalog mapping and detail navigation.
3. Queue/preload/media-session hardening.
4. Lyrics provider/cache upgrade.
5. ArchiveTune-level discovery surfaces and visual polish.
