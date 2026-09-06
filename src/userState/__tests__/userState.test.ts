import { describe, expect, it } from 'vitest';
import { assembleUserState } from '../assembleUserState';
import { estimateUserStateBytes } from '../estimateUserStateBytes';
import { LocalUserStateStore, migrateEnvelope, USER_STATE_ENVELOPE_KEY } from '../localUserStateStore';
import { USER_STATE_SCHEMA_VERSION, emptyEnvelope, type UserStateAssemblyInput } from '../userStateTypes';
import type { ListeningEvent } from '@/listening/listeningTypes';
import { deriveTasteSnapshot } from '@/taste/tasteModel';
import { deriveRecommendationOutcomes } from '@/recommendation/recommendationEval';
import { RECOMMENDATION_ENGINE_VERSION } from '@/recommendation/recommendationTypes';

class MemoryStorage implements Storage {
  private data = new Map<string, string>();
  get length() { return this.data.size; }
  clear() { this.data.clear(); }
  getItem(key: string) { return this.data.has(key) ? this.data.get(key)! : null; }
  key(index: number) { return [...this.data.keys()][index] ?? null; }
  removeItem(key: string) { this.data.delete(key); }
  setItem(key: string, value: string) { this.data.set(key, String(value)); }
}

class QuotaStorage extends MemoryStorage {
  setItem(key: string, value: string) {
    if (value.length > 1500) {
      const error = new Error('QuotaExceededError');
      error.name = 'QuotaExceededError';
      throw error;
    }
    super.setItem(key, value);
  }
}

const NOW = 1_700_000_000_000;

const event = (overrides: Partial<ListeningEvent> & Pick<ListeningEvent, 'id' | 'type'>): ListeningEvent => ({
  trackId: 't1',
  provider: 'youtube-music',
  providerTrackId: 't1',
  sessionId: 's1',
  timestamp: NOW,
  artistName: 'Artist A',
  ...overrides,
});

const baseInput = (overrides: Partial<UserStateAssemblyInput> = {}): UserStateAssemblyInput => ({
  now: NOW,
  events: [],
  session: null,
  playlists: [],
  favorites: [],
  history: [],
  recentlyPlayed: [],
  graphNodeCount: 0,
  graphEdgeCount: 0,
  discoverySurfaces: [],
  taste: null,
  recommendationResult: null,
  recommendationBatch: null,
  evaluation: [],
  envelope: emptyEnvelope(NOW),
  volume: 70,
  shuffle: false,
  repeat: 'off',
  ...overrides,
});

