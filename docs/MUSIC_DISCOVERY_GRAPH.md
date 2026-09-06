# Moodify music discovery graph

## Purpose

The provider can expose a graph of music objects and navigation contexts. Moodify should preserve those relationships with provenance and time instead of flattening everything into labels such as `mood = chill`.

## Graph we can construct today

```text
Track
  ├── performed_by ──> Artist
  ├── belongs_to ────> Album                 (when album ID is present)
  ├── member_of ────> Provider Playlist      (when traversed)
  ├── related_to ────> Track                  (provider related tab)
  ├── queued_after ──> Track                  (provider up-next panel)
  ├── returned_for ──> SearchContext           (Moodify query + timestamp)
  ├── surfaced_in ───> Explore/HomeContext     (provider shelf + timestamp)
  └── listened_to ──> Listener                 (Moodify event log)

Artist ── has_track ──> Track
Album  ── contains ───> Track                  (ordered when source provides order)
Playlist ─ contains ──> Track                  (ordered membership)
Scene ─── contains ───> Track                  (Moodify editorial edge)
Scene ─── related_to ─> Scene
```

## Edge provenance rules

Every provider edge should eventually carry:

```text
edgeType
fromKey
toKey
provider
sourceEndpoint / sourceSurface
observedAt
position?          // playlist or queue order
query?             // search context
sessionId?         // Moodify listening context
```

Provider relationships are observations, not permanent truth. Related and up-next results are contextual snapshots and can change between requests. Playlist membership and album membership are stronger edges but can still change.

## Edge audit

| Edge | Actual source | Confidence | Current Moodify status |
|---|---|---:|---|
| Track → Artist | Search item `artists`; track `basic_info` author/channel | Medium | Track DNA can preserve identity; graph ingestion not exposed |
| Track → Album | Search item `album`; album page | Medium | Search response may contain album; no graph store |
| Album → Track | `yt.music.getAlbum(id)` contents | Medium | Library capability only |
| Artist → Track | `yt.music.getArtist(id)` shelves / top songs | Medium | Library capability only |
| Playlist → Track | `yt.music.getPlaylist(id)` contents | Medium | Library capability only |
| Track → Playlist | Inverse of traversed playlist contents | Medium | Not currently stored |
| Track → Related Track | `yt.music.getRelated(id)` / `TrackInfo.getRelated()` | Medium/volatile | Not currently exposed by server |
| Track → Up-next Track | `yt.music.getUpNext(id)` / `TrackInfo.getUpNext()` | Medium/volatile | Not currently exposed by server |
| Search query → Track | Moodify `/api/search?q=...` response | High as an exposure event | Query is not currently included in listening events |
| Explore/Home → Track | Music shelves from `getExplore`/`getHomeFeed` | Low/volatile | Not currently exposed by server |
| Track → ListeningEvent | Existing provider identity key | High | Implemented |
| Track → Scene | Moodify editorial data | High when curated | Scene model not implemented |
| Track → mood/genre | No reliable current provider field | None | Must not be created as a graph fact |

## Provider navigation is not the same as similarity

- `related_to` means the provider returned an item in a related surface; it does not prove sonic similarity.
- `queued_after` means the provider placed an item in a contextual queue; it does not prove the user likes it.
- `returned_for` records search context; a result being shown does not mean it was played.
- `listened_to` records user behavior and is the strongest personalization edge, but behavior still needs completion/skip context.

## Recommended normalized graph records

```text
DiscoveryNode {
  key: provider:type:id
  type: track | artist | album | playlist | scene | search_context | explore_context
  provider
  title?
  observedAt
}

DiscoveryEdge {
  fromKey
  toKey
  type: performed_by | belongs_to | contains | related_to |
        queued_after | returned_for | surfaced_in | listened_to | related_scene
  source
  provider?
  observedAt
  position?
  query?
  sessionId?
}
```

## Highest-value graph implementation order

1. Complete explicit discovery-context propagation from existing search/scene/playlist/library callers.
2. Validate provider snapshots and continuation behavior against observed responses.
3. Add stronger cross-surface identity reconciliation only when explicit provider IDs are present.
4. Add Moodify Scene edges after an editorial content model exists.

The graph should be append-only evidence with bounded retention and deduplication by `(edge type, from, to, source window)`. It should not overwrite the Track DNA record or listening events.

## Current implementation

The server now provides a read-only `InnerTubeProviderAdapter` that owns all raw InnerTube parsing. It exposes normalized snapshots for search, artist, album, playlist, related, up-next, Explore, and Home surfaces. Search, playlist, Home, and up-next cursors are retained as short-lived opaque continuation handles; the adapter does not persist raw provider responses.

`DiscoveryGraphStore` persists bounded canonical nodes, edges, provenance, observation counts, and dated snapshots in `server/data/discovery-graph.json`. The graph is inspectable through `GET /api/discovery/graph` and the surface-specific `/api/discovery/*` routes, including `GET /api/discovery/search?q=...`. The existing `/api/search` path also records the same search snapshot while preserving its `{ tracks }` response shape. Search results use `returned_for` edges and retain the observed query as source context. Play requests can carry `discoveryContext`, which is attached only when playback actually reaches `playing` and then flows into the listening event.

Client attribution is centralized in `src/listening/discoveryAttribution.ts`. Existing React play surfaces (search, scenes, playlists, library shelves, queue, favorites, history, explore shelf, listen-together) pass context into `useCanonicalMusicPlayer.playTrack`. Provider album/artist/related/up-next/home collections are available through `moodifyMusicApi` discovery helpers that build the same play-request contract before UI shelves exist for every surface.
