export type PlaybackStatus = 'idle' | 'loading' | 'ready' | 'playing' | 'paused' | 'ended' | 'failed';

export interface PlaybackTrack {
  id: string;
  title: string;
  artist: string;
  album?: string;
  albumArt?: string;
  duration?: number;
  provider?: string;
  providerId?: string;
}

export interface PlaybackSource {
  url: string;
  expiresAt?: number;
}

export interface PlaybackError {
  code: string;
  message: string;
  cause?: unknown;
}

export interface PlaybackSnapshot {
  status: PlaybackStatus;
  currentTrack: PlaybackTrack | null;
  currentTime: number;
  duration: number;
  buffered: number;
  volume: number;
  muted: boolean;
  playbackRate: number;
  error: PlaybackError | null;
}

export type PlaybackSourceResolver = (
  track: PlaybackTrack,
  signal: AbortSignal,
  attempt: number,
) => Promise<PlaybackSource>;
