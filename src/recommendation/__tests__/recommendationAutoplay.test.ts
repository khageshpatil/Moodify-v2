import { describe, expect, it } from 'vitest';
import type { ListeningEvent } from '@/listening/listeningTypes';
import { normalizeDiscoveryContext } from '@/listening/listeningTypes';
import { deriveTasteSnapshot } from '@/taste/tasteModel';
import { buildTrackDna } from '@/trackDna/trackDna';
import { filterCandidates } from '../candidateFilter';
import { generateRecommendations } from '../recommendationEngine';
import { buildRecommendationContextFromEvents } from '../recommendationEngine';
import {
  applyAutoplayInjection,
  createRecommendationBatch,
  markBatchConsumed,
  markBatchFailed,
  markBatchInjected,
  remainingQueue,
  selectInjectableRecommendations,
  shouldInjectRecommendations,
  shouldPrefetchRecommendations,
  shouldReplaceBatch,
  stampRecommendationContexts,
  userQueueSignature,
} from '../recommendationAutoplay';
import { deriveRecommendationOutcomes } from '../recommendationEval';
import { replayRecommendationHistory } from '../recommendationReplay';
import type { DiscoveryGraphSnapshot, RankedCandidate } from '../recommendationTypes';

const NOW = 1_700_000_000_000;

const track = (id: string, artist = 'Artist A'): RankedCandidate['track'] => ({
  trackKey: `youtube-music:${id}`,
  id,
  title: `Title ${id}`,
  artist,
  album: 'Album',
  albumArt: '',
  duration: 180,
  provider: 'youtube-music',
  providerId: id,
});

const candidate = (id: string, extras: Partial<RankedCandidate> = {}): RankedCandidate => ({
  trackKey: `youtube-music:${id}`,
  track: track(id),
  score: 0.7,
  confidence: 0.5,
  provenance: [{ source: 'taste-track', evidence: ['taste'] }],
  components: { tasteAffinity: 0.2 },
  penalties: {},
  diversityAdjustment: 0,
  isExploration: false,
  isPersonalized: true,
  explanationKeys: ['strong_track_affinity'],
  caveats: [],
  ...extras,
});

const event = (overrides: Partial<ListeningEvent> & Pick<ListeningEvent, 'id' | 'type' | 'timestamp'>): ListeningEvent => ({
  trackId: 't1',
  provider: 'youtube-music',
  providerTrackId: 't1',
  sessionId: 's1',
  artistName: 'Artist A',
  ...overrides,
});

const graph: DiscoveryGraphSnapshot = {
  nodes: [
    { key: 'youtube-music:r1', type: 'track', provider: 'youtube-music', providerId: 'r1', track: { id: 'r1', title: 'Related', artist: 'B', provider: 'youtube-music' } },
  ],
  edges: [{ key: 'related_to|youtube-music:t1|youtube-music:r1', type: 'related_to', fromKey: 'youtube-music:t1', toKey: 'youtube-music:r1' }],
};

