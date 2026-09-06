import type { Track } from '@/data/mockMusic';
import type { DiscoveryContext, DiscoveryContextType } from './listeningTypes';
import { identityKey } from './listeningTypes';

/** Canonical discovery surfaces that can attribute a play request. */
export type DiscoveryPlaybackPath =
  | 'search'
  | 'home'
  | 'explore'
  | 'playlist'
  | 'album'
  | 'artist'
  | 'related'
  | 'up-next'
  | 'queue'
  | 'favorites'
  | 'history'
  | 'library'
  | 'scene'
  | 'vibe'
  | 'direct'
  | 'recommendation';

export interface DiscoveryPlaybackRequest {
  track: Track;
  tracks: Track[];
  discoveryContext: DiscoveryContext;
  path: DiscoveryPlaybackPath;
}

const withPosition = (context: DiscoveryContext, position?: number): DiscoveryContext => (
  typeof position === 'number' && Number.isInteger(position) && position >= 0
    ? { ...context, position }
    : context
);

export const trackSourceKey = (track: Pick<Track, 'id' | 'provider' | 'providerId'>) =>
  identityKey({
    trackId: track.id,
    provider: track.provider || 'unknown',
    providerTrackId: track.providerId || track.id,
  });
export const discoveryContextForSearch = (query: string, position?: number): DiscoveryContext =>
  withPosition({ type: 'search', id: query }, position);

export const discoveryContextForScene = (id: string, position?: number): DiscoveryContext =>
  withPosition({ type: 'scene', id }, position);

export const discoveryContextForVibe = (id: string, position?: number): DiscoveryContext =>
  withPosition({ type: 'vibe', id }, position);

export const discoveryContextForPlaylist = (id: string, position?: number): DiscoveryContext =>
  withPosition({ type: 'playlist', id }, position);

export const discoveryContextForAlbum = (id: string, position?: number): DiscoveryContext =>
  withPosition({ type: 'album', id }, position);

export const discoveryContextForArtist = (id: string, position?: number): DiscoveryContext =>
  withPosition({ type: 'artist', id }, position);

export const discoveryContextForRelated = (sourceTrackKey: string): DiscoveryContext => ({
  type: 'related',
  sourceTrackKey,
});

export const discoveryContextForUpNext = (sourceTrackKey: string, position?: number): DiscoveryContext =>
  withPosition({ type: 'up-next', sourceTrackKey }, position);

export const discoveryContextForExplore = (id?: string, position?: number): DiscoveryContext =>
  withPosition({ type: 'explore', ...(id ? { id } : {}) }, position);

export const discoveryContextForHome = (id?: string, position?: number): DiscoveryContext =>
  withPosition({ type: 'home', ...(id ? { id } : {}) }, position);

export const discoveryContextForLibrary = (id: string, position?: number): DiscoveryContext =>
  withPosition({ type: 'library', id }, position);

export const discoveryContextForDirect = (id: string): DiscoveryContext => ({ type: 'direct', id });

export const discoveryContextForRecommendation = (
  batchId: string,
  position: number,
  strategy: string,
  sources: string[],
  sourceTrackKey?: string,
): DiscoveryContext => ({
  type: 'recommendation',
  id: batchId,
  position,
  strategy,
  sources,
  ...(sourceTrackKey ? { sourceTrackKey } : {}),
});

/** Map a provider/Moodify surface + optional ids into a discoveryContext. */
export const discoveryContextForSurface = (
  surface: DiscoveryContextType,
  options: { id?: string; position?: number; sourceTrackKey?: string } = {},
): DiscoveryContext => {
  switch (surface) {
    case 'search':
      return discoveryContextForSearch(options.id || '', options.position);
    case 'scene':
      return discoveryContextForScene(options.id || 'scene', options.position);
    case 'vibe':
      return discoveryContextForVibe(options.id || 'vibe', options.position);
    case 'playlist':
      return discoveryContextForPlaylist(options.id || 'playlist', options.position);
    case 'album':
      return discoveryContextForAlbum(options.id || 'album', options.position);
    case 'artist':
      return discoveryContextForArtist(options.id || 'artist', options.position);
    case 'related':
      return discoveryContextForRelated(options.sourceTrackKey || options.id || '');
    case 'up-next':
      return discoveryContextForUpNext(options.sourceTrackKey || options.id || '', options.position);
    case 'explore':
      return discoveryContextForExplore(options.id, options.position);
    case 'home':
      return discoveryContextForHome(options.id, options.position);
    case 'library':
      return discoveryContextForLibrary(options.id || 'library', options.position);
    case 'direct':
      return discoveryContextForDirect(options.id || 'direct');
    case 'recommendation':
      return discoveryContextForRecommendation(options.id || 'recommendation', options.position ?? 0, 'continue-listening', []);
    default:
      return discoveryContextForDirect('unknown');
  }
};

export const buildDiscoveryPlaybackRequest = (
  path: DiscoveryPlaybackPath,
  track: Track,
  tracks: Track[],
  discoveryContext: DiscoveryContext,
): DiscoveryPlaybackRequest => ({
  path,
  track,
  tracks: tracks.length ? tracks : [track],
  discoveryContext,
});

/** Resolve which queue index to start from when a context carries position. */
export const resolvePlaybackStartIndex = (
  tracks: Track[],
  discoveryContext?: DiscoveryContext,
  fallbackTrack?: Track,
): number => {
  if (fallbackTrack) {
    const byId = tracks.findIndex((item) => item.id === fallbackTrack.id);
    if (byId >= 0) return byId;
  }
  const position = discoveryContext?.position;
  if (typeof position === 'number' && position >= 0 && position < tracks.length) return position;
  return 0;
};

export const PRODUCTION_PLAYBACK_PATHS: ReadonlyArray<{
  path: DiscoveryPlaybackPath;
  contextType: DiscoveryContextType;
  notes: string;
}> = [
  { path: 'search', contextType: 'search', notes: 'SearchBar result click; intent free-text fallback' },
  { path: 'home', contextType: 'home', notes: 'Provider Home feed via discovery API helper (no dedicated React shelf yet)' },
  { path: 'explore', contextType: 'explore', notes: 'DiscoverView recommendations shelf' },
  { path: 'playlist', contextType: 'playlist', notes: 'PlaylistManager, LibraryHub, social/shared playlists' },
  { path: 'album', contextType: 'album', notes: 'Provider album collection via discovery API helper' },
  { path: 'artist', contextType: 'artist', notes: 'Provider artist collection via discovery API helper' },
  { path: 'related', contextType: 'related', notes: 'Provider related via discovery API helper' },
  { path: 'up-next', contextType: 'up-next', notes: 'Provider up-next via discovery API helper' },
  { path: 'queue', contextType: 'library', notes: 'QueueManager → library id=queue' },
  { path: 'favorites', contextType: 'library', notes: 'FavoritesView / LibraryHub → library id=favorites' },
  { path: 'history', contextType: 'library', notes: 'HistoryView finished listens → library id=history' },
  { path: 'library', contextType: 'library', notes: 'LibraryHub recently-played and shelf entry points' },
  { path: 'scene', contextType: 'scene', notes: 'Legacy mood-playlist keyword routing' },
  { path: 'vibe', contextType: 'vibe', notes: 'Curated vibe destination → playlist/collection, not Mix' },
  { path: 'direct', contextType: 'direct', notes: 'Listen Together remote play; explicit direct entry' },
  { path: 'recommendation', contextType: 'recommendation', notes: 'Moodify Mix / autoplay after delegated queue exhaustion' },
];
