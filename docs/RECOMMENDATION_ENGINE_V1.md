# Recommendation Engine V1

Status: **implemented** (`recommendation-v1.0.0`). No recommendation UI or feed is included.

## Purpose

Given a user's `TasteSnapshot`, `DiscoveryGraph`, `TrackDNA`, and current listening context, produce a deterministic, explainable, diverse ranked set of plausible next listens.

This is a backend/data layer only. It does not infer emotion, personality, genre, or mood.

## Pipeline

```text
TasteSnapshot + DiscoveryGraph + TrackDNA + RecommendationContext
  → CandidateGenerator
  → CandidateFilter
  → DeterministicRanker
  → DiversityController
  → RecommendationResult
```

Code: `src/recommendation/`

## Candidate sources

| Source | Kind | Evidence class |
|---|---|---|
| Taste track affinity | `taste-track` | DERIVED (from listening events) |
| Taste artist neighbours | `taste-artist` | DERIVED |
| Taste album neighbours | `taste-album` | DERIVED |
| Graph related | `graph-related` | OBSERVED (provider edge) |
| Graph artist/album/playlist | `graph-*` | OBSERVED |
| Discovery surface success | `discovery-surface` | DERIVED (attributed play outcomes) |
| Session sequence | `session-sequence` | DERIVED |
| Familiarity | `familiarity` | DERIVED |
| Exploration | `exploration` | DERIVED (bounded graph neighbours) |
| Cold-start fallback | `fallback-graph` | OBSERVED |

Provider graph edges are discovery evidence, not user preference.

## Filtering

Hard filters before ranking:

- currently playing track
- tracks already in queue
- invalid provider identity
- recent skip (decaying, not permanent)
- repeated early-skip pattern (≥2 early skips, not favorite)

Skipped tracks are never permanently blacklisted.

## Ranking equation (V1)

Documented in `recommendationWeights.ts`:

```text
score = clamp01(
  tasteAffinity      * 0.28
+ artistAffinity     * 0.18
+ albumAffinity      * 0.10
+ graphRelationship  * 0.14
+ discoverySource    * 0.10
+ sessionContext     * 0.08
+ recencyFit         * 0.06
+ familiarity        * 0.08
+ explorationBonus   * 0.06
- recentSkipPenalty
- repeatedSkipPenalty
- immediateRepeatPenalty
- queueDuplicatePenalty
)
```

Every candidate retains `components`, `penalties`, `provenance`, and `explanationKeys`.

## Exploration strategy

Versioned exploitation ratios by cold-start tier:

| Tier | Events | Exploitation | Exploration |
|---|---:|---:|---:|
| empty | 0 | 55% | 45% |
| sparse | 1–5 | 65% | 35% |
| emerging | 6–20 | 75% | 25% |
| mature | 21+ | 80% | 20% |

Override via `explorationRatio` option. No `Math.random()`.

## Diversity

Soft caps per final list:

- max 2 per artist
- max 2 per album
- max 3 per candidate source

Overflow applies a deterministic penalty; deferred candidates may fill remaining slots.

## Recency

Recently played tracks receive a decaying penalty (~2h window). Favorites receive a reduced penalty (0.35×) so historically loved tracks are not permanently suppressed.

## Negative feedback

Early skips, late skips, and repeated skip patterns contribute negative score components with ~10-day half-life decay. One skip never implies dislike.

## Provenance model

Each candidate carries `CandidateProvenance[]`:

```ts
{
  source: 'graph-related' | 'taste-artist' | ...
  sourceTrackKey?: string
  graphEdgeType?: string
  graphPath?: string[]
  tasteAffinity?: number
  discoveryAffinity?: number
  evidence: string[]
}
```

## Cold start

| Tier | Behaviour |
|---|---|
| 0 events | Graph fallback candidates only; caveats on every result |
| 1–5 | Mix taste + graph; higher exploration ratio |
| 6–20 | Emerging personalization |
| 21+ | Full taste-weighted ranking |

## Determinism

- No `Math.random()`, no shuffling, no time-based randomness
- Stable sort: score desc, then `trackKey` asc
- Same inputs → same ordering

## Development inspector

`?recommendationDebug=1` shows candidate pool, scores, components, and provenance via `RecommendationInspector`.

## Limitations

- Requires client-side Discovery Graph snapshot (`GET /api/discovery/graph`)
- No impression-without-play attribution
- No pause-excluded listen duration
- No genre/mood/audio-feature inference
- Provider graph edges are volatile contextual observations
- No cross-device history

## Versioning

## Autoplay integration (V1)

Recommendations are a **continuation fallback**, not queue authority.

```text
user queue remaining > 1  → no prefetch, no injection
remaining <= 1 and repeat=off → prefetch/lock a stable batch
remaining = 0 and repeat=off → append playable batch tracks to Up Next
repeat-one → never inject (ended handler repeats current)
repeat-all → never inject (wraps the user queue)
shuffle on → user originalQueue is preserved; pending recs are dropped if shuffle is toggled
```

Prefetch threshold: `AUTOPLAY_PREFETCH_REMAINING = 1`.
Inject count: `AUTOPLAY_INJECT_COUNT = 5`.

User-controlled queue (`originalQueue`) and system Up Next (appended recommendation tracks) stay conceptually separate. Injection only **appends**; it never replaces, deletes, or reorders user items.

### Provenance

Injected plays use `discoveryContext.type = 'recommendation'` with `id` (batch), `strategy`, `position`, `sources`, and optional `sourceTrackKey`. Listening events therefore distinguish recommended → completed / skipped / replayed / favorited.

### Failure handling

Unplayable identities are skipped inside the batch. If playback of an injected track fails, the next remaining batch candidate is tried. If none resolve, existing exhaustion behaviour (pause) is used.

### Evaluation / replay

- `recommendationEvalStore` records `shown` at injection time (local only).
- `deriveRecommendationOutcomes` maps listening events with recommendation context to played/completed/skipped/favorited.
- `replayRecommendationHistory` is a test/dev harness: prior events → TasteSnapshot → RecommendationResult at each `play_started`.

### User-control guarantees

- Manual play/playlist load clears the recommendation batch.
- Repeat-one / repeat-all are never overridden.
- Duplicate / recently recommended / recently skipped / in-queue tracks are not re-injected.
- A locked batch is stable until consumed, failed-out, or the user queue signature changes.

