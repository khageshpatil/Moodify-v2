# Moodify deterministic Taste Model

Status: **Taste Model V1 implemented** (`taste-v1.0.0`). No recommendation UI or ranking feed is included in this phase.

## Goal

The Taste Model represents how a listener responds to music and contexts. It must not claim to represent personality, emotion, culture, or identity. It should be possible to explain every score from listening events, Track DNA, and dated discovery relationships.

## Implementation

```text
ListeningEvent (+ optional discoveryContext)
  -> deriveTasteSnapshot()
       + optional Track DNA (artistId/albumId)
  -> TasteSnapshot
       longTerm / recent / session
       track / artist / album affinities
       discovery surface affinities
  -> TasteStore (optional recomputable cache)
```

Code: `src/taste/` (`tasteModel.ts`, `tasteWeights.ts`, `tasteTypes.ts`, `TasteStore.ts`).
Wired into `useCanonicalMusicPlayer` as `tasteSnapshot` (no UI).

### Track affinity (V1 weights)

Documented in `tasteWeights.ts`:

| Term | Weight | Role |
|---|---:|---|
| completion | +0.34 | Average completion ratio, sample-gated |
| replay | +0.22 | Explicit return / replay |
| favorite | +0.28 | Explicit local intent |
| repetition | +0.18 | Distinct sessions returning to the track |
| recency | +0.12 | Half-life ~21 days |
| queue add | +0.06 | Weak positive intent |
| early skip | −0.36 | Poor early fit (not permanent dislike) |
| late skip | −0.14 | Weaker negative |
| queue remove | −0.08 | Weak negative |

Affinity is clamped to `[0, 1]`. Contributions are retained on `TasteEvidence` for explainability.

### Artist affinity

Aggregates track behaviour by artist ID (from Track DNA) or normalized artist name.
- `exposureScore` = play volume only
- `preferenceScore` / `affinity` = behaviour-weighted blend (track affinities, completion, replay, favorite, returns, minus skips)
Provider recommendation frequency never increases preference.

### Album affinity

Built only when album identity exists (DNA `albumId` or album title).
Engagement breadth uses observed tracks with meaningful completion/replay/favorite.
Single-track albums are down-weighted; one play never implies whole-album like.

### Discovery affinity

Uses `discoveryContext` on `play_started`, then attributes the next completion/skip/replay for that track in the same session.
Answers: which discovery surfaces successfully introduce music this listener completes/replays vs early-skips.

## Inputs

```text
ListeningEvent
  -> Track DNA by provider identity key
  -> Discovery Graph by track/provider/session context
  -> Taste signal aggregates
```

Current reliable inputs are local play lifecycle events, completion position, skip/replay/favorite actions, seeks, queue actions, timestamps, and session IDs. Provider relationships and discovery provenance are not yet connected to the active server.

## Observed versus derived signals

### Observed now

| Signal | Current evidence | Quality |
|---|---|---|
| play started | `play_started` from canonical PlaybackEngine state | Reliable when engine reaches `playing` |
| pause/resume | Playback state transitions | Reliable for observed transitions |
| play completed | `ended` state | Reliable for engine completion; not a threshold completion |
| playback position | Snapshot position copied into event | Reliable at event time |
| duration | Playback snapshot or track duration | Can be unknown or source-dependent |
| completion ratio | Position divided by duration on completion/skip | Deterministic but depends on duration |
| skip | Collector `recordSkip` | Reliable for wired next/previous/selection paths |
| early skip | Position compared to configured early-skip threshold | Deterministic heuristic, not dislike |
| replay | Explicit same-track selection/replay path | Reliable only for wired path |
| favorite/unfavorite | Explicit local favorite action | Reliable local intent signal |
| seek | Debounced seek event | Reliable for meaningful seeks |
| queue add/remove | Explicit local queue actions | Reliable for wired actions |
| session ID | Collector inactivity boundary | Reliable local session grouping |

### Derived now

