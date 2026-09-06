# Moodify Track DNA: audit and foundation

Status: foundation implemented, recommendation and taste systems intentionally deferred.

## 1. Architecture audit

The active playback and listening path is:

```text
YouTube Music / InnerTube
        |
        v
server/index.mjs
  /api/search       -> normalized provider track
  /api/tracks/:id   -> normalized provider track
  /api/media/:id    -> canonical media relay
        |
        v
src/services/moodifyMusicApi.ts
        |
        v
src/data/mockMusic.ts: Track
        |
        +--> useCanonicalMusicPlayer
        |       |
        |       +--> PlaybackEngine
        |       +--> ListeningEventCollector
        |               |
        |               +--> ListeningEventStore
        |               +--> deriveListeningSignals
        |
        +--> src/trackDna: Track DNA foundation
                |
                +--> deterministic derived attributes
                +--> optional inferred attributes
                +--> bounded TrackDnaStore
```

### Existing canonical models

- `Track` in `src/data/mockMusic.ts` is the application track model used by search, playlists, queue, favorites, and history.
- `PlaybackTrack` in `src/playback/playbackTypes.ts` is the smaller playback-facing model.
- `ListeningEvent` in `src/listening/listeningTypes.ts` stores provider identity, playback behavior, and session context. It deliberately does not copy the complete track payload.
- `ListeningSignals` is a deterministic aggregation of event history, not a music-understanding model.

### Existing metadata sources

- The active source is YouTube Music through `youtubei.js` in `server/index.mjs`.
- Search normalization currently exposes ID, title, primary artist, all returned artists, album, duration, artwork, provider, and a provider result type.
- Track detail exposes ID, title, author/channel, duration, and artwork. It may not expose an album or genre in the current normalized response.
- `moodifyMusicApi.ts` maps provider responses into `Track`. The current mapper intentionally keeps the existing frontend contract; richer provider fields can be passed to Track DNA by a future backend/adapter integration without changing the UI model now.
- `src/services/jiosaavn.ts` remains legacy code but is not part of the active canonical search or playback path.
- `mockMusic.ts` and `moodPlaylists.ts` contain seeded/demo tracks. Their text is usable as application input, but it is not evidence that a provider supplied genre, mood, language, or cultural meaning.

### Duplication and ownership findings

- `useCanonicalMusicPlayer` is the active player and owns the canonical playback/event integration.
- `useMusicPlayer` is an older parallel hook and should not become a Track DNA integration point.
- `PlaybackTrack` and `Track` intentionally serve different boundaries; Track DNA accepts a small adapter-shaped input instead of replacing either model.
- The listening event store is already bounded and resilient to storage failure. Track DNA uses a separate bounded store so a metadata problem cannot affect playback or event logging.

## 2. Track DNA proposal

Track DNA is a fact-preserving record with four layers:

1. `identity`: the track identity used for joins and display.
2. `observable`: values copied from the application/provider without interpretation.
3. `derived`: values calculated deterministically from identity and observable text/duration.
4. `inferred`: optional claims that must carry confidence, source, time, and model/provider version.

The first version deliberately does not pretend to know sonic, emotional, cultural, language, or genre attributes that the current source does not provide.

## 3. Schema definitions

Types live in `src/trackDna/trackDnaTypes.ts` and construction lives in `src/trackDna/trackDna.ts`.

```ts
TrackDna {
  schemaVersion: 1
  key: `${provider}:${providerTrackId}`
  generatedAt: number
  identity: {
    trackId: string
    provider: string
    providerTrackId: string
    title: string
    artist: string
    album?: string
    durationSeconds?: number
  }
  observable: {
    artistId?: string
    artists?: Array<{ id?: string; name: string }>
    albumId?: string
    artworkUrl?: string
    providerResultType?: string
  }
  derived: {
    normalizedTitle: string
    normalizedArtist: string
    normalizedAlbum?: string
    titleTokens: string[]
    artistTokens: string[]
    albumTokens: string[]
    versionMarkers: string[]
    durationBucket: 'unknown' | 'short' | 'standard' | 'long' | 'extended'
  }
  inferred: {
    language?: Evidence<string>
    genres?: Evidence<string[]>
    moods?: Evidence<string[]>
    culturalContexts?: Evidence<string[]>
    audioFeatures?: Evidence<Record<string, number>>
  }
}
```

`Evidence<T>` is `{ value, confidence, source, inferredAt, version }`. Empty or absent inferred fields mean “unknown”; they are not negative claims.

## 4. Why these dimensions exist

- Identity is required to join DNA to listening events and to avoid confusing the same title across providers.
- Artist and album relationships are useful for future taste aggregation and discovery, and are often available directly from the provider.
- Normalized text and tokens provide explainable similarity for titles, artists, albums, and version names without a recommendation engine.
- Version markers capture meaningful distinctions such as live, acoustic, remix, instrumental, lofi, slowed, and reverb. They are text observations, not claims about the recording’s full sonic content.
- Duration buckets are a low-cost deterministic context signal and are more honest than fabricating tempo or energy.
- The inferred slots create a stable future boundary for language, genre, mood, cultural context, and audio features without requiring an external API or ML model today.

## 5. Derived vs inferred field matrix

