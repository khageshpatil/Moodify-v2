import type { Track } from '@/data/mockMusic';
import { presentArtworkUrl, presentTrack, presentableAlbum, presentableName, presentableTitle } from '@/presentation/providerPresentation';
import type { DiscoveryGraphEdge, DiscoveryGraphNode, DiscoveryGraphSnapshot, RecommendationTrack } from './recommendationTypes';

export const trackKeyFromParts = (provider: string, providerId: string) => `${provider}:${providerId}`;

export const trackKeyFromTrack = (track: Pick<Track, 'id' | 'provider' | 'providerId'>) =>
  trackKeyFromParts(track.provider || 'youtube-music', track.providerId || track.id);

export const graphNodeToTrack = (node: DiscoveryGraphNode): RecommendationTrack | null => {
  const raw = node.track;
  if (!raw?.id && !node.providerId) return null;
  const id = raw?.id || node.providerId || '';
  const provider = raw?.provider || node.provider || 'youtube-music';
  const album = typeof raw?.album === 'string' ? raw.album : raw?.album?.title;
  const presented = presentTrack({
    trackKey: trackKeyFromParts(provider, id),
    id,
    title: presentableTitle(raw?.title, [id, node.providerId]) || presentableTitle(node.title, [id, node.providerId]) || '',
    artist: presentableName(raw?.artist, [id]) || '',
    album: presentableAlbum(album) || '',
    albumArt: presentArtworkUrl(raw?.artwork, raw?.artwork?.url) || '',
    duration: raw?.durationMs ? Math.round(raw.durationMs / 1000) : 0,
    provider,
    providerId: id,
  });
  return presented;
};

export const buildGraphIndex = (graph: DiscoveryGraphSnapshot) => {
  const nodes = new Map<string, DiscoveryGraphNode>();
  const outgoing = new Map<string, DiscoveryGraphEdge[]>();
  const incoming = new Map<string, DiscoveryGraphEdge[]>();

  [...graph.nodes].sort((a, b) => a.key.localeCompare(b.key)).forEach((node) => {
    nodes.set(node.key, node);
  });
  [...graph.edges].sort((a, b) => a.key.localeCompare(b.key)).forEach((edge) => {
    const out = outgoing.get(edge.fromKey) || [];
    out.push(edge);
    outgoing.set(edge.fromKey, out);
    const inc = incoming.get(edge.toKey) || [];
    inc.push(edge);
    incoming.set(edge.toKey, inc);
  });

  return { nodes, outgoing, incoming };
};

export const isTrackNode = (node?: DiscoveryGraphNode) => node?.type === 'track' && Boolean(node.track || node.providerId);
