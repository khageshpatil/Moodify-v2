import type { ListeningEvent } from '@/listening/listeningTypes';
import { deriveTasteSnapshot } from '@/taste/tasteModel';
import type { TrackDna } from '@/trackDna/trackDnaTypes';
import { generateRecommendations } from './recommendationEngine';
import { buildRecommendationContextFromEvents } from './recommendationEngine';
import type { DiscoveryGraphSnapshot, RecommendationResult } from './recommendationTypes';
import type { TasteSnapshot } from '@/taste/tasteTypes';

export interface RecommendationReplayStep {
  at: number;
  playEventId: string;
  trackKey: string;
  eventCount: number;
  taste: TasteSnapshot;
  recommendations: RecommendationResult;
}

/**
 * Development/test-only replay: for each play_started, recompute taste from
 * prior events and inspect what Moodify would have recommended next.
 */
export const replayRecommendationHistory = (input: {
  events: ListeningEvent[];
  graph: DiscoveryGraphSnapshot;
  trackDnaByKey: Record<string, TrackDna>;
  now?: number;
}): RecommendationReplayStep[] => {
  const sorted = [...input.events].sort((a, b) => a.timestamp - b.timestamp || a.id.localeCompare(b.id));
  const steps: RecommendationReplayStep[] = [];

  sorted.forEach((event, index) => {
    if (event.type !== 'play_started') return;
    const prior = sorted.slice(0, index);
    const now = input.now ?? event.timestamp;
    const taste = deriveTasteSnapshot(prior, {
      now,
      currentSessionId: event.sessionId,
      trackDnaByKey: input.trackDnaByKey,
    });
    const recommendations = generateRecommendations({
      taste,
      graph: input.graph,
      trackDnaByKey: input.trackDnaByKey,
      context: buildRecommendationContextFromEvents(prior, {
        now,
        mode: 'continue-listening',
        sessionId: event.sessionId,
        currentTrack: {
          id: event.trackId,
          title: event.trackId,
          artist: event.artistName || 'Unknown Artist',
          album: 'YouTube Music',
          albumArt: '',
          duration: event.durationSeconds || 0,
          provider: event.provider,
          providerId: event.providerTrackId,
        },
        limit: 10,
      }),
    });
    steps.push({
      at: event.timestamp,
      playEventId: event.id,
      trackKey: `${event.provider}:${event.providerTrackId}`,
      eventCount: prior.length,
      taste,
      recommendations,
    });
  });

  return steps;
};