| Field | Category | Current method | Confidence handling |
|---|---|---|---|
| provider identity | Observable | YouTube Music response / canonical Track | No inference; preserve source value |
| title, artist, album | Identity | Canonical Track values | No inference; missing remains missing |
| artist IDs, artist list, album ID | Observable | Provider response when available | Optional; never synthesized |
| normalized text | Derived | Unicode normalization, lowercasing, punctuation/whitespace normalization | Deterministic |
| title/artist/album tokens | Derived | Tokenization of normalized text | Deterministic |
| version markers | Derived | Explicit title/album marker patterns | Deterministic, may be incomplete |
| duration bucket | Derived | Duration thresholds | Deterministic; unknown duration remains unknown |
| language | Inferred | Not implemented; needs provider or validated classifier | `Evidence<string>` required |
| genre/subgenre | Inferred | Not implemented; needs provider taxonomy or external metadata | `Evidence<string[]>` required |
| mood/emotion | Inferred | Not implemented; needs validated inference | `Evidence<string[]>` required |
| cultural context | Inferred | Not implemented; requires evidence beyond title heuristics | `Evidence<string[]>` required |
| tempo/energy/valence/audio features | Inferred/observable only if provider supplies them | Not available from current source | `Evidence<Record<string, number>>` required |
| era/release year | Not available currently | Do not parse a year from a title and call it release year | Add only from a trusted release-date field |

## 6. Data-source availability matrix

| Source | Available now | Safe to use now | Not available / do not fabricate |
|---|---|---|---|
| YouTube Music search | IDs, title, artists, album, duration, artwork, result type | Identity and observable fields | Genre, mood, language, cultural meaning, audio features |
| YouTube Music track detail | ID, title, author/channel, duration, artwork | Identity and observable fields | Reliable album, release date, genre, mood |
| Canonical `Track` | Same mapped fields plus optional provider IDs | App-level DNA input | Any field not present on the Track |
| Listening events | Provider identity, time, session, completion, skips, favorites, seeks | Join key and future preference signals | Musical characteristics; behavior is not track metadata |
| JioSaavn legacy service | Legacy response/fallback code exists | Nothing in the active DNA path without explicit migration | Treat as a second source of truth |
| External metadata/audio API | Not configured | Nothing | Must be a separate, attributable integration later |
| Local ML/LLM | Not configured | Nothing | No silent guesses or generated labels |

## 7. Connection to Listening Events

Track DNA and events join through the existing provider identity key:

```text
ListeningEvent.provider + ListeningEvent.providerTrackId
        ==
TrackDna.identity.provider + TrackDna.identity.providerTrackId
```

`trackDnaKeyFromEvent`, `trackDnaKeyFromPlaybackTrack`, and `trackDnaKey` all use the existing `identityKey` convention. This keeps event storage backwards compatible and avoids copying DNA into every event.

The future taste layer can therefore read:

```text
event -> DNA key -> TrackDna -> deterministic metadata + optional evidence
event -> listening signals -> listener response to that DNA
```

Taste Profile is intentionally not implemented in this phase.

## 8. Storage strategy

`TrackDnaStore` in `src/trackDna/TrackDnaStore.ts` stores records under `moodify_track_dna_v1` in local storage.

- Default maximum: 1,000 records.
- Records are bounded by oldest `generatedAt` first.
- Invalid JSON and incompatible schema versions are ignored.
- Storage failure is swallowed because DNA is an enrichment layer and must never interrupt playback.
- The store is separate from `ListeningEventStore`; event retention and metadata retention can evolve independently.
- A server/database catalog should replace or supplement this browser cache only when multi-device persistence becomes a requirement.

## 9. Versioning strategy

- `TRACK_DNA_SCHEMA_VERSION` is currently `1`.
- A schema change creates a new version and migration path; readers must ignore incompatible records rather than guessing.
- Derived rules are code-owned and should be treated as versioned behavior when their output changes materially.
- Inferred attributes carry their own `version`, `source`, and `inferredAt`, so a later model/provider can be evaluated or replaced without rewriting facts.

## 10. Test strategy

The foundation tests cover:

- separation of source facts and deterministic derived values;
- normalization, tokenization, version markers, and duration buckets;
- joining DNA to the existing listening identity key;
- bounded storage;
- ignoring incompatible schema records.

Future tests should add provider fixtures, malformed metadata, Unicode titles, duplicate titles across providers, and migration fixtures for each schema version.

## 11. Migration strategy

No migration of existing listening events is required. Existing events already contain the stable provider identity needed for a lazy join.

When DNA is first needed for a track:

1. Build it from the current canonical Track or PlaybackTrack.
2. Store it under the provider identity key.
3. Keep the original event unchanged.
4. Refresh only when source metadata or the schema version changes.

Old tracks with only `id/title/artist/album/duration` still produce valid DNA with optional observable fields absent. Existing mock and shared-playlist tracks remain playable and are not upgraded with fabricated metadata.

## 12. Exact implementation plan and result

1. Audit playback, source, event, and persistence ownership — complete.
2. Preserve optional provider fields in the canonical Track mapping — complete.
3. Add the compact Track DNA schema and builder — complete.
4. Add deterministic derivation only — complete.
5. Add provider-identity joins to events without changing the event schema — complete.
6. Add bounded versioned local storage — complete.
7. Add unit coverage for the foundation — complete.
8. Defer Taste Profile, recommendation ranking, scene construction, external metadata, ML, and UI — intentional.

## Risks, unknowns, and critical review

- YouTube Music result metadata can be incomplete or change shape; normalization must continue to treat optional fields as optional.
- Title markers are useful evidence for version labels but are not audio analysis. They must not be presented later as energy, mood, or genre facts.
- The current schema does not claim to understand sonic characteristics yet. That is a deliberate boundary, not a missing fake feature.
- A browser-only catalog is not a cross-device source of truth. Server persistence should wait until the product has a clear account/sync requirement.
- The legacy JioSaavn module and older player hook remain repository duplication, but deleting them in this phase would broaden scope and risk playback regressions.
- The schema is intentionally small. Adding dozens of mood labels, guessed language fields, or generic “AI confidence” without evidence would be over-engineering and inconsistent with Moodify’s moment/memory/context philosophy.
