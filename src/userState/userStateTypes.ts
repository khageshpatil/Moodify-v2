import type { Track } from '@/data/mockMusic';
import type { ListeningEvent, ListeningSession } from '@/listening/listeningTypes';
import type { OpenListeningThread } from '@/listening/openThread';
import type { TasteSnapshot } from '@/taste/tasteTypes';
import type { RecommendationBatch } from '@/recommendation/recommendationAutoplay';
import type { RecommendationResult } from '@/recommendation/recommendationTypes';
import type { RecommendationEvalEvent } from '@/recommendation/recommendationEval';

export const USER_STATE_SCHEMA_VERSION = 1;

export type PlaybackRepeat = 'off' | 'all' | 'one';

export interface UserPlaybackPreferences {
  volume: number;
  shuffle: boolean;
  repeat: PlaybackRepeat;
}

export interface SavedLibraryExtras {
  savedAlbumKeys: string[];
  savedArtistKeys: string[];
  savedSceneIds: string[];
}

/**
 * Envelope persisted by LocalUserStateStore.
 * Canonical listening/library evidence remains in existing stores.
 */
export interface PersistedUserEnvelope {
  schemaVersion: number;
  updatedAt: number;
  preferences: UserPlaybackPreferences;
  libraryExtras: SavedLibraryExtras;
  openThread: OpenListeningThread | null;
}

export interface MoodifyUserState {
  schemaVersion: number;
  generatedAt: number;
  identity: { kind: 'anonymous-local'; note: string };
  listening: {
    eventCount: number;
    historyCount: number;
    recentCount: number;
    favoriteCount: number;
    skipCount: number;
    session: ListeningSession | null;
    openThread: OpenListeningThread | null;
  };
  library: {
    playlistCount: number;
    savedTrackCount: number;
    savedAlbumKeys: string[];
    savedArtistKeys: string[];
    savedSceneIds: string[];
  };
  discovery: {
    graphNodeCount: number;
    graphEdgeCount: number;
    surfaces: string[];
  };
  taste: TasteSnapshot | null;
  recommendations: {
    result: RecommendationResult | null;
    batch: RecommendationBatch | null;
    evaluation: RecommendationEvalEvent[];
  };
  preferences: UserPlaybackPreferences;
  sources: {
    listeningEvents: 'moodify_listening_events';
    playlists: 'moodify_playlists';
    favorites: 'moodify_favorites';
    history: 'moodify_history';
    recentlyPlayed: 'moodify_recently_played';
    volume: 'moodify_volume';
    identity: 'moodify_identity';
    trackDna: 'moodify_track_dna_v1';
    recommendationEval: 'moodify_recommendation_eval_v1';
    envelope: 'moodify_user_state_v1';
  };
}

export interface UserStateAssemblyInput {
  now: number;
  events: ListeningEvent[];
  session: ListeningSession | null;
  playlists: Array<{ id: string; tracks: Track[] }>;
  favorites: Track[];
  history: unknown[];
  recentlyPlayed: Track[];
  graphNodeCount: number;
  graphEdgeCount: number;
  discoverySurfaces: string[];
  taste: TasteSnapshot | null;
  recommendationResult: RecommendationResult | null;
  recommendationBatch: RecommendationBatch | null;
  evaluation: RecommendationEvalEvent[];
  envelope: PersistedUserEnvelope;
  volume: number;
  shuffle: boolean;
  repeat: PlaybackRepeat;
}

export interface UserStateStore {
  loadEnvelope(): PersistedUserEnvelope;
  saveEnvelope(envelope: PersistedUserEnvelope): PersistedUserEnvelope;
  savePreferences(preferences: UserPlaybackPreferences): PersistedUserEnvelope;
  saveLibraryExtras(extras: Partial<SavedLibraryExtras>): PersistedUserEnvelope;
  saveOpenThread(thread: OpenListeningThread | null): PersistedUserEnvelope;
}

export const emptyLibraryExtras = (): SavedLibraryExtras => ({
  savedAlbumKeys: [],
  savedArtistKeys: [],
  savedSceneIds: [],
});

export const defaultPreferences = (): UserPlaybackPreferences => ({
  volume: 70,
  shuffle: false,
  repeat: 'off',
});

export const emptyEnvelope = (now = 0): PersistedUserEnvelope => ({
  schemaVersion: USER_STATE_SCHEMA_VERSION,
  updatedAt: now,
  preferences: defaultPreferences(),
  libraryExtras: emptyLibraryExtras(),
  openThread: null,
});
