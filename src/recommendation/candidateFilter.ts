import type { TasteSnapshot } from '@/taste/tasteTypes';
import { RECOMMENDATION_WEIGHTS } from './recommendationWeights';
import type { RawCandidate, RecommendationContext } from './recommendationTypes';

export interface FilteredCandidate extends RawCandidate {
  excluded?: string;
}

export interface CandidateFilterResult {
  kept: RawCandidate[];
  filtered: FilteredCandidate[];
  reasons: Record<string, number>;
}

const bump = (reasons: Record<string, number>, reason: string) => {
  reasons[reason] = (reasons[reason] || 0) + 1;
};

export const filterCandidates = (
  candidates: RawCandidate[],
  taste: TasteSnapshot,
  context: RecommendationContext,
): CandidateFilterResult => {
  const kept: RawCandidate[] = [];
  const filtered: FilteredCandidate[] = [];
  const reasons: Record<string, number> = {};
  const now = context.now;
  const currentKey = context.currentTrack ? `${context.currentTrack.provider || 'youtube-music'}:${context.currentTrack.providerId || context.currentTrack.id}` : null;
  const queueSet = new Set([...context.queueTrackKeys, ...(context.recentRecommendedKeys || [])]);
  const recentSkipSet = new Set(context.recentSkips);
  const skipCounts = new Map<string, { early: number; total: number; lastAt: number }>();

  Object.values(taste.longTerm.tracks).forEach((track) => {
    if (track.skips > 0 || track.earlySkips > 0) {
      skipCounts.set(track.trackKey, {
        early: track.earlySkips,
        total: track.skips,
        lastAt: track.lastPlayedAt || 0,
      });
    }
  });

  for (const candidate of candidates) {
    const { trackKey, track } = candidate;

    if (!track.id || !track.providerId) {
      bump(reasons, 'invalid_identity');
      filtered.push({ ...candidate, excluded: 'invalid_identity' });
      continue;
    }

    if (currentKey && trackKey === currentKey) {
      bump(reasons, 'currently_playing');
      filtered.push({ ...candidate, excluded: 'currently_playing' });
      continue;
    }

    if (queueSet.has(trackKey)) {
      bump(reasons, context.recentRecommendedKeys?.includes(trackKey) && !context.queueTrackKeys.includes(trackKey) ? 'recently_recommended' : 'in_queue');
      filtered.push({ ...candidate, excluded: 'in_queue' });
      continue;
    }

    const profile = taste.longTerm.tracks[trackKey];
    const skipInfo = skipCounts.get(trackKey);
    const isFavorite = (profile?.favorites || 0) > 0;

    if (recentSkipSet.has(trackKey)) {
      const age = now - (skipInfo?.lastAt || 0);
      if (age < RECOMMENDATION_WEIGHTS.filter.recentSkipWindowMs && !isFavorite) {
        bump(reasons, 'recent_skip');
        filtered.push({ ...candidate, excluded: 'recent_skip' });
        continue;
      }
    }

    if (skipInfo && skipInfo.total >= RECOMMENDATION_WEIGHTS.filter.repeatedSkipThreshold) {
      const age = now - skipInfo.lastAt;
      if (age < RECOMMENDATION_WEIGHTS.filter.repeatedSkipWindowMs && skipInfo.early >= 2 && !isFavorite) {
        bump(reasons, 'repeated_early_skip');
        filtered.push({ ...candidate, excluded: 'repeated_early_skip' });
        continue;
      }
    }

    kept.push(candidate);
  }

  return { kept, filtered, reasons };
};
