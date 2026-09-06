export const PROVIDER = 'youtube-music';

export const NODE_TYPES = Object.freeze({
  TRACK: 'track',
  ARTIST: 'artist',
  ALBUM: 'album',
  PLAYLIST: 'playlist',
  CONTEXT: 'context',
});

export const EDGE_TYPES = Object.freeze({
  PERFORMED_BY: 'performed_by',
  BELONGS_TO: 'belongs_to',
  CONTAINS: 'contains',
  RELATED_TO: 'related_to',
  QUEUED_AFTER: 'queued_after',
  SURFACED_IN: 'surfaced_in',
  RETURNED_FOR: 'returned_for',
});

export const providerIdentityKey = (providerId, provider = PROVIDER) => `${provider}:${providerId}`;
export const nodeKey = (type, providerId, provider = PROVIDER) => (
  type === NODE_TYPES.TRACK ? providerIdentityKey(providerId, provider) : `${provider}:${type}:${providerId}`
);

export const makeProvenance = ({ operation, endpoint, surface, sourceId, observedAt }) => ({
  provider: PROVIDER,
  adapter: 'innertube',
  operation,
  endpoint,
  surface,
  sourceId,
  observedAt,
});

export const makeTrackNode = (track, provenance) => ({
  key: nodeKey(NODE_TYPES.TRACK, track.id, track.provider),
  type: NODE_TYPES.TRACK,
  provider: track.provider || PROVIDER,
  providerId: track.id,
  observedAt: provenance.observedAt,
  provenance,
  track,
});

export const makeEntityNode = ({ type, id, title, artworkUrl, provenance, metadata = {} }) => ({
  key: nodeKey(type, id),
  type,
  provider: PROVIDER,
  providerId: id,
  title: title || undefined,
  artworkUrl: artworkUrl || undefined,
  observedAt: provenance.observedAt,
  provenance,
  metadata,
});

export const makeContextNode = ({ surface, id, title, provenance, metadata = {} }) => makeEntityNode({
  type: NODE_TYPES.CONTEXT,
  id: `${surface}:${id}`,
  title: title || surface,
  provenance,
  metadata: { surface, ...metadata },
});

export const makeEdge = ({ fromKey, toKey, type, provenance, position, metadata = {} }) => ({
  key: [type, fromKey, toKey].join('|'),
  type,
  fromKey,
  toKey,
  provider: PROVIDER,
  observedAt: provenance.observedAt,
  provenance,
  ...(Number.isInteger(position) ? { position } : {}),
  ...(Object.keys(metadata).length ? { metadata } : {}),
});

export const makeSnapshot = ({ operation, surface, sourceId, nodes, edges, observedAt, nextCursor }) => ({
  snapshotId: `${operation}:${sourceId || surface}:${observedAt}`,
  provider: PROVIDER,
  operation,
  surface,
  sourceId,
  observedAt,
  nodes,
  edges,
  pageInfo: {
    hasMore: Boolean(nextCursor),
    ...(nextCursor ? { nextCursor } : {}),
  },
});
