import type { TasteSnapshot } from '@/taste/tasteTypes';
import type { TrackDna } from '@/trackDna/trackDnaTypes';
import { RECOMMENDATION_WEIGHTS } from './recommendationWeights';
import { buildGraphIndex, graphNodeToTrack, isTrackNode, trackKeyFromTrack } from './graphUtils';
import { presentTrack } from '@/presentation/providerPresentation';
import type {
  CandidateProvenance,
  CandidateSourceKind,
  DiscoveryGraphSnapshot,
  RawCandidate,
  RecommendationContext,
  RecommendationTrack,
} from './recommendationTypes';

const mergeCandidate = (
  pool: Map<string, RawCandidate>,
  track: RecommendationTrack,
  provenance: CandidateProvenance,
  graphStrength: number,
  isExploration: boolean,
) => {
  const existing = pool.get(track.trackKey);
  if (existing) {
    existing.provenance.push(provenance);
    existing.graphStrength = Math.max(existing.graphStrength, graphStrength);
    existing.isExploration = existing.isExploration && isExploration;
    return;
  }
  pool.set(track.trackKey, {
    trackKey: track.trackKey,
    track,
    provenance: [provenance],
    isExploration,
    graphStrength,
  });
};

const traverseGraph = (
  graph: DiscoveryGraphSnapshot,
  seeds: string[],
  maxDepth: number,
  maxCandidates: number,
  onTrack: (track: RecommendationTrack, provenance: CandidateProvenance, strength: number, isExploration: boolean) => void,
) => {
  const { nodes, outgoing } = buildGraphIndex(graph);
  const visited = new Set<string>();
  const queue: Array<{ key: string; depth: number; path: string[]; sourceTrackKey?: string }> = [];

  [...seeds].sort().forEach((key) => queue.push({ key, depth: 0, path: [key], sourceTrackKey: key }));

  while (queue.length > 0 && visited.size < maxCandidates) {
    const current = queue.shift()!;
    if (visited.has(current.key)) continue;
    visited.add(current.key);

    const node = nodes.get(current.key);
    if (isTrackNode(node) && current.depth > 0) {
      const track = graphNodeToTrack(node!);
      if (track) {
        const lastEdge = current.path.length >= 2 ? current.path[current.path.length - 2] : undefined;
        const edgeType = lastEdge?.split('|')[0] || 'graph';
        const strength = (RECOMMENDATION_WEIGHTS.graph.edgeStrength as Record<string, number>)[edgeType]
          ?? 0.25;
        const decayed = strength * Math.pow(RECOMMENDATION_WEIGHTS.graph.depthDecay, Math.max(0, current.depth - 1));
        const sourceKind: CandidateSourceKind = edgeType === 'related_to'
          ? 'graph-related'
          : edgeType === 'queued_after'
            ? 'graph-up-next'
            : edgeType === 'performed_by'
              ? 'graph-artist'
              : edgeType === 'belongs_to'
                ? 'graph-album'
                : edgeType === 'contains'
                  ? 'graph-playlist'
                  : edgeType === 'surfaced_in'
                    ? 'graph-explore'
                    : 'fallback-graph';
        onTrack(track, {
          source: sourceKind,
          sourceTrackKey: current.sourceTrackKey,
          graphEdgeType: edgeType,
          graphPath: [...current.path],
          evidence: [`graph:${edgeType}@${current.depth}`],
        }, decayed, current.depth > 1);
      }
    }

    if (current.depth >= maxDepth) continue;
    const edges = [...(outgoing.get(current.key) || [])].sort((a, b) => a.key.localeCompare(b.key));
    for (const edge of edges) {
      if (!visited.has(edge.toKey)) {
        queue.push({
          key: edge.toKey,
          depth: current.depth + 1,
          path: [...current.path, edge.key, edge.toKey],
          sourceTrackKey: current.sourceTrackKey,
        });
      }
    }
  }
};

