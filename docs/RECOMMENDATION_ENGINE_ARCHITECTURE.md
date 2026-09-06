# Moodify deterministic recommendation engine

Status: **Recommendation Engine V1 implemented** (`recommendation-v1.0.0`). See `docs/RECOMMENDATION_ENGINE_V1.md` for the operational spec. No recommendation UI is included yet.

## Recommended architecture

```text
Provider
  ↓
Provider Adapter
  ↓
Canonical Track Model
  ↓
Track DNA
  ↓
Listening Events
  ↓
Discovery Graph
  ↓
Taste Model
  ↓
Candidate Generator
  ↓
Ranking Engine
  ↓
Recommendation Explanation
  ↓
Moodify Experience
```

The provider adapter must be the only layer that understands InnerTube response shapes. Candidate and ranking code should operate on canonical nodes, DNA, graph edges, and taste evidence.

## Candidate sources

| Source | Actual support today | Candidate value | Required guard |
|---|---|---|---|
| Recent listening | Local `recentlyPlayed` and play events | High | Avoid immediate repeats |
| Repeated listening | Derived from `play_started` history | High | Use repetition evidence, not one retry |
| Same artist | Artist ID/name from provider pages/search | Medium/high | Identity match quality |
| Same album | Album IDs/pages when present | Medium | Album may be absent |
| Related tracks | `getRelated` wrapper exists, server not exposed | High once ingested | Provider relation is not user preference |
| Up-next | `getUpNext` wrapper exists, server not exposed | Medium/high | Contextual queue, dated snapshot |
| Playlist tracks | Public playlist traversal exists in library | Medium/high | Preserve membership/order/provenance |
| Provider Explore/Home | Wrapper exists, server not exposed | Medium | Provider context only |
| Search history/results | Search exists; attribution is missing | Medium | Do not count impression as preference |
| Moodify Scenes | Editorial model not implemented | High for product philosophy | Curator/source required |
| Novel candidates | Can come from Explore/search/graph neighbors | Medium | Explicit exploration budget |

## Candidate record

```text
Candidate {
  trackKey
  trackDna
  sourceEdges[]
  generatedAt
  sourceReasons[]
  excludedBecause?
}
```

Candidate generation should deduplicate by provider identity key while preserving all source reasons. If a track is both related and from a scene, the ranker should see both edges and the explanation should mention both.

## Ranking model

The first ranker should be deterministic, bounded, and inspectable:

```text
score(candidate, taste, context) =
    affinity(candidate, taste)
  + recencyFit(candidate, taste)
  + completionFit(candidate, taste)
  + relationshipStrength(candidate, graph)
  + sequenceFit(candidate, session)
  + discoveryFit(candidate, context)
  + noveltyBonus(candidate, taste)
  - repetitionPenalty(candidate, session)
  - skipPenalty(candidate, taste)
```

Each term must return both a bounded contribution and its evidence. The formula is a policy surface, not a claim that the weights are universally correct. Start with fixed documented weights and evaluate them against offline replay of event histories before tuning.

## Ranking rules

1. Exclude unavailable or unplayable tracks before ranking.
2. Deduplicate by provider identity, not title.
3. Apply a hard immediate-repeat penalty before small preference differences.
4. Separate familiar candidates from exploration candidates.
5. Do not let provider placement overwhelm listener response.
6. Require minimum sample sizes for sequence and relationship signals.
7. Keep long-term, recent, and session evidence visible separately.
8. Return an explanation object with the winning contributions.

## Explanation contract

```text
RecommendationExplanation {
  candidateKey
  reasons: Array<{
    type: affinity | recency | relationship | sequence | context | novelty
    contribution: number
    textKey: string
    evidence: string[]
  }>
  caveats: string[]
  algorithmVersion: string
}
```

Explanations should say “You returned to this artist 4 times” or “It follows tracks you completed in this session,” not “This matches your personality.”

## Scene / Universe content model

