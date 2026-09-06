import type { Track } from '@/data/mockMusic';
import type { DiscoveryContext, DiscoveryContextType } from '@/listening/listeningTypes';
import type { TasteSnapshot } from '@/taste/tasteTypes';
import type { TrackDna } from '@/trackDna/trackDnaTypes';

export const RECOMMENDATION_ENGINE_VERSION = 'recommendation-v1.0.0';

export type RecommendationMode =
  | 'continue-listening'
  | 'discover'
  | 'familiar'
  | 'session'
  | 'library';

export type CandidateSourceKind =
  | 'taste-track'
  | 'taste-artist'
  | 'taste-album'
  | 'graph-related'
  | 'graph-artist'
  | 'graph-album'
  | 'graph-playlist'
  | 'graph-up-next'
  | 'graph-explore'
  | 'graph-home'
  | 'discovery-surface'
  | 'session-sequence'
  | 'familiarity'
  | 'exploration'
  | 'fallback-graph';

export interface CandidateProvenance {
  source: CandidateSourceKind;
  sourceTrackKey?: string;
  sourceArtistKey?: string;
  sourceAlbumKey?: string;
  sourceSurface?: DiscoveryContextType | string;
  graphEdgeType?: string;
  graphPath?: string[];
  tasteAffinity?: number;
  discoveryAffinity?: number;
  evidence: string[];
}

export interface RecommendationTrack extends Track {
  trackKey: string;
}

export interface RawCandidate {
  trackKey: string;
  track: RecommendationTrack;
  provenance: CandidateProvenance[];
  isExploration: boolean;
  /** Pre-rank graph relationship strength [0,1]. */
  graphStrength: number;
}

export interface ScoreComponent {
  name: string;
  value: number;
}

export interface RankedCandidate {
  trackKey: string;
  track: RecommendationTrack;
  score: number;
  confidence: number;
  provenance: CandidateProvenance[];
  components: Record<string, number>;
  penalties: Record<string, number>;
  diversityAdjustment: number;
  isExploration: boolean;
  isPersonalized: boolean;
  explanationKeys: string[];
  caveats: string[];
}

export interface RecommendationContext {
  now: number;
  mode: RecommendationMode;
  currentTrack?: Track | null;
  currentArtistKey?: string;
  sessionId?: string;
  recentTrackKeys: string[];
  recentSkips: string[];
  recentReplays: string[];
  queueTrackKeys: string[];
  recentRecommendedKeys?: string[];
  discoveryContext?: DiscoveryContext;
  limit?: number;
}

export interface DiscoveryGraphNode {
  key: string;
  type: 'track' | 'artist' | 'album' | 'playlist' | 'context' | string;
  provider?: string;
  providerId?: string;
  title?: string;
  track?: {
    id: string;
    title?: string;
    artist?: string;
    album?: string | { title?: string };
    artwork?: { url?: string };
    durationMs?: number;
    provider?: string;
  };
}

export interface DiscoveryGraphEdge {
  key: string;
  type: string;
  fromKey: string;
  toKey: string;
  position?: number;
  observedAt?: number;
}

export interface DiscoveryGraphSnapshot {
  schemaVersion?: number;
  nodes: DiscoveryGraphNode[];
  edges: DiscoveryGraphEdge[];
  snapshots?: unknown[];
}

export interface RecommendationEngineInput {
  taste: TasteSnapshot;
  graph: DiscoveryGraphSnapshot;
  trackDnaByKey: Record<string, TrackDna>;
  context: RecommendationContext;
}

export interface RecommendationDiagnostics {
  coldStartTier: 'empty' | 'sparse' | 'emerging' | 'mature';
  rawCandidateCount: number;
  filteredCount: number;
  rankedCount: number;
  finalCount: number;
  explorationCount: number;
  personalizedCount: number;
  filteredReasons: Record<string, number>;
}

export interface RecommendationResult {
  version: typeof RECOMMENDATION_ENGINE_VERSION;
  generatedAt: number;
  context: RecommendationContext;
  strategy: RecommendationMode;
  coldStartTier: RecommendationDiagnostics['coldStartTier'];
  candidates: RankedCandidate[];
  diagnostics: RecommendationDiagnostics;
}

export interface RecommendationEngineOptions {
  explorationRatio?: number;
  maxGraphDepth?: number;
  maxRawCandidates?: number;
  maxResults?: number;
}
