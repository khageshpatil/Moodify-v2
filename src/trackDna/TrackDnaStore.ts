import type { TrackDna } from './trackDnaTypes';
import { TRACK_DNA_SCHEMA_VERSION } from './trackDnaTypes';

const STORAGE_KEY = 'moodify_track_dna_v1';
const DEFAULT_MAX_RECORDS = 1000;

export interface TrackDnaStoreOptions {
  maxRecords?: number;
  storage?: Storage | null;
}

export class TrackDnaStore {
  private readonly maxRecords: number;
  private readonly storage: Storage | null;

  constructor(options: TrackDnaStoreOptions = {}) {
    this.maxRecords = options.maxRecords ?? DEFAULT_MAX_RECORDS;
    this.storage = options.storage === undefined
      ? typeof window === 'undefined' ? null : window.localStorage
      : options.storage;
  }

  load(): Record<string, TrackDna> {
    if (!this.storage) return {};
    try {
      const parsed = JSON.parse(this.storage.getItem(STORAGE_KEY) || '{}') as Record<string, TrackDna>;
      return Object.fromEntries(Object.entries(parsed).filter(([, dna]) => dna?.schemaVersion === TRACK_DNA_SCHEMA_VERSION && typeof dna.key === 'string'));
    } catch {
      return {};
    }
  }

  get(key: string) { return this.load()[key]; }

  upsert(dna: TrackDna) {
    const records = this.load();
    records[dna.key] = dna;
    const bounded = Object.fromEntries(Object.entries(records).sort(([, a], [, b]) => a.generatedAt - b.generatedAt).slice(-this.maxRecords));
    this.write(bounded);
    return dna;
  }

  remove(key: string) {
    const records = this.load();
    delete records[key];
    this.write(records);
  }

  private write(records: Record<string, TrackDna>) {
    if (!this.storage) return;
    try {
      this.storage.setItem(STORAGE_KEY, JSON.stringify(records));
    } catch {
      // DNA is an optimization layer. Storage failure must never affect playback.
    }
  }
}

export const trackDnaStore = new TrackDnaStore();
