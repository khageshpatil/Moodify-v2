import type { Track } from '@/data/mockMusic';
import type { DiscoveryContext } from '@/listening/listeningTypes';
import type { RankedCandidate, RecommendationResult } from '@/recommendation/recommendationTypes';

export const HOME_MODEL_VERSION = 'home-v1.0.0';
export const SCENE_CONTRACT_VERSION = 'scene-contract-v1.0.0';
export const MOODIFY_MIX_CONTRACT_VERSION = 'moodify-mix-v1.0.0';

export type HomeSectionId =
  | 'continueListening'
  | 'yourRotation'
  | 'forYou'
  | 'freshFinds'
  | 'scenes'
  | 'moodifyMix';

export type HomeProminence = 'hidden' | 'available' | 'prominent';

export type HomeItemKind = 'track' | 'playlist' | 'scene' | 'mix';

export interface HomeItem {
  id: string;
  kind: HomeItemKind;
  track?: Track;
  playlistId?: string;
  sceneId?: string;
  queue?: Track[];
  discoveryContext?: DiscoveryContext;
  owner?: 'user' | 'mix';
  source: string;
  score?: number;
  provenance?: RankedCandidate['provenance'];
  explanationKeys?: string[];
}

export interface HomeSection {
  id: HomeSectionId;
  title: string;
  visible: boolean;
  prominence: HomeProminence;
  items: HomeItem[];
}

/**
 * Scene is an editorial listening world. V1 defines the contract only.
 * No hardcoded mood catalogue is published here.
 */
export interface SceneContract {
  id: string;
  title: string;
  description?: string;
  artwork?: string;
  trackSources: string[];
  editorial?: Record<string, string>;
  discoveryProvenance?: string[];
  recommendationContext?: string;
}

export interface MoodifyMixContract {
  version: typeof MOODIFY_MIX_CONTRACT_VERSION;
  id: string;
  engineVersion: string;
  strategy: string;
  generatedAt: number;
  candidateKeys: string[];
  source: 'recommendation-engine-v1';
}

export interface MoodifyHomeModel {
  version: typeof HOME_MODEL_VERSION;
  generatedAt: number;
  coldStartTier: RecommendationResult['coldStartTier'];
  sceneContractVersion: typeof SCENE_CONTRACT_VERSION;
  publishedScenes: SceneContract[];
  moodifyMix: MoodifyMixContract | null;
  sections: HomeSection[];
}
