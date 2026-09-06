# Moodify InnerTube Music Pipeline Spike

**Date:** September 3, 2026  
**Scope:** Isolated feasibility spike only. Production Moodify code was not refactored.

## Executive decision

Recommend **Option C**:

```text
React
  -> Moodify backend provider abstraction
       -> YouTube Music / InnerTube adapter (primary)
       -> validated fallback provider(s), only when available
  -> short-lived playback-source response
  -> canonical Moodify playback engine
```

Do not use `React -> InnerTube -> googlevideo` for playback. The direct browser test resolved a valid source but Chrome rejected the media request with `ERR_BLOCKED_BY_ORB`. A backend is realistically required for source resolution, source freshness, range handling, provider isolation, diagnostics, and future fallback providers.

`youtubei.js` is the best TypeScript/Node fit for the primary adapter. It is not an official YouTube API and does not make the playback problem disappear: it requires player-script deciphering, may require PO tokens for some clients/content, returns expiring media URLs, and can break when YouTube changes its internal API.

## 1. Implementation comparison

### Candidate libraries

| Capability | youtubei.js 18.0.0 | ytmusicapi 1.12.2 | yt-dlp 2026.08.19 |
|---|---|---|---|
| Stack fit | TypeScript, Node, browser, Deno | Python 3.10+ | Python CLI/library |
| YouTube Music search | Native `music.search()` with typed parsed shelves | Strong filters for songs, albums, artists, playlists, videos, podcasts, profiles | YouTube search/music search, but less domain-shaped metadata |
| Track metadata | `music.getInfo()` / `TrackInfo` | `get_song()` with song metadata | Rich extractor metadata, less consistently YouTube Music-specific |
| Artist/album/playlist | Native `getArtist`, `getAlbum`, `getPlaylist` | Native methods | Playlist/channel extraction, not the cleanest music domain API |
| Related songs | `TrackInfo.getRelated()` / `music.getRelated()` | `get_song_related()` | Related content is not the primary abstraction |
| Radio/next track | `getUpNext()` and continuations; music items can start radio | `get_watch_playlist(radio=True)` | Can extract mixes/playlists, but not a focused radio API |
| Suggestions | `music.getSearchSuggestions()` | `get_search_suggestions()` | Not a first-class music suggestion API |
| Categories/moods/charts | `getExplore()` and home feed; shape may vary | Explore, moods/genres, global/country charts | Broad site extraction, not a music-catalog model |
| Playback/source resolution | `getInfo()` -> `chooseFormat()` -> `format.decipher(player)` | `get_song()` exposes streaming data but needs current signature timestamp and is metadata/API oriented | Very strong format extraction and signature/n-sig/PO-token workarounds |
| Format/quality data | MIME, codec, bitrate, sample rate, channels, duration, formats | Bitrate, quality, sample rate, channels in song response | Extensive format filtering and quality metadata |
| Expiring URLs | Yes; response has `expiresInSeconds` and URLs contain `expire` | Yes; docs warn missing/current signature timestamps can make URLs invalid | Yes; extractor refetches and re-extracts as needed |
| Cipher/signature handling | Built in, but host must provide a JS evaluator; player script is fetched/cached | Caller must supply current signature timestamp for valid streaming URLs | Mature extractor-side JS/n-sig/PO-token handling, still subject to site changes |
| CORS/browser | Metadata may work in browser; direct media source failed in this test | Browser is not its target runtime | Python process, never browser-native |
| Rate limits | No durable guarantee; client/session choice affects behavior | Unofficial requests; cookies/session headers affect behavior | Extensive retry/sleep/client/cookie controls, but can still be rate-limited |
| Authentication | Anonymous metadata/source attempts can work; account features need session/cookies/tokens | Public browsing/search can be unauthenticated; library management needs auth/cookies/OAuth | Anonymous works for some content; cookies/PO tokens/auth may be required for others |
| Maintenance | Active upstream; v18.0.0, recent release and commits | Active upstream; recent release and tests | Extremely active; frequent releases and extractor fixes |
| Best role for Moodify | Primary Node provider adapter | Optional Python metadata service, not ideal for this stack | Operational fallback/extractor service, not primary domain API |

### Architecture options

| Capability | Option A: React -> InnerTube | Option B: React -> Moodify backend -> InnerTube | Option C: React -> backend -> provider abstraction -> InnerTube + fallback |
|---|---|---|---|
| Lowest latency for metadata | Sometimes | Good | Good |
| Direct browser playback | Failed in test | Backend can relay correctly | Backend can relay correctly |
| CORS/range control | Poor | Strong | Strong |
| Provider replacement | Poor | Moderate | Strong |
| Failure isolation | Poor | Strong | Strong |
| Operational complexity | Low initially | Moderate | Highest, but appropriate for long-term use |
| Recommendation | Reject | Viable minimum | **Choose** |

