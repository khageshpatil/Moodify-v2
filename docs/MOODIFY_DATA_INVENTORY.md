# Moodify provider data inventory

Audit basis: `server/index.mjs`, `src/services/moodifyMusicApi.ts`, the canonical `Track`/`PlaybackTrack` models, the listening event schema, and the installed `youtubei.js@18.0.0` parser surface.

The current server normalizes only a subset of provider data. “Discarded” means present or potentially present in the provider/parser boundary but not emitted by the current Moodify HTTP response. A missing value must remain missing.

## Provider-to-server inventory

| Field | Provider source | Example shape | Observable / derived / inferred | Reliability | Safe to persist? |
|---|---|---|---|---|---:|
| `id` / `videoId` | Music search item, track info | `"J7p4bzqLvCw"` | Observable | High for identity | Yes |
| `provider` | Moodify normalization | `"youtube-music"` | Observable application constant | High | Yes |
| `title` | Search item / `basic_info.title` | `"Song title"` | Observable | High/medium | Yes |
| primary artist name | Search `artists[0]` / `basic_info.author` | `{ name: "Artist" }` | Observable | Medium; author may be channel rather than recording artist | Yes, with source context |
| artist ID | Search artist endpoint / `channel_id` | `"UC..."` | Observable when present | Medium | Yes when present |
| additional artists | Search item `artists[]` | `[{ id, name }]` | Observable when present | Medium | Yes when present |
| album ID | Search item `album.id` | `"MPR..."` | Observable when present | Medium | Yes when present |
| album title | Search item `album.name` | `"Album"` | Observable when present | Medium | Yes when present |
| duration seconds | Search `duration.seconds`, track `basic_info.duration` | `214` | Observable; unit conversion is deterministic | Medium/high | Yes |
| artwork thumbnails | Search `thumbnails[]`, track `basic_info.thumbnail[]` | `{ url, width, height }` | Observable | Medium; URLs can expire/change | Cache with refresh |
| result type | Search item `type` / `item_type` | `"song"` | Observable | Medium | Yes |
| provider metadata | Track detail `basic_info`, raw parser page | large object | Observable but source-specific | Medium/volatile | Persist only a versioned allow-list |
| playability status | Track info | `{ status, reason }` | Observable operational state | Medium/volatile | Short-lived diagnostics only |
| streaming format | `chooseFormat` result | mime, bitrate, `approx_duration_ms`, `content_length` | Observable operational state | Volatile | No long-term DNA persistence |
| direct media URL | `format.decipher` | expiring URL with `expire` | Observable operational secret-like URL | Very volatile | No; cache only in server memory |
| `contentLength` | format/query `clen` or format field | integer bytes | Observable operational state | Medium | Short-lived only |
| MIME type | format `mime_type` | `audio/mp4` or provider format | Observable | Medium/volatile | Short-lived only |
| bitrate | format `bitrate` / `average_bitrate` | integer bits/sec | Observable | Medium/volatile | Short-lived only |
| provider tabs | `TrackInfo.tabs` from `next` | Up next, related, lyrics | Observable navigation structure | Low/volatile | Store normalized edges, not raw response |
| up-next items | `PlaylistPanel` | ordered items and continuation | Observable provider relationship | Medium/volatile | Yes as dated graph evidence |
| related items | related music tab | section items | Observable provider relationship | Medium/volatile | Yes as dated graph evidence |
| lyrics shelf | music lyrics tab | description shelf/message | Observable text supplied by provider | Low/variable | Optional, with source and terms review |
| artist page sections | `yt.music.getArtist` | shelves/carousels | Observable relationship/content | Medium/volatile | Normalize selected edges |
| album page sections | `yt.music.getAlbum` | track shelf/sections | Observable relationship/content | Medium/volatile | Normalize selected edges |
| playlist contents | `yt.music.getPlaylist` | ordered responsive list items | Observable membership/order | Medium/volatile | Yes as dated graph evidence |
| explore shelves | `yt.music.getExplore` | navigation buttons/carousels | Observable provider context | Medium/volatile | Yes as source context; not Moodify mood truth |
| home shelves | `yt.music.getHomeFeed` | carousels/taste builder | Observable provider context | Low/volatile/personalized | Short-lived candidate snapshot |
| search suggestions | `yt.music.getSearchSuggestions` | suggestion sections | Observable provider suggestion | Medium/volatile | Prefer aggregate/query context, not raw indefinitely |
| continuation token | search/home/library/playlist/up-next | opaque token | Observable pagination cursor | Short-lived | No long-term DNA persistence |

