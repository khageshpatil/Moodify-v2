/**
 * Taste Model V1 — types and version contract.
 *
 * Deterministic: same events + same DNA map + same `now` + same version => same snapshot.
 * This is evidence about listening behaviour, not personality or emotion.
 */

import type { DiscoveryContextType } from '@/listening/listeningTypes';

export const TASTE_MODEL_VERSION = 'taste-v1.0.0';

export type TasteWindow = 'long_term' | 'recent' | 'session';

export type TasteSubjectKind = 'track' | 'artist' | 'album' | 'discovery';

export type TasteSignalKind =
  | 'affinity'
  | 'recency'
  | 'frequency'
  | 'completion'
  | 'repetition'
  | 'discovery'
  | 'exposure'
  | 'preference';

export interface TasteContribution {
  signal: string;
  weight: number;
  raw: number;
  contribution: number;
}

export interface TasteEvidence {
  subjectKind: TasteSubjectKind;
  subjectKey: string;
  signal: TasteSignalKind;
  value: number;
  sampleCount: number;
  window: TasteWindow | 'contextual';
  evidenceEventIds: string[];
  contributions: TasteContribution[];
  generatedAt: number;
  version: string;
  /** Human-readable caveats; never psychological claims. */
  caveats: string[];
}

export interface TrackTasteProfile {
  trackKey: string;
  trackId: string;
  provider: string;
  providerTrackId: string;
  artistKey?: string;
  artistName?: string;
  albumKey?: string;
  albumTitle?: string;
  playStarts: number;
  completions: number;
  skips: number;
  earlySkips: number;
  replays: number;
  favorites: number;
  queueAdds: number;
  queueRemoves: number;
  averageCompletion: number;
  distinctSessions: number;
  lastPlayedAt: number | null;
  firstPlayedAt: number | null;
  affinity: number;
  evidence: TasteEvidence;
}

export interface ArtistTasteProfile {
  artistKey: string;
  artistName: string;
  artistId?: string;
  /** Tracks that contributed behaviour (not provider exposure). */
  trackKeys: string[];
  playStarts: number;
  completions: number;
  skips: number;
  earlySkips: number;
  replays: number;
  favorites: number;
  distinctSessions: number;
  distinctTracks: number;
  lastPlayedAt: number | null;
  /** Raw play volume — can include low-engagement exposure. */
  exposureScore: number;
  /** Behaviour-weighted preference — completions/replays/favorites/returns. */
  preferenceScore: number;
  affinity: number;
  evidence: TasteEvidence;
}

export interface AlbumTasteProfile {
  albumKey: string;
  albumTitle: string;
  albumId?: string;
  trackKeys: string[];
  playStarts: number;
  completions: number;
  skips: number;
  earlySkips: number;
  replays: number;
  favorites: number;
  distinctSessions: number;
  distinctTracks: number;
  lastPlayedAt: number | null;
  /** Fraction of observed album tracks with meaningful completion (not whole-album claim). */
  engagementBreadth: number;
  affinity: number;
  evidence: TasteEvidence;
}

export interface DiscoverySurfaceTaste {
  surface: DiscoveryContextType;
  playStarts: number;
  completions: number;
  earlySkips: number;
  skips: number;
  replays: number;
  averageCompletion: number;
  /** Completions / play starts for attributed discoveries. */
  successRate: number;
  /** Early skips / play starts. */
  earlySkipRate: number;
  affinity: number;
  evidence: TasteEvidence;
}

export interface TasteWindowSlice {
  window: TasteWindow;
  tracks: Record<string, TrackTasteProfile>;
  artists: Record<string, ArtistTasteProfile>;
  albums: Record<string, AlbumTasteProfile>;
  /** Top affinities for inspectability (sorted desc). */
  topTrackKeys: string[];
  topArtistKeys: string[];
  topAlbumKeys: string[];
}

export interface TasteSnapshot {
  version: typeof TASTE_MODEL_VERSION;
  generatedAt: number;
  eventCount: number;
  missingSignals: string[];
  longTerm: TasteWindowSlice;
  recent: TasteWindowSlice;
  session: TasteWindowSlice | null;
  discovery: Record<string, DiscoverySurfaceTaste>;
}

export interface TasteModelOptions {
  now?: number;
  /** Optional Track DNA by provider identity key for album/artist IDs. */
  trackDnaByKey?: Record<string, { identity: { artist?: string; album?: string }; observable?: { artistId?: string; albumId?: string } }>;
  currentSessionId?: string;
  recentWindowMs?: number;
  /** Cap evidenceEventIds retained per subject. */
  maxEvidenceIds?: number;
}