## 2. Upstream implementation findings

### youtubei.js

The current release supports `YTMUSIC` clients and exposes typed methods for search, artists, albums, playlists, explore, suggestions, related content, and up-next/radio. It sends InnerTube requests through a session whose client context can be adjusted, including `YTMUSIC` and `YTMUSIC_ANDROID`.

For playback, the actual path is:

```text
music.getInfo(videoId)
  -> info.chooseFormat({ type: 'audio', quality: 'best' })
  -> format.decipher(session.player)
  -> expiring googlevideo URL
```

The library fetches the YouTube player JavaScript and extracts the signature/n-sig functions. It does not include a built-in JavaScript evaluator in the current documented setup. The host must provide one. Our Node spike used the documented `Platform.shim.eval` hook. That evaluator executes upstream player-script code, so it should be isolated and monitored in a backend process rather than casually embedded in the main browser app.

The streaming response contains audio MIME/codec, bitrate, sample rate, channels, approximate duration, and expiry information. The source URL is ephemeral and must be resolved close to playback. It cannot be cached as permanent track metadata.

### ytmusicapi

`ytmusicapi` has the broadest YouTube Music-specific metadata surface in this comparison: search filters, suggestions, songs, artists, albums, playlists, related songs, watch playlists, radio, moods/genres, charts, lyrics, and library operations. It emulates the web client and can browse publicly without authentication, while account/library operations require authentication headers, cookies, or OAuth.

It is a strong candidate if Moodify later chooses a Python service for metadata and recommendation ingestion. It is not a clean fit for the current Vite/TypeScript application and is not the best sole playback resolver. Its song API documents the need for a current signature timestamp to avoid invalid streaming URLs.

### yt-dlp

`yt-dlp` is the most operationally mature extractor. It supports YouTube Music search, multiple clients, format selection, retries, cookies, browser cookie extraction, JavaScript runtimes, n-sig/signature workarounds, PO tokens, and detailed format metadata. Its own documentation explicitly describes active external breakage, client-specific authentication/PO-token requirements, and the need to update frequently.

It is Python-first and brings process/runtime/ffmpeg concerns. It is better treated as an optional backend extraction fallback or emergency operational tool, not as the domain model behind Moodify’s UI. It also has a different license/dependency profile that must be reviewed before embedding or distributing it.

## 3. Browser versus backend decision

A backend is required for the desired long-term experience.

Reasons:

- Direct `googlevideo` media consumption failed in Chrome with `ERR_BLOCKED_BY_ORB` and `NotSupportedError`.
- The browser cannot safely own provider-specific deciphering and client/session logic.
- The backend can resolve a fresh source immediately before playback.
- The backend can implement HTTP Range forwarding, response headers, abort propagation, and retry.
- Provider credentials, cookies, PO tokens, and rate limits stay outside the browser.
- One provider outage should not corrupt the normalized track model or React state.
- The backend can expose stable Moodify contracts while InnerTube changes.

The backend should not blindly download entire tracks into memory. It should resolve a source and provide a range-aware relay or a controlled source response, with no permanent caching of media URLs.

## 4. Proof-of-concept results

### Test run

Probe: `probe.mjs`  
Package: `youtubei.js@18.0.0`  
Session: anonymous, locally generated session, documented host evaluator  
Source validation: HTTP `Range: bytes=0-1023`, no media saved

| Case | Search | Resolve | Source range validation | Result |
|---|---:|---:|---:|---|
| The Weeknd - Blinding Lights | 0.51 s | 0.37 s | 0.22 s, HTTP 206, audio/mp4 | Pass server-side |
| Arijit Singh - Tum Hi Ho | 0.44 s | 0.28 s | 0.18 s, HTTP 206, audio/mp4 | Pass server-side |
| Queen - Bohemian Rhapsody query | 0.50 s | 0.26 s | 0.04 s, HTTP 206, audio/mp4 | Pass server-side; search selected Live Aid version |
| Anuv Jain - Alag Aasmaan | 0.48 s | 0.27 s | 0.21 s, HTTP 206, audio/mp4 | Pass server-side |
| Nonsense query | 0.43 s | 0.28 s | 0.18 s, HTTP 206, audio/mp4 | Search returned an unrelated song; relevance validation required |

