# Moodify Home Architecture

Home V1 is a **data contract**, not a UI. `deriveHomeModel` produces `MoodifyHomeModel` for a future cinematic Home. It does not render shelves, does not invent moods/genres, and does not implement a second recommender.

```
Listening evidence + library
        ↓
Taste Model V1
        ↓
Recommendation Engine V1  ← Discovery Graph (fetch, not duplicated)
        ↓
Moodify Home Model
        ↓
Future UI
```

Version: `home-v1.0.0`.

## Sections

| Id | Question it answers | Evidence |
|---|---|---|
| `continueListening` | What did I actually start? | Incomplete `play_started`, recently played, history, last playlist context |
| `yourRotation` | What do I return to? | Taste long-term return patterns (replays, favorites, multi-session, completions) — **not** rec score |
| `forYou` | What does the engine suggest now? | Existing `RecommendationResult.candidates` in engine order |
| `freshFinds` | What have I not meaningfully consumed? | Exploration / graph-sourced rec candidates + unused graph tracks |
| `scenes` | Editorial worlds (future) | Contract only; **no published mood catalogue** |
| `moodifyMix` | Living session | Mix contract wrapping Recommendation Engine V1 candidate keys |

Each `HomeItem` may carry `source`, `score`, `provenance`, and `explanationKeys`. Missing evidence ⇒ omit explanation. Never fabricate copy.

**Meaningfully consumed** (Fresh Finds exclusion): `play_completed` ≥ 1, or `play_started` ≥ 2, or favorited.

## Cold-start vs mature visibility

Cold-start tiers reuse Recommendation Engine V1 event counts:

- `empty`: 0 events
- `sparse`: 1–5
- `emerging`: 6–20
- `mature`: 21+

Documented prominence:

| Section | empty | sparse | emerging | mature |
|---|---|---|---|---|
| Continue Listening | hidden (also hidden if no items) | prominent if items | prominent if items | prominent if items |
| Your Rotation | hidden | available if items | prominent if items | prominent if items |
| For You | available if items | available if items | prominent if items | prominent if items |
| Fresh Finds | prominent if items | prominent if items | prominent if items | available if items |
| Scenes | prominent (contract, empty items) | prominent | available | available |
| Moodify Mix | available | prominent if items | prominent if items | prominent if items |

Empty item lists are hidden except Scenes (always the contract surface) and Mix (available as a future session even before candidates exist).

## Recommendation integration

- For You and Moodify Mix **consume** `generateRecommendations` output
- Ranking weights are unchanged
- Provenance and explanation keys are copied, not rewritten
- Autoplay evaluation remains the source of `shown` (inject) vs `played` / `completed` / `skipped` / `favorited`
- Building Home **must not** write impression events. Shown ≠ rendered on Home until a real UI records it

## Scene contract (`scene-contract-v1.0.0`)

A Scene is an editorial listening world (identity, artwork, description, track sources, editorial metadata, discovery provenance, recommendation context). V1 publishes **zero** scenes. Do not hardcode generic mood cards.

## Moodify Mix contract (`moodify-mix-v1.0.0`)

Not a static playlist. A generated session:

- `id`, `engineVersion`, `strategy`, `generatedAt`, `candidateKeys`
- `source: "recommendation-engine-v1"`

The future player can start this session with the same recommendation provenance used by autoplay.

## Discovery graph

Home does not persist a client graph replica. It accepts a `DiscoveryGraphSnapshot` (today: API fetch). A future cache can implement the same snapshot shape.

## Provider Home ≠ Moodify Home

InnerTube / YouTube Music home shelves are provider observations. Moodify Home is assembled only from local evidence + Moodify recommendation/taste/graph APIs.
