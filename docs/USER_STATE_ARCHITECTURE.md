# Moodify User State Architecture

Moodify has **no authenticated user identity**. Personal state is local to the device. A local anonymous UUID (`moodify_identity`) exists for peer features; it is not a Moodify account, and YouTube / InnerTube Home is not Moodify taste.

This document describes Personal State V1: a coherent view over existing evidence stores, plus a small persisted envelope for fields that did not already have an owner.

## Current ownership (do not duplicate)

| Concern | Owner | Persistence |
|---|---|---|
| Listening events + session | `ListeningEventStore` | `moodify_listening_events`, `moodify_listening_session` (max 2000 events, 180 days) |
| Playlists, favorites, history, recently played, volume | Canonical player | `moodify_playlists`, `moodify_favorites`, `moodify_history`, `moodify_recently_played`, `moodify_volume` |
| Track DNA | `TrackDnaStore` | `moodify_track_dna_v1` (max 1000, cache) |
| Taste snapshot | Derived; `TasteStore` is a recomputable cache | `moodify_taste_snapshot_v1` |
| Recommendation evaluation | `RecommendationEvalStore` | `moodify_recommendation_eval_v1` (max 500) |
| Discovery graph | Server | `server/data/discovery-graph.json` — client fetches `/api/discovery/graph` |
| Anonymous local identity | `useAnonymousIdentity` | `moodify_identity` |
| Playback queue / shuffle / repeat | In-memory player session | Not a long-term personal library |
| User-state envelope (extras + prefs snapshot) | `LocalUserStateStore` | `moodify_user_state_v1` |

Social keys (`moodify_friends`, chat, shares) stay outside personal listening state.

## Canonical view: `MoodifyUserState`

Assembled by `assembleUserState`. It is a **read model**, not a second database.

```
MoodifyUserState
├── schemaVersion
├── identity                # always anonymous-local; no fake account
├── listening               # counts + session pointer into ListeningEventStore
├── library                 # playlist/favorite counts + envelope extras
├── discovery               # graph size + attributed surfaces (from events)
├── taste                   # TasteSnapshot (derived)
├── recommendations         # RecommendationResult + eval events (derived / existing)
└── preferences             # live player volume/shuffle/repeat
```

### Persisted vs derived

**Persist (source evidence):**

- Listening events and session
- Playlists, favorites, history, recently played
- Volume
- Recommendation evaluation events (`shown` at autoplay inject; `played` / `completed` / `skipped` / `favorited` from listening)
- Envelope: saved album/artist/scene keys (empty until a future library UI writes them)

**Derive (recomputable):**

- TasteSnapshot
- RecommendationResult
- Moodify Home model
- Rotation / affinities / exploration sets

Taste cache and DNA cache may exist for performance; they are not canonical.

## Store contract

```ts
interface UserStateStore {
  loadEnvelope(): PersistedUserEnvelope
  saveEnvelope(envelope): PersistedUserEnvelope
  savePreferences(preferences): PersistedUserEnvelope
  saveLibraryExtras(extras): PersistedUserEnvelope
}
```

V1 implementation: `LocalUserStateStore`.

Future (not implemented): `CloudUserStateStore` behind the same interface. No OAuth, accounts, or cloud database in this phase.

## Persistence resilience

- Corrupt JSON → empty envelope; app still loads
- Unknown / future schema → empty envelope
- Schema `0` / missing fields → migrate to `USER_STATE_SCHEMA_VERSION` (currently `1`)
- Quota errors → compact extras (20 albums, 20 artists, 10 scenes) and retry; never throw into playback
- Listening and eval retention stay in their existing stores

## Authentication intentionally deferred

- No login, OAuth, or backend user accounts
- Provider account state is not assumed
- InnerTube Home / provider shelves are observations, not Moodify preference
- Future sync should copy **evidence**, then recompute derived taste / recommendations / home

## Dev inspector

`?userStateDebug=1` (development only) shows schema, storage size, library/session counts, taste tops, home sections, and recommendation eval counts. Home appearance is not a `shown` evaluation event.
