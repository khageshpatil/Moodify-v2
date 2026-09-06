import { USER_STATE_SCHEMA_VERSION, emptyEnvelope, type PersistedUserEnvelope, type SavedLibraryExtras, type UserPlaybackPreferences, type UserStateStore } from './userStateTypes';
import { reviveOpenThread, type OpenListeningThread } from '@/listening/openThread';

export const USER_STATE_ENVELOPE_KEY = 'moodify_user_state_v1';

const asStringArray = (value: unknown) =>
  Array.isArray(value) ? value.filter((entry): entry is string => typeof entry === 'string' && entry.trim().length > 0) : [];

export const migrateEnvelope = (value: unknown, now = 0): PersistedUserEnvelope => {
  const base = emptyEnvelope(now);
  if (!value || typeof value !== 'object') return base;
  const raw = value as Partial<PersistedUserEnvelope> & { schemaVersion?: number };
  const version = typeof raw.schemaVersion === 'number' ? raw.schemaVersion : 0;
  if (version > USER_STATE_SCHEMA_VERSION) return base;
  const preferences = raw.preferences && typeof raw.preferences === 'object'
    ? {
        volume: typeof raw.preferences.volume === 'number' ? raw.preferences.volume : base.preferences.volume,
        shuffle: Boolean(raw.preferences.shuffle),
        repeat: (raw.preferences.repeat === 'all' || raw.preferences.repeat === 'one' ? raw.preferences.repeat : 'off') as UserPlaybackPreferences['repeat'],
      }
    : base.preferences;
  const extras = raw.libraryExtras && typeof raw.libraryExtras === 'object'
    ? {
        savedAlbumKeys: asStringArray(raw.libraryExtras.savedAlbumKeys).slice(0, 200),
        savedArtistKeys: asStringArray(raw.libraryExtras.savedArtistKeys).slice(0, 200),
        savedSceneIds: asStringArray(raw.libraryExtras.savedSceneIds).slice(0, 50),
      }
    : base.libraryExtras;
  return {
    schemaVersion: USER_STATE_SCHEMA_VERSION,
    updatedAt: typeof raw.updatedAt === 'number' ? raw.updatedAt : now,
    preferences,
    libraryExtras: extras,
    openThread: reviveOpenThread(raw.openThread),
  };
};

export class LocalUserStateStore implements UserStateStore {
  constructor(private readonly storage: Storage | null = typeof window === 'undefined' ? null : window.localStorage) {}

  loadEnvelope(): PersistedUserEnvelope {
    if (!this.storage) return emptyEnvelope();
    try {
      const raw = this.storage.getItem(USER_STATE_ENVELOPE_KEY);
      if (!raw) return emptyEnvelope();
      return migrateEnvelope(JSON.parse(raw));
    } catch {
      return emptyEnvelope();
    }
  }

  saveEnvelope(envelope: PersistedUserEnvelope): PersistedUserEnvelope {
    const next = migrateEnvelope(envelope, envelope.updatedAt);
    if (!this.storage) return next;
    try {
      this.storage.setItem(USER_STATE_ENVELOPE_KEY, JSON.stringify(next));
      return next;
    } catch {
      const compact: PersistedUserEnvelope = {
        ...next,
        libraryExtras: {
          savedAlbumKeys: next.libraryExtras.savedAlbumKeys.slice(0, 20),
          savedArtistKeys: next.libraryExtras.savedArtistKeys.slice(0, 20),
          savedSceneIds: next.libraryExtras.savedSceneIds.slice(0, 10),
        },
        openThread: next.openThread
          ? { ...next.openThread, remainder: next.openThread.remainder.slice(0, 8) }
          : null,
      };
      try {
        this.storage.setItem(USER_STATE_ENVELOPE_KEY, JSON.stringify(compact));
        return compact;
      } catch {
        return next;
      }
    }
  }

  savePreferences(preferences: UserPlaybackPreferences): PersistedUserEnvelope {
    const current = this.loadEnvelope();
    return this.saveEnvelope({ ...current, preferences, updatedAt: Date.now() });
  }

  saveLibraryExtras(extras: Partial<SavedLibraryExtras>): PersistedUserEnvelope {
    const current = this.loadEnvelope();
    return this.saveEnvelope({
      ...current,
      libraryExtras: { ...current.libraryExtras, ...extras },
      updatedAt: Date.now(),
    });
  }

  saveOpenThread(thread: OpenListeningThread | null): PersistedUserEnvelope {
    const current = this.loadEnvelope();
    return this.saveEnvelope({ ...current, openThread: thread, updatedAt: Date.now() });
  }
}

export const localUserStateStore = new LocalUserStateStore();