| Signal | Derivation |
|---|---|
| track play count | Count `play_started` by provider identity |
| track completion count/rate | Count completion events and average completion ratio |
| track skip count/rate | Count skip events; early skips are a subset |
| replay count | Count replay events |
| favorite count | Favorite events minus unfavorite events, bounded at zero |
| artist play/completion/skip/favorite aggregates | Aggregate track signals by event artist name |
| session track count | Session `tracksPlayed` list |
| session skip rate | Skip events divided by session event activity |
| recency | Maximum play timestamp or a decay from it |
| frequency | Plays per fixed window |
| repeated listening | Multiple play starts for the same DNA key across sessions/windows |
| time of day | Derive local time bucket from event timestamp at analysis time |
| sequence transitions | Sort play starts within a session and count adjacent track pairs |
| novelty | First-seen timestamp compared with prior event history |

### Not currently reliable

- queue position at the moment a track was added or played;
- explicit repeat events (`repeat = one/all` is player state, not an event);
- search-to-play conversion;
- scene-to-play conversion;
- playlist-to-play conversion;
- provider-related candidate performance;
- wall-clock listen duration excluding paused time. Current `listenedSeconds` is measured from track start to event time and can include pauses;
- cross-device or provider account history.

## Taste dimensions

The first model should expose evidence, not a psychological summary:

```text
TasteEvidence {
  subjectKey: track | artist | album | playlist | scene | relation
  signal: affinity | recency | frequency | completion | repetition |
          sequence | discovery | novelty | context | relationship
  value: number
  sampleCount: number
  window: long_term | recent | session | contextual
  evidenceEventIds: string[]
  generatedAt: number
  version: string
}
```

### Affinity

Affinity should combine positive and negative behavior with bounded weights:

```text
positive = completion + replay + favorite + repeated return
negative = skip, especially early skip
affinity = bounded(positive - negative)
```

An early skip is evidence of a poor early fit, not proof of dislike. A favorite is explicit intent and can carry more weight than an accidental play.

### Recency and frequency

Keep long-term and recent windows separate. A track played ten times six months ago should not automatically dominate a new session. Use a documented decay function later; do not hide decay in arbitrary constants.

### Completion

Completion is stronger than impression and weaker than explicit preference. Completion should be normalized by known duration and ignored or down-weighted when duration is missing or unreliable.

### Repetition

Repetition is the return pattern: same DNA key across sessions and time windows. It is distinct from repeated `play_started` events caused by a player retry.

### Sequence

Sequence affinity comes from observed transitions within a session, not from provider up-next alone. A transition needs enough observations and should be directional: `A -> B` is not automatically `B -> A`.

### Discovery

Discovery affinity is computed from `discoveryContext` on attributed `play_started` events and the listener’s subsequent completion / skip / replay response. Impressions without play never increase discovery affinity. Surfaces with fewer than three attributed plays are down-weighted.

### Novelty

Novelty is a listener-relative property: new to this listener, new in this session, or familiar at the artist/album level. It must not be derived from release year because release data is not currently available.

### Context

Current context can safely include session and local time-of-day. Location, device, activity, and emotional state should remain absent unless explicitly collected with consent.

### Artist, album, playlist, and relationship affinity

Artist and album affinity can aggregate Track DNA relationships when IDs/names are present. Playlist affinity requires playlist membership and play attribution. Related-track affinity requires dated provider graph edges plus subsequent listener response; a provider relation alone is not affinity.

## Long-term / recent / session layers

```text
TasteSnapshot
  longTerm: stable repeated preferences over a broad bounded window
  recent: recency-weighted preferences over a shorter window
  session: current-session sequence and context evidence
  contextual: time/session/source-specific patterns
  discovery: exploration and novelty response
```

These are views over events and graph evidence, not duplicated permanent truths. Recomputing them from the event log makes them testable and migration-friendly.

## Guardrails

- No genre + mood + artist-frequency-only profile.
- No inferred personality or emotional diagnosis.
- No positive preference from an impression alone.
- No permanent penalty from one skip.
- No cross-provider merge without an explicit identity match.
- No recommendation score without an explanation payload.
- No score from a provider surface unless the source and timestamp are retained.

## Next implementation dependency

Taste Model V1 is implemented on attributed listening events. The recommendation candidate generator / ranker remains deferred and must consume `TasteSnapshot` + Discovery Graph without inventing ML.
