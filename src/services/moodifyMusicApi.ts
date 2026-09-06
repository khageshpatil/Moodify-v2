import { Track } from '@/data/mockMusic';
import { presentArtworkUrl, presentEntityTitle, presentTrack, presentableAlbum, presentableName, presentableTitle } from '@/presentation/providerPresentation';
import type { DiscoveryGraphSnapshot } from '@/recommendation/recommendationTypes';
import {
  buildDiscoveryPlaybackRequest,
  discoveryContextForAlbum,
  discoveryContextForArtist,
  discoveryContextForExplore,
  discoveryContextForHome,
  discoveryContextForPlaylist,
  discoveryContextForRelated,
  discoveryContextForSearch,
  discoveryContextForUpNext,
  trackSourceKey,
  type DiscoveryPlaybackRequest,
} from '@/listening/discoveryAttribution';

const getServerBaseUrl = () =>
  (import.meta.env.VITE_MOODIFY_SERVER_URL || 'http://127.0.0.1:8787').replace(/\/$/, '');

type BackendTrack = {
  id?: string;
  provider?: string;
  title?: string;
  artist?: { name?: string; id?: string } | string;
  artists?: Array<{ name?: string; id?: string } | string>;
  album?: { title?: string; name?: string; id?: string } | string;
  durationMs?: number;
  artwork?: { url?: string };
};

export type DiscoveryEntityKind = 'artist' | 'album' | 'playlist';

export interface DiscoveryEntity {
  id: string;
  type: DiscoveryEntityKind;
  title: string;
  artworkUrl?: string;
}

type DiscoveryNode = {
  type?: string;
  providerId?: string;
  title?: string;
  artworkUrl?: string;
  track?: BackendTrack;
};

type DiscoverySnapshot = {
  surface?: string;
  sourceId?: string;
  nodes?: DiscoveryNode[];
  pageInfo?: { hasMore?: boolean; nextCursor?: string };
};

const artistName = (item: BackendTrack) => {
  if (typeof item.artist === 'string') return item.artist;
  if (item.artist?.name) return item.artist.name;
  return item.artists?.map((entry) => typeof entry === 'string' ? entry : entry.name).filter(Boolean).join(', ');
};

const artistIdFromTrack = (item: BackendTrack) => {
  if (item.artist && typeof item.artist === 'object' && item.artist.id) return item.artist.id;
  const first = item.artists?.find((entry) => typeof entry !== 'string' && entry.id);
  return first && typeof first !== 'string' ? first.id : undefined;
};

const albumTitle = (item: BackendTrack) => {
  if (typeof item.album === 'string') return item.album;
  return item.album?.title || item.album?.name;
};

const albumIdFromTrack = (item: BackendTrack) => (
  item.album && typeof item.album === 'object' ? item.album.id : undefined
);

export const toTrack = (item: BackendTrack): Track | null => {
  if (!item.id) return null;
  const title = presentableName(item.title, [item.id]);
  if (!title) return null;
  return presentTrack({
    id: item.id,
    title,
    artist: presentableName(artistName(item), [item.id, artistIdFromTrack(item)]) || '',
    album: presentableAlbum(albumTitle(item), [albumIdFromTrack(item)]) || '',
    albumArt: presentArtworkUrl(item.artwork, item.artwork?.url) || '',
    duration: item.durationMs ? Math.round(item.durationMs / 1000) : 0,
    provider: item.provider || 'youtube-music',
    providerId: item.id,
    artistId: artistIdFromTrack(item),
    albumId: albumIdFromTrack(item),
  });
};

export const parseDiscoverySnapshot = (snapshot: DiscoverySnapshot) => {
  const tracks = (snapshot.nodes || [])
    .filter((node) => node.type === 'track' && node.track)
    .map((node) => toTrack(node.track as BackendTrack))
    .filter((track): track is Track => Boolean(track));
  const entities = (snapshot.nodes || [])
    .filter((node): node is DiscoveryNode & { type: DiscoveryEntityKind; providerId: string } =>
      (node.type === 'artist' || node.type === 'album' || node.type === 'playlist') && Boolean(node.providerId))
    .map((node) => ({
      id: node.providerId,
      type: node.type,
      title: presentEntityTitle(node.title, node.providerId) || '',
      artworkUrl: presentArtworkUrl(node.artworkUrl),
    }))
    .filter((entry) => Boolean(entry.title));
  const uniqueEntities = [...new Map(entities.map((entry) => [`${entry.type}:${entry.id}`, entry])).values()];
  return {
    tracks,
    entities: uniqueEntities,
    surface: snapshot.surface || '',
    sourceId: snapshot.sourceId,
    nextCursor: snapshot.pageInfo?.nextCursor,
  };
};

