export const TRACK_DNA_SCHEMA_VERSION = 1;

export type TrackDnaInferenceSource = 'external-provider' | 'user' | 'local-model';

export interface TrackDnaEvidence<T> {
  value: T;
  confidence: number;
  source: TrackDnaInferenceSource;
  inferredAt: number;
  version: string;
}

export interface TrackDnaIdentity {
  trackId: string;
  provider: string;
  providerTrackId: string;
  title: string;
  artist: string;
  album?: string;
  durationSeconds?: number;
}

/** Fields copied from a provider or the canonical Track model without interpretation. */
export interface TrackDnaObservable {
  artistId?: string;
  artists?: Array<{ id?: string; name: string }>;
  albumId?: string;
  artworkUrl?: string;
  providerResultType?: string;
}

/** Reproducible values derived only from identity and observable text/duration. */
export interface TrackDnaDerived {
  normalizedTitle: string;
  normalizedArtist: string;
  normalizedAlbum?: string;
  titleTokens: string[];
  artistTokens: string[];
  albumTokens: string[];
  versionMarkers: string[];
  durationBucket: 'unknown' | 'short' | 'standard' | 'long' | 'extended';
}

/** Intentionally optional: an absent field means Moodify does not know it yet. */
export interface TrackDnaInferred {
  language?: TrackDnaEvidence<string>;
  genres?: TrackDnaEvidence<string[]>;
  moods?: TrackDnaEvidence<string[]>;
  culturalContexts?: TrackDnaEvidence<string[]>;
  audioFeatures?: TrackDnaEvidence<Record<string, number>>;
}

export interface TrackDna {
  schemaVersion: typeof TRACK_DNA_SCHEMA_VERSION;
  key: string;
  generatedAt: number;
  identity: TrackDnaIdentity;
  observable: TrackDnaObservable;
  derived: TrackDnaDerived;
  inferred: TrackDnaInferred;
}
