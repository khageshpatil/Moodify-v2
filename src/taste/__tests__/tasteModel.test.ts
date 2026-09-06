import { describe, expect, it } from 'vitest';
import type { ListeningEvent } from '@/listening/listeningTypes';
import { deriveTasteSnapshot } from '../tasteModel';
import { TASTE_MODEL_VERSION } from '../tasteTypes';
import { TasteStore } from '../TasteStore';
import { MISSING_TASTE_SIGNALS, TASTE_WEIGHTS } from '../tasteWeights';

const base = (overrides: Partial<ListeningEvent> & Pick<ListeningEvent, 'id' | 'type' | 'timestamp' | 'sessionId'>): ListeningEvent => ({
  trackId: 't1',
  provider: 'youtube-music',
  providerTrackId: 't1',
  artistName: 'Artist One',
  ...overrides,
});

const now = 1_700_000_000_000;

describe('Taste Model V1', () => {
  it('is deterministic for the same events, DNA, now, and version', () => {
    const events: ListeningEvent[] = [
      base({ id: 'e1', type: 'play_started', timestamp: now - 60_000, sessionId: 's1', discoveryContext: { type: 'search', id: 'q', position: 0 } }),
      base({ id: 'e2', type: 'play_completed', timestamp: now - 30_000, sessionId: 's1', completionRatio: 1, listenedSeconds: 180 }),
      base({ id: 'e3', type: 'favorite', timestamp: now - 20_000, sessionId: 's1' }),
      base({ id: 'e4', type: 'replay', timestamp: now - 10_000, sessionId: 's2' }),
      base({ id: 'e5', type: 'play_started', timestamp: now - 9_000, sessionId: 's2' }),
      base({ id: 'e6', type: 'play_completed', timestamp: now - 1_000, sessionId: 's2', completionRatio: 0.95 }),
    ];

    const dna = {
      'youtube-music:t1': {
        identity: { artist: 'Artist One', album: 'Album One' },
        observable: { artistId: 'UC1', albumId: 'MPR1' },
      },
    };

    const a = deriveTasteSnapshot(events, { now, trackDnaByKey: dna, currentSessionId: 's2' });
    const b = deriveTasteSnapshot(events, { now, trackDnaByKey: dna, currentSessionId: 's2' });
    expect(a).toEqual(b);
    expect(a.version).toBe(TASTE_MODEL_VERSION);
    expect(a.missingSignals).toEqual([...MISSING_TASTE_SIGNALS]);
  });

  it('raises track affinity for completion, replay, favorite, and multi-session returns', () => {
    const weak: ListeningEvent[] = [
      base({ id: 'w1', type: 'play_started', timestamp: now - 5_000, sessionId: 's1' }),
      base({ id: 'w2', type: 'skip', timestamp: now - 4_000, sessionId: 's1', earlySkip: true, completionRatio: 0.05, positionSeconds: 3 }),
    ];
    const strong: ListeningEvent[] = [
      base({ id: 's1', type: 'play_started', timestamp: now - 80_000, sessionId: 'a' }),
      base({ id: 's2', type: 'play_completed', timestamp: now - 70_000, sessionId: 'a', completionRatio: 1 }),
      base({ id: 's3', type: 'favorite', timestamp: now - 65_000, sessionId: 'a' }),
      base({ id: 's4', type: 'replay', timestamp: now - 40_000, sessionId: 'b' }),
      base({ id: 's5', type: 'play_started', timestamp: now - 39_000, sessionId: 'b' }),
      base({ id: 's6', type: 'play_completed', timestamp: now - 20_000, sessionId: 'b', completionRatio: 1 }),
    ];

    const weakAffinity = deriveTasteSnapshot(weak, { now }).longTerm.tracks['youtube-music:t1'].affinity;
    const strongAffinity = deriveTasteSnapshot(strong, { now }).longTerm.tracks['youtube-music:t1'].affinity;
    expect(strongAffinity).toBeGreaterThan(weakAffinity);
    expect(strongAffinity).toBeGreaterThan(0.4);
    expect(weakAffinity).toBeLessThan(0.25);
  });

  it('distinguishes artist exposure from preference', () => {
    const events: ListeningEvent[] = [
      // Many starts but early skips — high exposure, low preference
      base({ id: 'a1', type: 'play_started', timestamp: now - 100_000, sessionId: 's1', trackId: 'x1', providerTrackId: 'x1' }),
      base({ id: 'a2', type: 'skip', timestamp: now - 99_000, sessionId: 's1', trackId: 'x1', providerTrackId: 'x1', earlySkip: true, completionRatio: 0.02 }),
      base({ id: 'a3', type: 'play_started', timestamp: now - 90_000, sessionId: 's1', trackId: 'x2', providerTrackId: 'x2' }),
      base({ id: 'a4', type: 'skip', timestamp: now - 89_000, sessionId: 's1', trackId: 'x2', providerTrackId: 'x2', earlySkip: true, completionRatio: 0.03 }),
      base({ id: 'a5', type: 'play_started', timestamp: now - 80_000, sessionId: 's2', trackId: 'x3', providerTrackId: 'x3' }),
      base({ id: 'a6', type: 'skip', timestamp: now - 79_000, sessionId: 's2', trackId: 'x3', providerTrackId: 'x3', earlySkip: true, completionRatio: 0.04 }),
    ];

    const snapshot = deriveTasteSnapshot(events, { now });
    const artist = snapshot.longTerm.artists['artist-name:artist one'];
    expect(artist.exposureScore).toBeGreaterThan(artist.preferenceScore);
    expect(artist.affinity).toBe(artist.preferenceScore);
    expect(artist.evidence.caveats.some((caveat) => caveat.includes('Exposure score'))).toBe(true);
  });

  it('does not treat a single album track as full-album preference', () => {
    const events: ListeningEvent[] = [
      base({ id: 'b1', type: 'play_started', timestamp: now - 50_000, sessionId: 's1' }),
      base({ id: 'b2', type: 'play_completed', timestamp: now - 40_000, sessionId: 's1', completionRatio: 1 }),
      base({ id: 'b3', type: 'favorite', timestamp: now - 35_000, sessionId: 's1' }),
    ];
    const dna = {
      'youtube-music:t1': {
        identity: { artist: 'Artist One', album: 'Big Album' },
        observable: { albumId: 'MPRbig' },
      },
    };
    const album = deriveTasteSnapshot(events, { now, trackDnaByKey: dna }).longTerm.albums['album-id:MPRbig'];
    expect(album.distinctTracks).toBe(1);
    expect(album.evidence.caveats.some((caveat) => caveat.includes('entire album'))).toBe(true);
    expect(album.evidence.caveats.some((caveat) => caveat.includes('down-weighted'))).toBe(true);
  });

  it('builds discovery affinity from attributed play outcomes', () => {
    const events: ListeningEvent[] = [
      base({ id: 'd1', type: 'play_started', timestamp: now - 120_000, sessionId: 's1', discoveryContext: { type: 'scene', id: 'focus' } }),
      base({ id: 'd2', type: 'play_completed', timestamp: now - 110_000, sessionId: 's1', completionRatio: 1 }),
      base({ id: 'd3', type: 'replay', timestamp: now - 105_000, sessionId: 's1' }),
      base({ id: 'd4', type: 'play_started', timestamp: now - 100_000, sessionId: 's1', trackId: 't2', providerTrackId: 't2', discoveryContext: { type: 'scene', id: 'focus' } }),
      base({ id: 'd5', type: 'play_completed', timestamp: now - 90_000, sessionId: 's1', trackId: 't2', providerTrackId: 't2', completionRatio: 0.9 }),
      base({ id: 'd6', type: 'play_started', timestamp: now - 80_000, sessionId: 's1', trackId: 't3', providerTrackId: 't3', discoveryContext: { type: 'scene', id: 'focus' } }),
      base({ id: 'd7', type: 'play_completed', timestamp: now - 70_000, sessionId: 's1', trackId: 't3', providerTrackId: 't3', completionRatio: 1 }),

      base({ id: 'r1', type: 'play_started', timestamp: now - 60_000, sessionId: 's2', trackId: 'r1', providerTrackId: 'r1', discoveryContext: { type: 'related', sourceTrackKey: 'youtube-music:t1' } }),
      base({ id: 'r2', type: 'skip', timestamp: now - 59_000, sessionId: 's2', trackId: 'r1', providerTrackId: 'r1', earlySkip: true, completionRatio: 0.02 }),
      base({ id: 'r3', type: 'play_started', timestamp: now - 50_000, sessionId: 's2', trackId: 'r2', providerTrackId: 'r2', discoveryContext: { type: 'related', sourceTrackKey: 'youtube-music:t1' } }),
      base({ id: 'r4', type: 'skip', timestamp: now - 49_000, sessionId: 's2', trackId: 'r2', providerTrackId: 'r2', earlySkip: true, completionRatio: 0.01 }),
      base({ id: 'r5', type: 'play_started', timestamp: now - 40_000, sessionId: 's2', trackId: 'r3', providerTrackId: 'r3', discoveryContext: { type: 'related', sourceTrackKey: 'youtube-music:t1' } }),
      base({ id: 'r6', type: 'skip', timestamp: now - 39_000, sessionId: 's2', trackId: 'r3', providerTrackId: 'r3', earlySkip: true, completionRatio: 0.03 }),
    ];

    const discovery = deriveTasteSnapshot(events, { now }).discovery;
    expect(discovery.scene.affinity).toBeGreaterThan(discovery.related.affinity);
    expect(discovery.scene.successRate).toBeGreaterThan(0.8);
    expect(discovery.related.earlySkipRate).toBeGreaterThan(0.8);
  });

  it('keeps long-term / recent / session windows separate', () => {
    const recentMs = TASTE_WEIGHTS.recentWindowMs;
    const events: ListeningEvent[] = [
      base({ id: 'old1', type: 'play_started', timestamp: now - recentMs - 10_000, sessionId: 'old' }),
      base({ id: 'old2', type: 'play_completed', timestamp: now - recentMs - 5_000, sessionId: 'old', completionRatio: 1 }),
      base({ id: 'new1', type: 'play_started', timestamp: now - 1_000, sessionId: 'cur', trackId: 'n1', providerTrackId: 'n1', artistName: 'New Artist' }),
      base({ id: 'new2', type: 'skip', timestamp: now - 500, sessionId: 'cur', trackId: 'n1', providerTrackId: 'n1', earlySkip: true, completionRatio: 0.01 }),
    ];

    const snapshot = deriveTasteSnapshot(events, { now, currentSessionId: 'cur', recentWindowMs: recentMs });
    expect(snapshot.longTerm.tracks['youtube-music:t1']).toBeTruthy();
    expect(snapshot.recent.tracks['youtube-music:t1']).toBeUndefined();
    expect(snapshot.recent.tracks['youtube-music:n1']).toBeTruthy();
    expect(snapshot.session?.tracks['youtube-music:n1']).toBeTruthy();
    expect(snapshot.session?.tracks['youtube-music:t1']).toBeUndefined();
  });

  it('never invents album affinity without DNA/album identity', () => {
    const events: ListeningEvent[] = [
      base({ id: 'c1', type: 'play_started', timestamp: now - 10_000, sessionId: 's1' }),
      base({ id: 'c2', type: 'play_completed', timestamp: now - 5_000, sessionId: 's1', completionRatio: 1 }),
    ];
    const snapshot = deriveTasteSnapshot(events, { now });
    expect(Object.keys(snapshot.longTerm.albums)).toHaveLength(0);
  });

  it('persists snapshots only as a recomputable cache', () => {
    const memory = new Map<string, string>();
    const storage = {
      getItem: (key: string) => memory.get(key) ?? null,
      setItem: (key: string, value: string) => { memory.set(key, value); },
      removeItem: (key: string) => { memory.delete(key); },
      clear: () => memory.clear(),
      key: () => null,
      length: 0,
    } as Storage;
    const store = new TasteStore({ storage });
    const snapshot = deriveTasteSnapshot([
      base({ id: 'p1', type: 'play_started', timestamp: now - 1_000, sessionId: 's1' }),
    ], { now });
    store.save(snapshot);
    expect(store.load()?.version).toBe(TASTE_MODEL_VERSION);
    store.clear();
    expect(store.load()).toBeNull();
  });
});
