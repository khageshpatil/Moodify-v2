import type { Track } from '@/data/mockMusic';
import { discoveryContextForRecommendation } from '@/listening/discoveryAttribution';
import type { DiscoveryContext } from '@/listening/listeningTypes';
import { trackKeyFromTrack } from './graphUtils';
import type { RankedCandidate } from './recommendationTypes';

export const AUTOPLAY_PREFETCH_REMAINING = 1;
export const AUTOPLAY_INJECT_COUNT = 5;
export const AUTOPLAY_RECENT_LIMIT = 40;

export type RepeatMode = 'off' | 'all' | 'one';

export interface RecommendationBatch {
  id: string;
  generatedAt: number;
  strategy: string;
  signature: string;
  candidates: RankedCandidate[];
  injectedKeys: string[];
  failedKeys: string[];
  consumedKeys: string[];
}

export interface AutoplayDiagnostics {
  remaining: number;
  prefetch: boolean;
  injected: boolean;
  reason: string;
  batchId?: string;
  injectedKeys: string[];
  failedKeys: string[];
}

export const remainingQueue = (currentIndex: number, queueLength: number) => {
  if (queueLength <= 0) return 0;
  if (currentIndex < 0) return queueLength;
  return Math.max(0, queueLength - currentIndex - 1);
};

export const userQueueSignature = (tracks: Track[]) =>
  tracks.map((track) => trackKeyFromTrack(track)).join('|');

export const shouldPrefetchRecommendations = (remaining: number, repeat: RepeatMode, threshold = AUTOPLAY_PREFETCH_REMAINING) =>
  repeat === 'off' && remaining <= threshold;

export const shouldInjectRecommendations = (remaining: number, repeat: RepeatMode) =>
  repeat === 'off' && remaining === 0;

export const isPlayableRecommendation = (track: Track) =>
  Boolean(track.providerId && track.title);

export const batchHasUnconsumed = (batch: RecommendationBatch | null) => {
  if (!batch) return false;
  return batch.candidates.some((candidate) =>
    !batch.injectedKeys.includes(candidate.trackKey)
    && !batch.failedKeys.includes(candidate.trackKey)
    && !batch.consumedKeys.includes(candidate.trackKey));
};

export const shouldReplaceBatch = (
  batch: RecommendationBatch | null,
  nextSignature: string,
) => !batch || batch.signature !== nextSignature || !batchHasUnconsumed(batch);

export const createRecommendationBatch = (
  candidates: RankedCandidate[],
  strategy: string,
  signature: string,
  generatedAt: number,
  limit = AUTOPLAY_INJECT_COUNT,
): RecommendationBatch => {
  const selected = candidates.slice(0, Math.max(limit, AUTOPLAY_INJECT_COUNT));
  const id = `rec-${generatedAt}-${selected.map((candidate) => candidate.trackKey).join('.')}`;
  return {
    id,
    generatedAt,
    strategy,
    signature,
    candidates: selected,
    injectedKeys: [],
    failedKeys: [],
    consumedKeys: [],
  };
};

export const selectInjectableRecommendations = (
  batch: RecommendationBatch,
  blockedKeys: Set<string>,
  count = AUTOPLAY_INJECT_COUNT,
): RankedCandidate[] => {
  const selected: RankedCandidate[] = [];
  for (const candidate of batch.candidates) {
    if (selected.length >= count) break;
    if (batch.injectedKeys.includes(candidate.trackKey)) continue;
    if (batch.failedKeys.includes(candidate.trackKey)) continue;
    if (batch.consumedKeys.includes(candidate.trackKey)) continue;
    if (blockedKeys.has(candidate.trackKey)) continue;
    if (!isPlayableRecommendation(candidate.track)) {
      batch.failedKeys.push(candidate.trackKey);
      continue;
    }
    selected.push(candidate);
  }
  return selected;
};

export const stampRecommendationContexts = (
  batch: RecommendationBatch,
  candidates: RankedCandidate[],
  sourceTrackKey?: string,
): Array<{ track: Track; discoveryContext: DiscoveryContext }> =>
  candidates.map((candidate, index) => ({
    track: candidate.track,
    discoveryContext: discoveryContextForRecommendation(
      batch.id,
      index,
      batch.strategy,
      [...new Set(candidate.provenance.map((entry) => entry.source))],
      sourceTrackKey,
    ),
  }));

export const markBatchInjected = (batch: RecommendationBatch, keys: string[]) => {
  keys.forEach((key) => {
    if (!batch.injectedKeys.includes(key)) batch.injectedKeys.push(key);
  });
  return batch;
};

export const markBatchFailed = (batch: RecommendationBatch, key: string) => {
  if (!batch.failedKeys.includes(key)) batch.failedKeys.push(key);
  return batch;
};

export const markBatchConsumed = (batch: RecommendationBatch, key: string) => {
  if (!batch.consumedKeys.includes(key)) batch.consumedKeys.push(key);
  return batch;
};

export const applyAutoplayInjection = (
  queue: Track[],
  currentIndex: number,
  stamped: Array<{ track: Track; discoveryContext: DiscoveryContext }>,
) => {
  const tracks = stamped.map((entry) => entry.track);
  return {
    queue: [...queue, ...tracks],
    nextIndex: currentIndex + 1,
    nextTrack: tracks[0] || null,
  };
};

