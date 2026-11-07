import { describe, expect, it } from 'vitest';
import type { Track } from '@/data/mockMusic';
import type { SyncedLibrary } from '@/hooks/useCanonicalMusicPlayer';
import { applyToLibrary, mergePlays, RECENT_KEY, RECENT_MAX, snapshotLibrary } from '../social/librarySync';
import type { SyncChange } from '../social/syncEngine';

const track = (id: string, title = id): Track => ({
  id, title, artist: 'Arijit Singh', album: 'Album', albumArt: `https://i.ytimg.com/vi/${id}/hqdefault.jpg`, duration: 188,
  provider: 'youtube-music', providerId: id,
});
const empty = (): SyncedLibrary => ({ favorites: [], playlists: [], history: [], recentlyPlayed: [] });
const asChanges = (snap: Map<string, unknown>): SyncChange[] =>
  [...snap].map(([key, data]) => { const at = key.indexOf(':'); return { c: key.slice(0, at), id: key.slice(at + 1), data, updatedAt: 5_000 }; });

describe('web library sync mapping', () => {
  const lib: SyncedLibrary = {
    favorites: [track('VAdGW7QDJiU', 'Chaleya')],
    playlists: [{ id: '1730000000000', name: 'Road trip', createdAt: new Date(1_730_000_000_000), tracks: [track('JGwWNGJdvx8'), track('kJQP7kiw5Fk')], pinned: true }],
    history: [{ track: track('JGwWNGJdvx8'), playedAt: new Date(2_000), duration: 200 }, { track: track('VAdGW7QDJiU'), playedAt: new Date(3_000), duration: 180 }],
    recentlyPlayed: [],
  };

  it('a library survives the round trip unchanged (so two browsers never bounce edits)', () => {
    const snap = snapshotLibrary(lib);
    expect([...snap.keys()].sort()).toEqual(['like:VAdGW7QDJiU', 'playlist:1730000000000', RECENT_KEY]);
    const copy = { ...empty(), ...applyToLibrary(empty(), asChanges(snap)) };
    expect(snapshotLibrary(copy)).toEqual(snap);
    expect(copy.playlists[0].pinned).toBe(true);
    expect(copy.recentlyPlayed.map((t) => t.id)).toEqual(['VAdGW7QDJiU', 'JGwWNGJdvx8']);
  });

  it('deletions remove likes and playlists; bad songs are dropped', () => {
    const after = applyToLibrary(lib, [
      { c: 'like', id: 'VAdGW7QDJiU', data: null, updatedAt: 1 },
      { c: 'playlist', id: '1730000000000', data: null, updatedAt: 1 },
      { c: 'like', id: 'bad', data: { track: { id: 'not-a-video-id!' } }, updatedAt: 1 },
    ]);
    expect(after.favorites).toEqual([]);
    expect(after.playlists).toEqual([]);
  });

  it('plays merge by song and time, newest first, capped', () => {
    const p = (id: string, playedAt: number) => ({ track: { id }, playedAt });
    expect(mergePlays({ plays: [p('a', 3), p('b', 1)] }, { plays: [p('a', 3), p('c', 2)] }).plays.map((x) => x.playedAt)).toEqual([3, 2, 1]);
    const many = { plays: Array.from({ length: RECENT_MAX + 10 }, (_, i) => p('x', i)) };
    expect(mergePlays(undefined, many).plays).toHaveLength(RECENT_MAX);
  });
});
