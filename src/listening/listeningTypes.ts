import type { PlaybackSnapshot, PlaybackTrack } from '@/playback/playbackTypes';

export type ListeningEventType =
  | 'play_started'
  | 'play_completed'
  | 'pause'
  | 'resume'
  | 'skip'
  | 'seek'
  | 'favorite'
  | 'unfavorite'
  | 'queue_added'
  | 'queue_removed'
  | 'replay'
  | 'not_interested';

export type DiscoveryContextType =
  | 'search'
  | 'scene'
  | 'playlist'
  | 'album'
  | 'artist'
  | 'related'
  | 'up-next'
  | 'explore'
  | 'home'
  | 'library'
  | 'direct'
  | 'recommendation'
  | 'vibe';

export interface DiscoveryContext {
  type: DiscoveryContextType;
  id?: string;
  position?: number;
  sourceTrackKey?: string;
  strategy?: string;
  sources?: string[];
}

const discoveryContextTypes = new Set<DiscoveryContextType>([
  'search', 'scene', 'playlist', 'album', 'artist', 'related', 'up-next', 'explore', 'home', 'library', 'direct', 'recommendation', 'vibe',
]);

export const normalizeDiscoveryContext = (value: unknown): DiscoveryContext | undefined => {
  if (!value || typeof value !== 'object') return undefined;
  const candidate = value as Partial<DiscoveryContext>;
  if (typeof candidate.type !== 'string' || !discoveryContextTypes.has(candidate.type as DiscoveryContextType)) return undefined;
  const context: DiscoveryContext = { type: candidate.type as DiscoveryContextType };
  if (typeof candidate.id === 'string' && candidate.id.trim()) context.id = candidate.id;
  if (typeof candidate.position === 'number' && Number.isInteger(candidate.position) && candidate.position >= 0) context.position = candidate.position;
  if (typeof candidate.sourceTrackKey === 'string' && candidate.sourceTrackKey.trim()) context.sourceTrackKey = candidate.sourceTrackKey;
  if (typeof candidate.strategy === 'string' && candidate.strategy.trim()) context.strategy = candidate.strategy;
  if (Array.isArray(candidate.sources)) {
    const sources = candidate.sources.filter((entry): entry is string => typeof entry === 'string' && entry.trim().length > 0);
    if (sources.length) context.sources = sources;
  }
  return context;
};

export interface ListeningTrackIdentity {
  trackId: string;
  provider: string;
  providerTrackId: string;
  artistName?: string;
}

export interface ListeningEvent extends ListeningTrackIdentity {
  id: string;
  type: ListeningEventType;
  timestamp: number;
  sessionId: string;
  positionSeconds?: number;
  durationSeconds?: number;
  completionRatio?: number;
  earlySkip?: boolean;
  listenedSeconds?: number;
  fromPositionSeconds?: number;
  toPositionSeconds?: number;
  direction?: 'forward' | 'backward' | 'none';
  source?: string;
  discoveryContext?: DiscoveryContext;
}

export interface ListeningSession {
  sessionId: string;
  startedAt: number;
  lastActivityAt: number;
  tracksPlayed: string[];
}

export interface TrackListeningSignals extends ListeningTrackIdentity {
  playCount: number;
  completionCount: number;
  skipCount: number;
  earlySkipCount: number;
  replayCount: number;
  favoriteCount: number;
  averageCompletion: number;
  averageListenDuration: number;
  lastPlayedAt: number | null;
}

export interface ArtistListeningSignals {
  artist: string;
  artistPlayCount: number;
  artistCompletionRate: number;
  artistSkipRate: number;
  artistFavoriteCount: number;
  lastPlayedAt: number | null;
}

export interface SessionListeningSignals {
  sessionId: string;
  tracksPlayed: number;
  averageCompletion: number;
  skipRate: number;
}

export interface ListeningSignals {
  tracks: Record<string, TrackListeningSignals>;
  artists: Record<string, ArtistListeningSignals>;
  sessions: Record<string, SessionListeningSignals>;
}

export const toListeningIdentity = (track: PlaybackTrack): ListeningTrackIdentity => ({
  trackId: track.id,
  provider: track.provider || 'unknown',
  providerTrackId: track.providerId || track.id,
});

export const identityKey = ({ provider, providerTrackId }: ListeningTrackIdentity) => `${provider}:${providerTrackId}`;

export type ListeningSnapshot = Pick<PlaybackSnapshot, 'status' | 'currentTrack' | 'currentTime' | 'duration'>;