describe('MoodifyUserState V1', () => {
  it('initializes empty user state without fabricating identity or taste', () => {
    const state = assembleUserState(baseInput());
    expect(state.schemaVersion).toBe(USER_STATE_SCHEMA_VERSION);
    expect(state.identity.kind).toBe('anonymous-local');
    expect(state.listening.eventCount).toBe(0);
    expect(state.taste).toBeNull();
    expect(state.recommendations.result).toBeNull();
    expect(state.library.savedAlbumKeys).toEqual([]);
  });

  it('assembles counts from existing evidence stores rather than duplicating them', () => {
    const events = [
      event({ id: 'e1', type: 'play_started' }),
      event({ id: 'e2', type: 'skip', timestamp: NOW + 1 }),
    ];
    const state = assembleUserState(baseInput({
      events,
      favorites: [{ id: 't1', title: 'One', artist: 'A', album: 'Al', albumArt: '', duration: 1 }],
      playlists: [{ id: 'p1', tracks: [] }],
      history: [{}],
      recentlyPlayed: [{ id: 't1', title: 'One', artist: 'A', album: 'Al', albumArt: '', duration: 1 }],
      discoverySurfaces: ['search', 'playlist'],
    }));
    expect(state.listening.eventCount).toBe(2);
    expect(state.listening.skipCount).toBe(1);
    expect(state.listening.favoriteCount).toBe(1);
    expect(state.library.playlistCount).toBe(1);
    expect(state.library.savedTrackCount).toBe(1);
    expect(state.discovery.surfaces).toEqual(['playlist', 'search']);
    expect(state.sources.listeningEvents).toBe('moodify_listening_events');
  });

  it('persists and reloads the envelope', () => {
    const storage = new MemoryStorage();
    const store = new LocalUserStateStore(storage);
    store.savePreferences({ volume: 40, shuffle: true, repeat: 'all' });
    store.saveLibraryExtras({ savedAlbumKeys: ['album:a'] });
    const loaded = store.loadEnvelope();
    expect(loaded.preferences.volume).toBe(40);
    expect(loaded.preferences.shuffle).toBe(true);
    expect(loaded.libraryExtras.savedAlbumKeys).toEqual(['album:a']);
    expect(JSON.parse(storage.getItem(USER_STATE_ENVELOPE_KEY) || '{}').schemaVersion).toBe(USER_STATE_SCHEMA_VERSION);
  });

  it('recovers from corrupt state', () => {
    const storage = new MemoryStorage();
    storage.setItem(USER_STATE_ENVELOPE_KEY, '{not-json');
    const loaded = new LocalUserStateStore(storage).loadEnvelope();
    expect(loaded).toEqual(emptyEnvelope());
  });

  it('migrates missing and older schema records', () => {
    const migrated = migrateEnvelope({ schemaVersion: 0, preferences: { volume: 12 } }, NOW);
    expect(migrated.schemaVersion).toBe(USER_STATE_SCHEMA_VERSION);
    expect(migrated.preferences.volume).toBe(12);
    expect(migrated.preferences.repeat).toBe('off');
    expect(migrated.libraryExtras.savedSceneIds).toEqual([]);
    expect(migrated.openThread).toBeNull();
  });

  it('compacts library extras when storage quota is exceeded', () => {
    const storage = new QuotaStorage();
    const store = new LocalUserStateStore(storage);
    const saved = store.saveEnvelope({
      schemaVersion: 1,
      updatedAt: NOW,
      preferences: { volume: 70, shuffle: false, repeat: 'off' },
      libraryExtras: {
        savedAlbumKeys: Array.from({ length: 200 }, (_, index) => `album-${index}`),
        savedArtistKeys: Array.from({ length: 200 }, (_, index) => `artist-${index}`),
        savedSceneIds: Array.from({ length: 50 }, (_, index) => `scene-${index}`),
      },
      openThread: null,
    });
    expect(saved.libraryExtras.savedAlbumKeys.length).toBeLessThanOrEqual(20);
    expect(store.loadEnvelope().libraryExtras.savedAlbumKeys.length).toBeGreaterThan(0);
  });

  it('estimates storage size from owned keys only', () => {
    const storage = new MemoryStorage();
    storage.setItem('moodify_listening_events', 'abc');
    storage.setItem('unrelated', 'zzzz');
    expect(estimateUserStateBytes(storage)).toBe(3);
  });

  it('rebuilds derived taste from canonical listening events', () => {
    const events = [
      event({ id: 'e1', type: 'play_started', timestamp: NOW - 20_000 }),
      event({ id: 'e2', type: 'play_completed', timestamp: NOW - 10_000, completionRatio: 1 }),
    ];
    const taste = deriveTasteSnapshot(events, { now: NOW });
    const state = assembleUserState(baseInput({ events, taste }));
    expect(state.taste).toBe(taste);
    expect(state.taste?.eventCount).toBe(2);
    expect(state.taste?.longTerm.tracks['youtube-music:t1']).toBeDefined();
  });

  it('keeps recommendation evaluation separate from shown home impressions', () => {
    const events = [
      event({
        id: 'e1',
        type: 'play_started',
        discoveryContext: { type: 'recommendation', id: 'batch-1', strategy: 'continue-listening' },
      }),
    ];
    const evaluation = deriveRecommendationOutcomes(events);
    const state = assembleUserState(baseInput({ events, evaluation }));
    expect(evaluation.every((entry) => entry.type !== 'shown')).toBe(true);
    expect(state.recommendations.evaluation).toEqual(evaluation);
    expect(state.recommendations.result).toBeNull();
  });

  it('does not treat recommendation engine version as stored user evidence', () => {
    const state = assembleUserState(baseInput());
    expect(JSON.stringify(state)).not.toContain(RECOMMENDATION_ENGINE_VERSION);
  });

  it('persists and revives the open listening thread without turning it into history', () => {
    const storage = new MemoryStorage();
    const store = new LocalUserStateStore(storage);
    const thread = {
      track: { id: 't9', title: 'Open', artist: 'A', album: 'Al', albumArt: '', duration: 180, provider: 'youtube-music' as const, providerId: 't9' },
      remainder: [{ id: 't8', title: 'Next', artist: 'A', album: 'Al', albumArt: '', duration: 180, provider: 'youtube-music' as const, providerId: 't8' }],
      origin: { type: 'vibe' as const, id: 'late-train-home' },
      owner: 'user' as const,
      updatedAt: NOW,
    };
    store.saveOpenThread(thread);
    const loaded = store.loadEnvelope();
    expect(loaded.openThread?.track.id).toBe('t9');
    expect(loaded.openThread?.remainder.map((entry) => entry.id)).toEqual(['t8']);
    expect(loaded.openThread?.origin).toEqual({ type: 'vibe', id: 'late-train-home' });
    const state = assembleUserState(baseInput({ envelope: loaded }));
    expect(state.listening.openThread?.track.id).toBe('t9');
    expect(state.listening.historyCount).toBe(0);
  });
});
