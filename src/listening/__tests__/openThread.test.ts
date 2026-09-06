import { describe, expect, it } from 'vitest';
import { buildOpenThread, queueFromOpenThread, reviveOpenThread } from '../openThread';

const track = (id: string) => ({
  id,
  title: `Track ${id}`,
  artist: 'Artist',
  album: 'Album',
  albumArt: '',
  duration: 180,
  provider: 'youtube-music' as const,
  providerId: id,
});

describe('open listening thread', () => {
  it('captures the current track plus delegated remainder', () => {
    const thread = buildOpenThread({
      track: track('b'),
      queue: [track('a'), track('b'), track('c'), track('d')],
      currentIndex: 1,
      origin: { type: 'vibe', id: 'late-train-home' },
      owner: 'user',
      now: 10,
    });
    expect(thread?.track.id).toBe('b');
    expect(thread?.remainder.map((entry) => entry.id)).toEqual(['c', 'd']);
    expect(queueFromOpenThread(thread!).map((entry) => entry.id)).toEqual(['b', 'c', 'd']);
    expect(thread?.origin).toEqual({ type: 'vibe', id: 'late-train-home' });
  });

  it('does not persist a thread without a current track', () => {
    expect(buildOpenThread({
      track: null,
      queue: [track('a')],
      currentIndex: 0,
      owner: 'user',
    })).toBeNull();
  });

  it('revives a valid thread and drops invalid remainder entries', () => {
    const revived = reviveOpenThread({
      track: track('a'),
      remainder: [track('b'), { id: '' }, null],
      origin: { type: 'recommendation', id: 'moodify-mix' },
      owner: 'mix',
      updatedAt: 1,
    });
    expect(revived?.owner).toBe('mix');
    expect(revived?.remainder.map((entry) => entry.id)).toEqual(['b']);
    expect(reviveOpenThread({ track: track('a') })).toBeNull();
  });
});
