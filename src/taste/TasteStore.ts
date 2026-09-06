import type { TasteSnapshot } from './tasteTypes';
import { TASTE_MODEL_VERSION } from './tasteTypes';

const STORAGE_KEY = 'moodify_taste_snapshot_v1';

export interface TasteStoreOptions {
  storage?: Storage | null;
}

/**
 * Optional local persistence for the latest TasteSnapshot.
 * Taste is always recomputable from listening events; storage is a cache only.
 */
export class TasteStore {
  private readonly storage: Storage | null;

  constructor(options: TasteStoreOptions = {}) {
    this.storage = options.storage === undefined
      ? typeof window === 'undefined' ? null : window.localStorage
      : options.storage;
  }

  load(): TasteSnapshot | null {
    if (!this.storage) return null;
    try {
      const parsed = JSON.parse(this.storage.getItem(STORAGE_KEY) || 'null') as TasteSnapshot | null;
      if (!parsed || parsed.version !== TASTE_MODEL_VERSION) return null;
      return parsed;
    } catch {
      return null;
    }
  }

  save(snapshot: TasteSnapshot) {
    if (!this.storage) return;
    try {
      this.storage.setItem(STORAGE_KEY, JSON.stringify(snapshot));
    } catch {
      // Taste cache must never affect playback.
    }
  }

  clear() {
    if (!this.storage) return;
    try {
      this.storage.removeItem(STORAGE_KEY);
    } catch {
      // Ignore unavailable storage.
    }
  }
}

export const tasteStore = new TasteStore();
