import type { ListeningEvent } from '@/listening/listeningTypes';
import { identityKey } from '@/listening/listeningTypes';
import { generateCandidates } from './candidateGenerator';
import { filterCandidates } from './candidateFilter';
import { rankCandidates } from './deterministicRanker';
import { applyDiversity } from './diversityController';
import { trackKeyFromTrack } from './graphUtils';
import { RECOMMENDATION_WEIGHTS } from './recommendationWeights';
import type {
  RecommendationEngineInput,
  RecommendationEngineOptions,
  RecommendationResult,
} from './recommendationTypes';
import { RECOMMENDATION_ENGINE_VERSION } from './recommendationTypes';

export const resolveColdStartTier = (eventCount: number): RecommendationResult['coldStartTier'] => {
  const tiers = RECOMMENDATION_WEIGHTS.coldStart;
  if (eventCount <= tiers.emptyMaxEvents) return 'empty';
  if (eventCount <= tiers.sparseMaxEvents) return 'sparse';
  if (eventCount <= tiers.emergingMaxEvents) return 'emerging';
  return 'mature';
};

export const resolveExplorationRatio = (tier: RecommendationResult['coldStartTier'], override?: number) => {
  if (override !== undefined) return Math.min(0.45, Math.max(0.10, override));
  switch (tier) {
    case 'empty': return 1 - RECOMMENDATION_WEIGHTS.exploration.coldStartExploitationRatio;
    case 'sparse': return 1 - RECOMMENDATION_WEIGHTS.exploration.sparseExploitationRatio;
    case 'emerging': return 1 - RECOMMENDATION_WEIGHTS.exploration.emergingExploitationRatio;
    default: return 1 - RECOMMENDATION_WEIGHTS.exploration.defaultExploitationRatio;
  }
};

export const buildRecommendationContextFromEvents = (
  events: ListeningEvent[],
  overrides: Partial<RecommendationEngineInput['context']> = {},
): RecommendationEngineInput['context'] => {
  const sorted = [...events].sort((a, b) => b.timestamp - a.timestamp || b.id.localeCompare(a.id));
  const recentTrackKeys: string[] = [];
  const recentSkips: string[] = [];
  const recentReplays: string[] = [];
  const seen = new Set<string>();

  sorted.forEach((event) => {
    const key = identityKey(event);
    if (event.type === 'play_started' && !seen.has(key)) {
      recentTrackKeys.push(key);
      seen.add(key);
    }
    if (event.type === 'skip') recentSkips.push(key);
    if (event.type === 'replay') recentReplays.push(key);
  });

  return {
    now: overrides.now ?? 0,
    mode: overrides.mode ?? 'continue-listening',
    currentTrack: overrides.currentTrack,
    currentArtistKey: overrides.currentArtistKey,
    sessionId: overrides.sessionId,
    recentTrackKeys: overrides.recentTrackKeys ?? recentTrackKeys,
    recentSkips: overrides.recentSkips ?? recentSkips,
    recentReplays: overrides.recentReplays ?? recentReplays,
    queueTrackKeys: overrides.queueTrackKeys ?? [],
    recentRecommendedKeys: overrides.recentRecommendedKeys ?? [],
    discoveryContext: overrides.discoveryContext,
    limit: overrides.limit,
  };
};

/**
 * Deterministic recommendation pipeline:
 * generate → filter → rank → diversify
 */
export const generateRecommendations = (
  input: RecommendationEngineInput,
  options: RecommendationEngineOptions = {},
): RecommendationResult => {
  const limit = options.maxResults ?? input.context.limit ?? 20;
  const coldStartTier = resolveColdStartTier(input.taste.eventCount);
  const explorationRatio = resolveExplorationRatio(coldStartTier, options.explorationRatio);

  const rawCandidates = generateCandidates(
    input.taste,
    input.graph,
    input.trackDnaByKey,
    input.context,
  );

  const { kept, filtered, reasons } = filterCandidates(rawCandidates, input.taste, input.context);
  const ranked = rankCandidates(kept, input.taste, input.trackDnaByKey, input.context, coldStartTier);
  const finalCandidates = applyDiversity(ranked, input.taste, input.trackDnaByKey, limit, explorationRatio);

  const currentKey = input.context.currentTrack
    ? trackKeyFromTrack(input.context.currentTrack)
    : undefined;

  return {
    version: RECOMMENDATION_ENGINE_VERSION,
    generatedAt: input.context.now,
    context: input.context,
    strategy: input.context.mode,
    coldStartTier,
    candidates: finalCandidates,
    diagnostics: {
      coldStartTier,
      rawCandidateCount: rawCandidates.length,
      filteredCount: filtered.length,
      rankedCount: ranked.length,
      finalCount: finalCandidates.length,
      explorationCount: finalCandidates.filter((candidate) => candidate.isExploration).length,
      personalizedCount: finalCandidates.filter((candidate) => candidate.isPersonalized).length,
      filteredReasons: reasons,
    },
  };
};