Scenes are backend content objects, not UI cards and not arbitrary mood labels:

```text
Scene {
  id
  title
  description
  curator: editorial | community | provider | user
  sourceId?
  artworkRef?
  editorialTags[]       // explicit, attributable tags
  trackRefs: [{ trackKey, position, role? }]
  relatedSceneIds[]
  status: draft | published | archived
  version
  createdAt
  updatedAt
}
```

Separate evidence from editorial intent. A curated scene may say it is for a late-night drive because a curator authored that context; the system must not infer the listener’s emotional state from playing it.

Future scene interaction records can include:

```text
SceneInteraction {
  sceneId
  action: opened | started | completed | skipped | saved | shared
  trackKey?
  position?
  timestamp
  sessionId
}
```

Popularity is an aggregate interaction metric, not an intrinsic scene property. Personalization should rank scene-track relationships using both editorial ordering and listener response.

## Cold start and exploration

- With no local events, use explicit editorial scenes, public provider discovery, and search intent.
- With a few events, favor direct repeats and same-identity relationships while keeping a small exploration quota.
- With mature history, blend long-term affinity, recent context, session sequence, and novel graph neighbors.
- Never fill missing metadata with generated mood/genre labels just to make cold start appear intelligent.

## Evaluation plan

Offline evaluation should replay timestamped event histories and measure:

- completion and early-skip rate;
- repeat return rate;
- diversity across artists/albums/scenes;
- novelty acceptance;
- source attribution quality;
- explanation correctness;
- duplicate/immediate-repeat rate.

Online experiments should wait until event attribution, graph snapshots, and deletion/export semantics exist. A recommendation engine without those foundations would optimize noisy or fabricated signals.

## Dependency roadmap

1. Keep the existing Track DNA and local event systems stable.
2. Complete explicit discovery-context propagation from existing callers.
3. Validate the normalized discovery graph and dated provenance against observed provider responses.
4. Add search exposure records when source context is available.
5. Add deterministic taste aggregation with long-term/recent/session windows.
6. Add a candidate generator with deduplication and hard safety rules.
7. Add offline ranker evaluation and explanation snapshots.
8. Add the Scene/Universe content model and editorial workflow.
9. Only then expose recommendations in the product experience.

## Implementation status and next decision

### Already implemented

- canonical playback through InnerTube-backed Moodify server relay;
- normalized `Track` and `PlaybackTrack` boundaries;
- Track DNA identity/observable/derived/inferred separation;
- bounded local Track DNA storage;
- normalized listening event log, sessions, and deterministic behavioral aggregates;
- production discovery-context attribution on play paths;
- Taste Model V1 (`deriveTasteSnapshot`);
- Recommendation Engine V1 (`generateRecommendations`) with candidate generation, filtering, ranking, diversity, provenance, and cold-start handling.

### Partially implemented

- provider search and track lookup plus normalized discovery graph traversal are exposed;
- provider metadata is normalized only to the fields needed by current search/playback;
- local events capture core behavior and accept discovery attribution, but accurate pause-excluded listening duration is incomplete;
- local playlists/queue exist, but provider playlist membership is not always in the client graph snapshot;
- recommendation engine fetches graph from server; offline graph cache is not yet persisted client-side.

### Missing

- recommendation UI / feed surface;
- editorial Scene/Universe content model and persistence;
- offline ranker evaluation harness;
- server-side recommendation endpoint (client-side only today).

### Dangerous assumptions rejected

- an InnerTube method existing means its response is stable or anonymous-safe;
- a provider related/up-next edge proves similarity or listener preference;
- a search impression is a play;
- title words can reliably produce genre, mood, language, culture, era, or emotional meaning;
- provider account history is interchangeable with Moodify’s local event history;
- a recommendation score can be tuned before attribution and evaluation data exist.

### Highest-value next implementation

Complete caller-side discovery-context propagation and validate graph snapshots against observed provider responses. This unlocks the Taste Model without touching the UI or inventing metadata.