Measured server-side real cases: **4/4 resolved**, **4/4 returned valid audio bytes**. The returned format was consistently itag 140, `audio/mp4; codecs="mp4a.40.2"`, approximately 131 kbps. The source URLs contained an `expire` timestamp roughly hours in the future, confirming that source expiry must be handled explicitly.

### Additional tests

- Invalid video ID: detected as `InnertubeError: This video is unavailable`.
- Network failure: detected as a rejected fetch (`TypeError: fetch failed`).
- Expired source: changing the actual `expire` query parameter produced HTTP 403.
- Next-track/radio: `music.getInfo(...).getUpNext()` returned 50 items.
- Repeated search: completed in approximately 0.42-0.52 seconds in the final run.
- Rapid consecutive searches: three concurrent searches fulfilled in approximately 1.72 seconds.
- Direct browser playback: **failed** for both mainstream and Hindi sources with `ERR_BLOCKED_BY_ORB` and `NotSupportedError`.
- Minimal proxy: explicit ranged byte requests returned HTTP 206 and 1024 bytes; the browser media request still received 502 because a production relay must handle initial no-range requests, range translation, fresh source reuse, and upstream request headers. Proxy playback is therefore **not yet proven** by this spike.

The probe intentionally does not claim full playback success from receiving bytes. A real browser `playing` event is the acceptance signal, and direct browser playback did not reach it.

## 5. Failure analysis

1. **Missing evaluator:** The first resolver attempt failed with `No valid URL to decipher`; the current library requires a host-provided JavaScript evaluator. This is an operational prerequisite, not an application bug.
2. **Direct browser ORB:** The browser blocked the cross-origin media response even though Node could receive a valid ranged response. Therefore server-side resolution and browser-side consumption are different tests.
3. **Expired URLs:** A deliberately expired URL returned 403. The resolver must refetch on expiry or source errors and the player must retry once with a new source.
4. **Search relevance:** A nonsense query returned a plausible unrelated result, and the Queen query returned a Live Aid version. Search success is not semantic correctness. Normalize, score, and validate title/artist similarity before treating a result as the requested track.
5. **Parser drift:** `getUpNext()` logged a `ParsingError` because the response included a `Message` where a narrower parsed type was expected, yet still returned 50 queue items. Production code must treat parser warnings as diagnostics and validate the returned queue instead of assuming every response shape is stable.
6. **Rate limiting/session variation:** The probe was anonymous and unauthenticated. Results, client availability, and source resolution may vary by IP, geography, client, content, or request volume. Measurements are point-in-time evidence, not an SLA.
7. **Unplayable content:** Search can surface videos, uploads, private content, age-restricted content, region-restricted content, or DRM-protected material. A result must pass playability and audio-only checks before entering a playable queue.
8. **Proxy complexity:** A relay must preserve Range, Content-Range, Content-Length, Content-Type, Accept-Ranges, aborts, and upstream failures. Resolving a new source for every browser range request is incorrect and wasteful.

## 6. Production architecture

```text
React UI
  -> Moodify API client
  -> Moodify backend MusicProvider interface
       -> request timeout / bounded retry
       -> provider health and structured logging
       -> metadata normalization and cache
       -> YouTubeMusicProvider (youtubei.js)
            -> YTMUSIC InnerTube search/metadata/radio
            -> player-script deciphering in isolated backend runtime
            -> short-lived source resolver
       -> optional fallback provider adapter
  -> normalized Track + PlaybackInfo metadata
  -> PlaybackResolver client command
  -> range-aware Moodify media relay or validated source response
  -> one canonical HTMLAudioElement
  -> one Web Audio graph
  -> PlaybackState events
  -> local ListeningEventStore
  -> future RecommendationEngine
```

The React app should never receive InnerTube response objects. It should receive normalized metadata and a playback token/source contract with explicit expiry. The canonical playback engine owns source assignment, media events, retries, and Media Session. The Web Audio graph attaches to that one element exactly once.

## 7. Required dependencies

### Spike

- `youtubei.js@18.0.0` only. No production package was changed.

### Smallest likely production set

- `youtubei.js` in the backend provider adapter.
- Existing `zod` for normalized request/response and persisted-data validation.
- Existing `@tanstack/react-query` only if its caching lifecycle is retained for metadata requests.
- Node’s built-in `http`/`fetch` is sufficient for a range-aware relay; do not add Express/Fastify only for this boundary unless the eventual backend already standardizes on one.
- A JavaScript runtime/evaluator strategy for player deciphering must be explicitly chosen and isolated. Do not add a browser-side `new Function` evaluator to the production app without a security review.

