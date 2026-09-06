import type { Track } from '@/data/mockMusic';

export interface FinishedListen {
  track: Track;
  playedAt: Date;
  duration: number;
}

export const appendFinishedListen = (
  history: FinishedListen[],
  track: Track,
  durationMs: number,
  playedAt = new Date(),
): FinishedListen[] => (
  [{ track, playedAt, duration: Math.max(0, durationMs) }, ...history].slice(0, 100)
);

export const reviveListenHistory = (value: unknown): FinishedListen[] => {
  if (!Array.isArray(value)) return [];
  return value.flatMap((entry) => {
    if (!entry || typeof entry !== 'object' || !('track' in entry)) return [];
    const item = entry as { track: Track; playedAt?: string | Date; duration?: number };
    if (!item.track?.id) return [];
    return [{
      track: item.track,
      playedAt: item.playedAt ? new Date(item.playedAt) : new Date(0),
      duration: Number(item.duration) || 0,
    }];
  });
};