const fetchJson = async <T,>(path: string, signal?: AbortSignal): Promise<T> => {
  const response = await fetch(`${getServerBaseUrl()}${path}`, { signal });
  if (!response.ok) throw new Error(`Moodify discovery request failed (${response.status})`);
  return response.json() as Promise<T>;
};

export const searchMoodifyTracks = async (query: string, limit = 10, signal?: AbortSignal): Promise<Track[]> => {
  const data = await fetchJson<{ tracks?: BackendTrack[] }>(`/api/search?q=${encodeURIComponent(query)}`, signal);
  return (data.tracks || []).slice(0, limit).map(toTrack).filter((track): track is Track => Boolean(track));
};

export interface DiscoveryCollectionResult {
  tracks: Track[];
  entities: DiscoveryEntity[];
  surface: string;
  sourceId?: string;
  nextCursor?: string;
}

const collectionFromPath = async (path: string, signal?: AbortSignal): Promise<DiscoveryCollectionResult> => {
  const snapshot = await fetchJson<DiscoverySnapshot>(path, signal);
  return parseDiscoverySnapshot(snapshot);
};

export const parseSearchSuggestions = (payload: { suggestions?: unknown }) =>
  [...new Set((Array.isArray(payload.suggestions) ? payload.suggestions : [])
    .map((entry) => presentableTitle(typeof entry === 'string' ? entry : '') || '')
    .filter(Boolean))].slice(0, 8);

export const fetchSearchSuggestions = async (query: string, signal?: AbortSignal): Promise<string[]> => {
  if (query.trim().length < 2) return [];
  const payload = await fetchJson<{ suggestions?: unknown }>(`/api/discovery/suggest?q=${encodeURIComponent(query.trim())}`, signal);
  return parseSearchSuggestions(payload);
};

export const parseTrackLyrics = (payload: { lyrics?: unknown }) => {
  const lyrics = typeof payload.lyrics === 'string' ? payload.lyrics.trim() : '';
  return lyrics || null;
};

export const fetchTrackLyrics = async (trackId: string, signal?: AbortSignal): Promise<string | null> => {
  if (!trackId) return null;
  try {
    const payload = await fetchJson<{ lyrics?: unknown }>(`/api/tracks/${encodeURIComponent(trackId)}/lyrics`, signal);
    return parseTrackLyrics(payload);
  } catch {
    return null;
  }
};

export const fetchDiscoverySearch = (
  query: string,
  cursor?: string,
  signal?: AbortSignal,
  type?: 'all' | 'song' | 'video' | 'album' | 'artist' | 'playlist',
) =>
  collectionFromPath(
    `/api/discovery/search?q=${encodeURIComponent(query)}${type && type !== 'all' ? `&type=${encodeURIComponent(type)}` : ''}${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''}`,
    signal,
  );

export const fetchDiscoveryExplore = (cursor?: string, signal?: AbortSignal) =>
  collectionFromPath(`/api/discovery/explore${cursor ? `?cursor=${encodeURIComponent(cursor)}` : ''}`, signal);

export const fetchDiscoveryHome = (cursor?: string, signal?: AbortSignal) =>
  collectionFromPath(`/api/discovery/home${cursor ? `?cursor=${encodeURIComponent(cursor)}` : ''}`, signal);

export const fetchDiscoveryArtist = (id: string, cursor?: string, signal?: AbortSignal) =>
  collectionFromPath(`/api/discovery/artist/${encodeURIComponent(id)}${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''}`, signal);

export const fetchDiscoveryAlbum = (id: string, cursor?: string, signal?: AbortSignal) =>
  collectionFromPath(`/api/discovery/album/${encodeURIComponent(id)}${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''}`, signal);

export const fetchDiscoveryPlaylist = (id: string, cursor?: string, signal?: AbortSignal) =>
  collectionFromPath(`/api/discovery/playlist/${encodeURIComponent(id)}${cursor ? `?cursor=${encodeURIComponent(cursor)}` : ''}`, signal);

const playlistIdForBrowse = (id: string) => {
  if (!id) return id;
  if (id.startsWith('VL') || id.startsWith('RD')) return id;
  if (id.startsWith('PL')) return `VL${id}`;
  return id;
};

const pickPlaylistEntity = (entities: DiscoveryEntity[], query: string) => {
  const playlists = entities.filter((entity) => entity.type === 'playlist');
  if (!playlists.length) return undefined;
  const noisy = /\bdj\b|remix|ringtone|nonstop/i;
  const tokens = query.toLowerCase().split(/\s+/).filter((token) => token.length > 3 && token !== 'playlist' && token !== 'songs');
  const ranked = playlists.filter((playlist) => !noisy.test(playlist.title));
  const pool = ranked.length ? ranked : playlists;
  return pool.find((playlist) => {
    const title = playlist.title.toLowerCase();
    return tokens.some((token) => title.includes(token));
  }) || pool[0];
};