Do not add `ytmusicapi`, `yt-dlp`, `shaka-player`, or a virtualization library yet. Each could be useful later, but none is necessary for the next provider/resolver/engine phase.

## 8. Risks

- InnerTube is private and can change without compatibility guarantees.
- YouTube player JavaScript, signature functions, n-sig behavior, client availability, PO-token requirements, and response schemas can change.
- Resolved URLs expire and may be bound to client/IP/session details.
- Direct browser CORS/ORB behavior is not reliable for googlevideo URLs.
- Backend relays create bandwidth, concurrency, abuse, privacy, and rate-limit costs.
- Anonymous traffic may be challenged or throttled; cookies and authentication create secret-handling obligations.
- Region, age, account, DRM, and availability differences can make a search result non-playable.
- Web Audio can fail on unsupported browsers or before user activation resumes the AudioContext.
- Running extracted player JavaScript requires a carefully isolated evaluator.
- The provider’s metadata may not include reliable tempo, energy, valence, or mood data; recommendation attributes must remain estimated and labeled.
- Fallback providers can introduce inconsistent IDs, metadata quality, licensing terms, and playback semantics.

## 9. Exact next implementation phase, pending approval

### Phase 1A - provider contract

Create only the backend/provider boundary and tests:

- normalized `Track`, `Artist`, `Album`, `Playlist`, `PlayableSource` types
- `MusicProvider` interface
- `YouTubeMusicProvider` adapter around `youtubei.js`
- request timeout, bounded retry, schema validation, provider health
- search, track info, playlist/album/artist, related, up-next, suggestions
- title/artist relevance guard for search results
- no UI changes

### Phase 1B - playback resolver

- resolve a fresh audio format on demand
- expose expiry and format metadata
- retry resolution after expiry/403
- implement source validation without claiming playback success from HTTP status alone
- prototype range-aware relay with integration tests for initial request, bounded range, upstream 403, abort, and source refresh

### Phase 1C - canonical playback engine

- one `HTMLAudioElement` owner
- explicit `idle/loading/ready/playing/paused/ended/failed` state machine
- source assignment only after resolver success
- media event wiring and stale-request cancellation
- visible Retry/Skip/Report failure contract
- Media Session bridge
- attach the Web Audio graph to the same element; defer EQ/crossfade claims until tested

### Phase 1D - migration seam

- add a temporary adapter from the current `Track` shape to normalized tracks
- keep existing social/P2P features untouched
- route existing search and mood playback through the new provider/resolver behind a feature flag
- run playback, provider, expiry, queue, and browser smoke tests
- remove the old direct `jiosaavn.ts` playback path only after parity evidence

**Approval required before implementation:** approve Option C, the backend boundary, the evaluator strategy, and Phase 1A-1C scope. Recommendation and broad UI work remain out of this phase.

## Phase 1 implementation attempt

The approved isolated backend was implemented under `server/` with a normalized provider surface, source-session cache, diagnostics, and a range-aware media relay. HTTP contract tests passed for search, track normalization, initial response, bounded ranges, sequential ranges, malformed ranges, and missing ranges.

The browser smoke page reached the relay, but did not emit `playing`. The failure was reproduced outside the browser against the same fresh source: `googlevideo` returned HTTP 206 for `bytes=0-1048575` and HTTP 403 for `bytes=1048576-2097151`. Browser-like headers did not change the result. The relay now surfaces this cleanly instead of crashing, but it cannot manufacture a continuous browser stream from a source that rejects the next range.

**Phase 1 acceptance status: NOT PASSED.** A real browser did not successfully emit `playing` through the Moodify backend. The React application was intentionally not wired to this incomplete playback path, and no canonical production playback engine was introduced.

The next technical decision is required before further implementation: test a different InnerTube/client/source strategy, evaluate a compliant YouTube embed/player path, or select a provider whose browser playback contract supports continuous media ranges. Do not proceed to recommendations or broad UX work until one of those paths produces a real browser `playing` event.

## Follow-up verification - September 4, 2026

The isolated investigation was rerun against the current `youtubei.js@18.0.0` installation and the same anonymous InnerTube flow. `node --check` passed for the server and spike scripts.

### Client and format matrix

`YTMUSIC`, `MWEB`, `IOS`, and `ANDROID` returned playable-looking audio metadata for `J7p4bzqLvCw`; `YTMUSIC_ANDROID` returned HTTP 400, `TV_EMBEDDED`/`WEB_EMBEDDED` were unavailable, `ANDROID_VR` was login-required in this run, and `TV_SIMPLY` was unplayable. The usable progressive formats were itags 139, 140, 249, 250, 251, 599, and 600. They were ordinary fragmented MP4/WebM media: the first MP4 bytes begin with `ftypdash`; WebM begins with the EBML/WebM signature. Deciphered URLs contained `clen`, `expire`, `n` (where applicable), and `sig`, but no `range`, `rn`, or `rbuf` parameter.