export const generateCandidates = (
  taste: TasteSnapshot,
  graph: DiscoveryGraphSnapshot,
  trackDnaByKey: Record<string, TrackDna>,
  context: RecommendationContext,
): RawCandidate[] => {
  const pool = new Map<string, RawCandidate>();
  const tasteSlice = context.mode === 'session' && taste.session
    ? taste.session
    : Object.keys(taste.recent.tracks).length > 0
      ? taste.recent
      : taste.longTerm;
  const maxDepth = RECOMMENDATION_WEIGHTS.graph.maxDepth;
  const maxCandidates = RECOMMENDATION_WEIGHTS.graph.maxCandidates;

  const dnaToTrack = (key: string): RecommendationTrack | null => {
    const dna = trackDnaByKey[key];
    if (!dna) return null;
    return presentTrack({
      trackKey: key,
      id: dna.identity.providerTrackId,
      title: dna.identity.title,
      artist: dna.identity.artist,
      album: dna.identity.album || '',
      albumArt: dna.observable.artworkUrl || '',
      duration: dna.identity.durationSeconds || 0,
      provider: dna.identity.provider,
      providerId: dna.identity.providerTrackId,
    });
  };

  // A. Taste-neighbour candidates (tracks, artists, albums)
  [...tasteSlice.topTrackKeys].sort().forEach((key) => {
    const profile = tasteSlice.tracks[key];
    if (!profile || profile.affinity < 0.2) return;
    const track = dnaToTrack(key);
    if (!track) return;
    mergeCandidate(pool, track, {
      source: 'taste-track',
      tasteAffinity: profile.affinity,
      evidence: [`taste-track:${profile.affinity}`],
    }, 0, false);
  });

  [...tasteSlice.topArtistKeys].sort().forEach((artistKey) => {
    const artist = tasteSlice.artists[artistKey];
    if (!artist || artist.preferenceScore < 0.2) return;
    artist.trackKeys.forEach((trackKey) => {
      const profile = tasteSlice.tracks[trackKey];
      const track = dnaToTrack(trackKey);
      if (!track || !profile) return;
      mergeCandidate(pool, track, {
        source: 'taste-artist',
        sourceArtistKey: artistKey,
        tasteAffinity: artist.preferenceScore,
        evidence: [`taste-artist:${artist.artistName}`],
      }, 0, false);
    });
  });

  [...tasteSlice.topAlbumKeys].sort().forEach((albumKey) => {
    const album = tasteSlice.albums[albumKey];
    if (!album || album.affinity < 0.2) return;
    album.trackKeys.forEach((trackKey) => {
      const track = dnaToTrack(trackKey);
      if (!track) return;
      mergeCandidate(pool, track, {
        source: 'taste-album',
        sourceAlbumKey: albumKey,
        tasteAffinity: album.affinity,
        evidence: [`taste-album:${album.albumTitle}`],
      }, 0, false);
    });
  });

  // B. Discovery graph traversal from engaged seeds
  const seedKeys = new Set<string>();
  if (context.currentTrack) seedKeys.add(trackKeyFromTrack(context.currentTrack));
  context.recentTrackKeys.slice(0, 5).forEach((key) => seedKeys.add(key));
  [...tasteSlice.topTrackKeys].slice(0, 5).forEach((key) => seedKeys.add(key));

  traverseGraph(graph, [...seedKeys].sort(), maxDepth, maxCandidates, (track, provenance, strength, isExploration) => {
    mergeCandidate(pool, track, provenance, strength, isExploration);
  });

  // C. Successful discovery surfaces — boost neighbours of high-affinity surfaces
  const surfaces = Object.values(taste.discovery)
    .filter((surface) => surface.playStarts >= 2 && surface.affinity >= 0.35)
    .sort((a, b) => b.affinity - a.affinity);

  surfaces.forEach((surface) => {
    traverseGraph(graph, [...seedKeys].sort(), 1, 30, (track, provenance, strength) => {
      mergeCandidate(pool, track, {
        ...provenance,
        source: 'discovery-surface',
        sourceSurface: surface.surface,
        discoveryAffinity: surface.affinity,
        evidence: [...provenance.evidence, `discovery-surface:${surface.surface}`],
      }, strength * surface.affinity, false);
    });
  });

  // D. Session sequence — tracks related to recent trajectory
  if (context.recentTrackKeys.length >= 2) {
    const last = context.recentTrackKeys[0];
    traverseGraph(graph, [last], 1, 20, (track, provenance, strength) => {
      mergeCandidate(pool, track, {
        ...provenance,
        source: 'session-sequence',
        sourceTrackKey: last,
        evidence: [...provenance.evidence, 'session-sequence'],
      }, strength, false);
    });
  }

  // E. Familiarity — re-surface engaged tracks (not currently playing)
  Object.values(tasteSlice.tracks)
    .filter((track) => track.affinity >= 0.45 || track.favorites > 0 || track.replays > 0)
    .sort((a, b) => b.affinity - a.affinity || a.trackKey.localeCompare(b.trackKey))
    .slice(0, 15)
    .forEach((profile) => {
      const track = dnaToTrack(profile.trackKey);
      if (!track) return;
      mergeCandidate(pool, track, {
        source: 'familiarity',
        tasteAffinity: profile.affinity,
        evidence: ['familiarity'],
      }, 0, false);
    });

  // F. Exploration — graph neighbours with weaker taste connection
  if (pool.size < 20) {
    traverseGraph(graph, [...seedKeys].sort(), maxDepth, maxCandidates, (track, provenance, strength) => {
      mergeCandidate(pool, track, { ...provenance, source: 'exploration', evidence: [...provenance.evidence, 'exploration'] }, strength, true);
    });
  } else {
    traverseGraph(graph, [...seedKeys].sort(), maxDepth, maxCandidates, (track, provenance, strength) => {
      const existing = pool.get(track.trackKey);
      if (!existing || existing.provenance.every((entry) => entry.source.startsWith('graph'))) {
        mergeCandidate(pool, track, { ...provenance, source: 'exploration', evidence: [...provenance.evidence, 'exploration'] }, strength, true);
      }
    });
  }

  // Cold-start fallback: any track nodes in graph
  if (pool.size === 0 && graph.nodes.length > 0) {
    [...graph.nodes]
      .filter((node) => isTrackNode(node))
      .sort((a, b) => a.key.localeCompare(b.key))
      .slice(0, 25)
      .forEach((node) => {
        const track = graphNodeToTrack(node);
        if (!track) return;
        mergeCandidate(pool, track, {
          source: 'fallback-graph',
          evidence: ['cold-start-graph'],
        }, 0.2, true);
      });
  }

  return [...pool.values()].sort((a, b) => a.trackKey.localeCompare(b.trackKey));
};