const uniqueTracks = (tracks: Track[]) => [...new Map(tracks.map((track) => [track.id, track])).values()];

const loadPlaylistTracks = async (playlistId: string, signal?: AbortSignal) => {
  const first = await fetchDiscoveryPlaylist(playlistIdForBrowse(playlistId), undefined, signal);
  const tracks = [...first.tracks];
  if (first.nextCursor && tracks.length < 36) {
    const more = await fetchDiscoveryPlaylist(playlistIdForBrowse(playlistId), first.nextCursor, signal);
    tracks.push(...more.tracks);
  }
  return uniqueTracks(tracks).slice(0, 48);
};

export interface VibePlaylistLoadResult {
  playlistId?: string;
  playlistTitle?: string;
  tracks: Track[];
}

const vibePlaylistCache = new Map<string, VibePlaylistLoadResult>();

export const fetchVibePlaylistFromYouTube = async (
  cacheKey: string,
  queries: string[],
  signal?: AbortSignal,
): Promise<VibePlaylistLoadResult> => {
  const cached = vibePlaylistCache.get(cacheKey);
  if (cached) return cached;

  let fallbackTracks: Track[] = [];
  for (const query of queries) {
    const playlistSearch = await fetchDiscoverySearch(query, undefined, signal, 'playlist');
    let playlist = pickPlaylistEntity(playlistSearch.entities, query);
    if (!playlist) {
      const mixed = await fetchDiscoverySearch(query, undefined, signal);
      if (mixed.tracks.length && !fallbackTracks.length) fallbackTracks = mixed.tracks;
      playlist = pickPlaylistEntity(mixed.entities, query);
    }
    if (!playlist) continue;
    const tracks = await loadPlaylistTracks(playlist.id, signal);
    if (!tracks.length) continue;
    const result = { playlistId: playlist.id, playlistTitle: playlist.title, tracks };
    vibePlaylistCache.set(cacheKey, result);
    return result;
  }

  const result = { tracks: uniqueTracks(fallbackTracks).slice(0, 24) };
  if (result.tracks.length) vibePlaylistCache.set(cacheKey, result);
  return result;
};

export const fetchDiscoveryRelated = (trackId: string, cursor?: string, signal?: AbortSignal) =>
  collectionFromPath(`/api/discovery/related/${encodeURIComponent(trackId)}${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''}`, signal);

export const fetchDiscoveryUpNext = (trackId: string, cursor?: string, signal?: AbortSignal) =>
  collectionFromPath(`/api/discovery/up-next/${encodeURIComponent(trackId)}${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''}`, signal);

export const fetchDiscoveryGraph = async (signal?: AbortSignal): Promise<DiscoveryGraphSnapshot> => {
  const response = await fetchJson<DiscoveryGraphSnapshot>('/api/discovery/graph', signal);
  return {
    schemaVersion: response.schemaVersion,
    nodes: response.nodes || [],
    edges: response.edges || [],
    snapshots: response.snapshots,
  };
};

export const toDiscoveryPlaybackRequest = (
  kind: 'search' | 'home' | 'explore' | 'playlist' | 'album' | 'artist' | 'related' | 'up-next',
  tracks: Track[],
  options: { id?: string; sourceTrack?: Track; position?: number } = {},
): DiscoveryPlaybackRequest | null => {
  const position = options.position ?? 0;
  const track = tracks[position] || tracks[0];
  if (!track) return null;

  const sourceTrackKey = options.sourceTrack ? trackSourceKey(options.sourceTrack) : options.id
    ? `youtube-music:${options.id}`
    : undefined;

  switch (kind) {
    case 'search':
      return buildDiscoveryPlaybackRequest('search', track, tracks, discoveryContextForSearch(options.id || '', position));
    case 'home':
      return buildDiscoveryPlaybackRequest('home', track, tracks, discoveryContextForHome(options.id || 'FEmusic_home', position));
    case 'explore':
      return buildDiscoveryPlaybackRequest('explore', track, tracks, discoveryContextForExplore(options.id || 'moodify-discover', position));
    case 'playlist':
      return buildDiscoveryPlaybackRequest('playlist', track, tracks, discoveryContextForPlaylist(options.id || 'playlist', position));
    case 'album':
      return buildDiscoveryPlaybackRequest('album', track, tracks, discoveryContextForAlbum(options.id || 'album', position));
    case 'artist':
      return buildDiscoveryPlaybackRequest('artist', track, tracks, discoveryContextForArtist(options.id || 'artist', position));
    case 'related':
      return buildDiscoveryPlaybackRequest('related', track, tracks, discoveryContextForRelated(sourceTrackKey || options.id || ''));
    case 'up-next':
      return buildDiscoveryPlaybackRequest('up-next', track, tracks, discoveryContextForUpNext(sourceTrackKey || options.id || '', position));
    default:
      return null;
  }
};
