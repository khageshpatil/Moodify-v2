import type { Track } from '@/data/mockMusic';
import type { DiscoveryContext } from './listeningTypes';
import { normalizeDiscoveryContext } from './listeningTypes';

export const OPEN_THREAD_REMAINDER_LIMIT = 40;

export type ListeningThreadOwner = 'user' | 'mix';

export interface OpenListeningThread {
  track: Track;
  remainder: Track[];
  origin: DiscoveryContext;
  owner: ListeningThreadOwner;
  updatedAt: number;
}

const isTrack = (value: unknown): value is Track => {
  if (!value || typeof value !== 'object') return false;
  const track = value as Track;
  return typeof track.id === 'string' && track.id.length > 0 && typeof track.title === 'string';
};

export const queueFromOpenThread = (thread: OpenListeningThread): Track[] => {
  const rest = thread.remainder.filter((track) => track.id !== thread.track.id);
  return [thread.track, ...rest];
};

export const buildOpenThread = (input: {
  track: Track | null;
  queue: Track[];
  currentIndex: number;
  origin?: DiscoveryContext;
  owner: ListeningThreadOwner;
  now?: number;
}): OpenListeningThread | null => {
  if (!input.track) return null;
  const origin = input.origin || { type: 'direct', id: 'open-thread' };
  const remainder = input.queue
    .slice(Math.max(0, input.currentIndex) + 1)
    .filter((track) => track.id !== input.track?.id)
    .slice(0, OPEN_THREAD_REMAINDER_LIMIT);
  return {
    track: input.track,
    remainder,
    origin,
    owner: input.owner,
    updatedAt: input.now ?? Date.now(),
  };
};

export const reviveOpenThread = (value: unknown): OpenListeningThread | null => {
  if (!value || typeof value !== 'object') return null;
  const raw = value as Partial<OpenListeningThread>;
  if (!isTrack(raw.track)) return null;
  const origin = normalizeDiscoveryContext(raw.origin);
  if (!origin) return null;
  const remainder = Array.isArray(raw.remainder) ? raw.remainder.filter(isTrack).slice(0, OPEN_THREAD_REMAINDER_LIMIT) : [];
  return {
    track: raw.track,
    remainder,
    origin,
    owner: raw.owner === 'mix' ? 'mix' : 'user',
    updatedAt: typeof raw.updatedAt === 'number' ? raw.updatedAt : 0,
  };
};