## Fields currently discarded by Moodify’s active server

The active `normalizeTrack` returns a compact response containing ID/provider/title/primary artist/all artists/album/duration/artwork/result type. It does not currently expose:

- raw search shelves and their titles;
- item endpoints/browse IDs for graph traversal;
- raw playlist membership/order context (the graph now stores normalized membership/order);
- raw related and up-next contents from the `next` response (the graph now stores normalized dated edges);
- track tabs and continuation tokens;
- lyrics content;
- raw artist and album page sections (selected relationships are now normalized into graph snapshots);
- release date/year;
- provider like/dislike state and counts;
- full thumbnail size/crop metadata;
- raw playability reason in the canonical track response;
- signed streaming URLs beyond the server relay;
- raw provider home/explore/library/history responses (home and Explore are now normalized as graph context snapshots; library/history remain unimplemented).

These are not automatically losses to fix. Each should be normalized only when a concrete Moodify use case and retention policy exist.

## Canonical Moodify fields

The application `Track` model uses:

```text
id, title, artist, album, albumArt, duration,
url?, provider?, providerId?
```

`PlaybackTrack` intentionally carries only playback identity/display fields. `ListeningEvent` carries provider identity and behavioral measurements, not a copied track snapshot. Track DNA joins by `provider:providerTrackId` and can be built from whichever canonical payload is available.

## Track DNA treatment

| Candidate attribute | Treatment |
|---|---|
| title/artist/album | Identity facts when supplied by source |
| artist/album IDs | Observable optional facts |
| normalized strings/tokens | Deterministically derived |
| live/acoustic/remix/instrumental/etc. marker | Deterministically derived from explicit text marker; incomplete, not audio analysis |
| duration bucket | Deterministically derived from known duration |
| language | Unknown until explicit provider evidence or separately versioned inference |
| genre/subgenre | Unknown until explicit source/taxonomy evidence |
| mood/emotion | Unknown; title words are not reliable mood evidence |
| cultural context | Unknown unless editorial/provider evidence is attributable |
| era/release year | Unknown until release metadata exists; never parse a year from title as release year |
| tempo/energy/valence/acousticness | Unknown until a reliable audio feature source exists |

## Persistence rules

Safe long-lived fields are stable identities, normalized metadata, dated provider relationship edges, and local listening events. Expiring URLs, continuation tokens, raw provider payloads, playability state, and provider UI shelves should be cached or persisted only with short lifetimes and explicit versioning.

No field in this inventory is evidence of user personality, emotion, culture, or intent. Those are not provider facts and must not be generated from metadata shortcuts.

## Discovery graph implementation status

The server-side provider adapter now normalizes the available InnerTube artist, album, playlist, related, up-next, Explore, and Home responses into canonical provider nodes and edges. It retains provenance, positions, observation timestamps, continuation state, and bounded dated snapshots in the discovery graph store. Raw InnerTube payloads and expiring media URLs are not written to the graph.

The listening event schema accepts an optional `discoveryContext` (`search`, `scene`, `playlist`, `album`, `artist`, `related`, `up-next`, `explore`, `home`, `library`, `direct`, or `recommendation`). The event collector attaches it when playback actually starts. Production React play call sites and discovery API helpers supply source-specific context through `discoveryAttribution` builders; context is never inferred from track metadata.

## Personal state and Home (V1)

`MoodifyUserState` is a local read model over the stores above plus envelope key `moodify_user_state_v1`. Taste, recommendations, and Home sections are derived, not duplicated. See `docs/USER_STATE_ARCHITECTURE.md` and `docs/MOODIFY_HOME_ARCHITECTURE.md`.