describe('recommendation autoplay integration', () => {
  it('1. queue has enough tracks → no recommendation injection', () => {
    expect(remainingQueue(0, 5)).toBe(4);
    expect(shouldPrefetchRecommendations(4, 'off')).toBe(false);
    expect(shouldInjectRecommendations(4, 'off')).toBe(false);
  });

  it('2. queue approaches exhaustion → recommendation prefetch', () => {
    expect(remainingQueue(3, 5)).toBe(1);
    expect(shouldPrefetchRecommendations(1, 'off')).toBe(true);
    expect(shouldInjectRecommendations(1, 'off')).toBe(false);
  });

  it('3. queue exhausted → recommendation injection', () => {
    expect(remainingQueue(4, 5)).toBe(0);
    expect(shouldInjectRecommendations(0, 'off')).toBe(true);
    const batch = createRecommendationBatch([candidate('r1'), candidate('r2')], 'continue-listening', 'sig', NOW);
    const selected = selectInjectableRecommendations(batch, new Set(['youtube-music:t1']));
    const stamped = stampRecommendationContexts(batch, selected, 'youtube-music:t1');
    const applied = applyAutoplayInjection([track('t1')], 0, stamped);
    expect(applied.queue.map((item) => item.id)).toEqual(['t1', 'r1', 'r2']);
    expect(applied.nextTrack?.id).toBe('r1');
  });

  it('4. manual queue is preserved', () => {
    const batch = createRecommendationBatch([candidate('r1')], 'continue-listening', 'user-a|user-b', NOW);
    const applied = applyAutoplayInjection([track('user-a'), track('user-b')], 1, stampRecommendationContexts(batch, batch.candidates));
    expect(applied.queue[0].id).toBe('user-a');
    expect(applied.queue[1].id).toBe('user-b');
    expect(applied.queue[2].id).toBe('r1');
  });

  it('5. repeat-one is preserved', () => {
    expect(shouldPrefetchRecommendations(0, 'one')).toBe(false);
    expect(shouldInjectRecommendations(0, 'one')).toBe(false);
  });

  it('6. repeat-all is preserved', () => {
    expect(shouldPrefetchRecommendations(0, 'all')).toBe(false);
    expect(shouldInjectRecommendations(0, 'all')).toBe(false);
  });

  it('7. shuffle is preserved (user signature unchanged)', () => {
    const shuffled = [track('b'), track('a')];
    expect(userQueueSignature(shuffled)).toBe('youtube-music:b|youtube-music:a');
    expect(shouldInjectRecommendations(2, 'off')).toBe(false);
  });

  it('8. duplicate recommendation is filtered', () => {
    const batch = createRecommendationBatch([candidate('r1'), candidate('r2')], 'continue-listening', 'sig', NOW);
    markBatchInjected(batch, ['youtube-music:r1']);
    const selected = selectInjectableRecommendations(batch, new Set(['youtube-music:r1']));
    expect(selected.map((item) => item.trackKey)).toEqual(['youtube-music:r2']);
  });

  it('9. recently skipped candidate is filtered by the engine', () => {
    const taste = deriveTasteSnapshot([
      event({ id: 'e1', type: 'play_started', timestamp: NOW - 3000, trackId: 'r1', providerTrackId: 'r1' }),
      event({ id: 'e2', type: 'skip', timestamp: NOW - 2000, trackId: 'r1', providerTrackId: 'r1', earlySkip: true }),
    ], { now: NOW });
    const result = filterCandidates(
      [{ trackKey: 'youtube-music:r1', track: track('r1'), provenance: [{ source: 'graph-related', evidence: [] }], isExploration: false, graphStrength: 0.4 }],
      taste,
      buildRecommendationContextFromEvents([], { now: NOW, recentSkips: ['youtube-music:r1'], queueTrackKeys: [] }),
    );
    expect(result.filtered.some((item) => item.trackKey === 'youtube-music:r1')).toBe(true);
  });

  it('10. failed recommendation is skipped', () => {
    const batch = createRecommendationBatch([candidate('bad'), candidate('ok')], 'continue-listening', 'sig', NOW);
    markBatchFailed(batch, 'youtube-music:bad');
    expect(selectInjectableRecommendations(batch, new Set()).map((item) => item.trackKey)).toEqual(['youtube-music:ok']);
  });

  it('11. multiple failed candidates followed by a playable candidate', () => {
    const batch = createRecommendationBatch([candidate('a'), candidate('b'), candidate('c')], 'continue-listening', 'sig', NOW);
    markBatchFailed(batch, 'youtube-music:a');
    markBatchFailed(batch, 'youtube-music:b');
    expect(selectInjectableRecommendations(batch, new Set(), 1).map((item) => item.trackKey)).toEqual(['youtube-music:c']);
  });

  it('12. recommendation provenance is preserved', () => {
    const batch = createRecommendationBatch([candidate('r1')], 'continue-listening', 'sig', NOW);
    const stamped = stampRecommendationContexts(batch, batch.candidates, 'youtube-music:t1');
    expect(stamped[0].discoveryContext.type).toBe('recommendation');
    expect(stamped[0].discoveryContext.sources).toContain('taste-track');
    expect(stamped[0].discoveryContext.strategy).toBe('continue-listening');
  });

  it('13. recommendation play generates correct discovery context', () => {
    const context = stampRecommendationContexts(
      createRecommendationBatch([candidate('r1')], 'continue-listening', 'sig', NOW),
      [candidate('r1')],
    )[0].discoveryContext;
    expect(normalizeDiscoveryContext(context)).toEqual(context);
    expect(context.type).toBe('recommendation');
  });

  it('14–16. recommendation completion, skip, and favorite are recorded from listening events', () => {
    const events: ListeningEvent[] = [
      event({ id: 'p', type: 'play_started', timestamp: NOW - 3000, trackId: 'r1', providerTrackId: 'r1', discoveryContext: { type: 'recommendation', id: 'rec-1', strategy: 'continue-listening', sources: ['taste-track'] } }),
      event({ id: 'c', type: 'play_completed', timestamp: NOW - 2000, trackId: 'r1', providerTrackId: 'r1', discoveryContext: { type: 'recommendation', id: 'rec-1' } }),
      event({ id: 's', type: 'skip', timestamp: NOW - 1500, trackId: 'r2', providerTrackId: 'r2', discoveryContext: { type: 'recommendation', id: 'rec-1' } }),
      event({ id: 'f', type: 'favorite', timestamp: NOW - 1000, trackId: 'r1', providerTrackId: 'r1', discoveryContext: { type: 'recommendation', id: 'rec-1' } }),
    ];
    const outcomes = deriveRecommendationOutcomes(events);
    expect(outcomes.map((item) => item.type)).toEqual(['played', 'completed', 'skipped', 'favorited']);
  });

  it('17. recommendation batch remains stable', () => {
    const first = createRecommendationBatch([candidate('r1'), candidate('r2')], 'continue-listening', 'sig', NOW);
    expect(shouldReplaceBatch(first, 'sig')).toBe(false);
    expect(first.candidates.map((item) => item.trackKey)).toEqual(['youtube-music:r1', 'youtube-music:r2']);
  });

  it('18. new batch is generated after consumption', () => {
    const batch = createRecommendationBatch([candidate('r1')], 'continue-listening', 'sig', NOW);
    markBatchInjected(batch, ['youtube-music:r1']);
    markBatchConsumed(batch, 'youtube-music:r1');
    expect(shouldReplaceBatch(batch, 'sig')).toBe(true);
  });

  it('19. empty recommendation result is handled gracefully', () => {
    const batch = createRecommendationBatch([], 'continue-listening', 'sig', NOW);
    expect(selectInjectableRecommendations(batch, new Set())).toEqual([]);
    expect(shouldInjectRecommendations(0, 'off')).toBe(true);
  });

  it('20. cold-start fallback still produces candidates for autoplay', () => {
    const result = generateRecommendations({
      taste: deriveTasteSnapshot([], { now: NOW }),
      graph,
      trackDnaByKey: {},
      context: buildRecommendationContextFromEvents([], { now: NOW, mode: 'continue-listening', limit: 10 }),
    });
    expect(result.coldStartTier).toBe('empty');
    expect(result.candidates.length).toBeGreaterThan(0);
    const batch = createRecommendationBatch(result.candidates, result.strategy, 'empty', result.generatedAt);
    expect(selectInjectableRecommendations(batch, new Set()).length).toBeGreaterThan(0);
  });

  it('invalid identity is not playable and is marked failed', () => {
    const broken = candidate('x', { track: { ...track('x'), title: '', providerId: '' } });
    const batch = createRecommendationBatch([broken, candidate('ok')], 'continue-listening', 'sig', NOW);
    const selected = selectInjectableRecommendations(batch, new Set());
    expect(selected.map((item) => item.trackKey)).toEqual(['youtube-music:ok']);
    expect(batch.failedKeys).toContain('youtube-music:x');
  });

  it('replay harness produces deterministic recommendation steps', () => {
    const dna = buildTrackDna({ id: 't1', title: 'Track t1', artist: 'Artist A', album: 'Album', duration: 180, provider: 'youtube-music', providerId: 't1' });
    const events = [
      event({ id: 'e1', type: 'play_started', timestamp: NOW - 5000 }),
      event({ id: 'e2', type: 'play_completed', timestamp: NOW - 4000, completionRatio: 1 }),
      event({ id: 'e3', type: 'play_started', timestamp: NOW - 3000, trackId: 't2', providerTrackId: 't2' }),
    ];
    const first = replayRecommendationHistory({ events, graph, trackDnaByKey: { [dna.key]: dna }, now: NOW });
    const second = replayRecommendationHistory({ events, graph, trackDnaByKey: { [dna.key]: dna }, now: NOW });
    expect(first.map((step) => step.recommendations.candidates.map((item) => item.trackKey))).toEqual(
      second.map((step) => step.recommendations.candidates.map((item) => item.trackKey)),
    );
    expect(first.length).toBe(2);
  });
});