### Range evidence

For the best MP4 format, itag 140 (`audio/mp4; codecs="mp4a.40.2"`, 130,916 bps, 44.1 kHz, 2 channels, 3,264,188 bytes), the following were observed against a fresh source:

| Request | Result |
|---|---|
| `bytes=0-1023` | 206, `audio/mp4`, `Content-Range: bytes 0-1023/3264188` |
| `bytes=0-262143` | 206, 262,144 bytes |
| `bytes=262144-524287` | 206, 262,144 bytes |
| `bytes=524288-786431` | 206, 262,144 bytes |
| `bytes=786432-1048575` | 206, 262,144 bytes |
| `bytes=1048576-1310719` | 403 |

The 403 response had `content-type: text/plain`, `content-length: 0`, `connection: close`, `server: gvs 1.0`, and no body. It did not include a redirect or `Content-Range`. The same cutoff reproduced with the same source, a freshly resolved source for every range, and a new connection for every range. Opus formats failed at an equal or earlier byte position; low formats did not provide a continuous alternative. Exact browser-like `User-Agent`, `Referer`, `Accept`, and `Range` headers did not change the result. Query-range requests also returned 403.

SABR checks did not provide an alternative: `YTMUSIC` and `MWEB` `server_abr_streaming_url` requests returned 403; `IOS` and `ANDROID` returned HTTP 200 with `sabr.malformed_config`.

### Relay diagnostics

The relay now emits secret-safe diagnostics for the incoming browser request, the translated upstream range request/response, and the response headers sent back to Chrome. URL logging is limited to host/path, query-key names, itag, content length, and expiry; signed query values are not logged.

### Browser status

The real-Chrome smoke result remains negative: the Moodify relay delivers the initial range, then the upstream 403 prevents a continuous media response. The audio element reaches `play`/`waiting`/`loadstart` and then `error`/`NotSupportedError`; it does not emit `playing` and cannot satisfy the 30-second requirement. No second track, pause/resume, seek, or three-track acceptance claim is made.

The concrete blocker is upstream progressive-range rejection, not a missing decipher step, redirect, relay-only header translation, source expiry, client choice, or browser CORS/ORB handling. The acceptance criterion is therefore still not met. React and production playback code remain untouched.

## Playback fix - September 4, 2026

The blocker was specific to the audio-only adaptive formats selected by the original resolver. YouTube currently returns those formats through a SABR/progressive path that permits an initial range but rejects later byte ranges. The available muxed MP4 format, itag 18, remains continuously range-readable and includes an AAC audio track.

The isolated resolver now prefers:

```text
info.chooseFormat({ type: 'video+audio', format: 'mp4', quality: 'best' })
  -> format.decipher(yt.session.player)
  -> Moodify range relay
```

This is still an audio-capable HTML media source; Chrome's `<audio>` element decodes the audio track from the muxed MP4 container. The relay preserves the browser's requested range and forwards it upstream without downloading the whole track.

### Verified evidence

- itag 18, `video/mp4; codecs="avc1.42001E, mp4a.40.2"`, returned HTTP 206 for the first 1 MiB and HTTP 206 for the following range.
- For `J7p4bzqLvCw`, upstream content length was 1,742,175 bytes; `bytes=0-1048575` and `bytes=1048576-2097151` both succeeded.
- Chrome 152 loaded the Moodify relay, emitted `loadstart`, `loadedmetadata`, `canplay`, and `playing`, and advanced continuously to approximately 34.6 seconds with no media error.
- Chrome requested sequential ranges for the Hindi and additional test tracks; the relay logs show upstream HTTP 206 responses for their successive ranges.
- A third source also passed larger sequential requests through at least byte 5,242,879 with HTTP 206.
- The `googlevideo@4.1.1` SABR spike dependency was tested but its endpoint returned HTTP 403 before media delivery; it is not used by the working relay path.

The relay diagnostics remain enabled with secret-safe URL logging. React, recommendations, social/P2P code, and the production PlaybackEngine remain untouched. The isolated browser playback path is now proven sufficiently to proceed to the canonical playback-engine phase.

### Final decision

INNER TUBE PLAYBACK PROVEN — READY FOR CANONICAL PLAYBACK ENGINE
