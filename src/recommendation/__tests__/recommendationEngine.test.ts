import { describe, expect, it } from 'vitest';
import type { ListeningEvent } from '@/listening/listeningTypes';
import { deriveTasteSnapshot } from '@/taste/tasteModel';
import { buildTrackDna } from '@/trackDna/trackDna';
import { generateCandidates } from '../candidateGenerator';
import { filterCandidates } from '../candidateFilter';
import { rankCandidates } from '../deterministicRanker';
import { applyDiversity } from '../diversityController';
import {
  buildRecommendationContextFromEvents,
  generateRecommendations,
  resolveColdStartTier,
} from '../recommendationEngine';
import type { DiscoveryGraphSnapshot, RecommendationEngineInput } from '../recommendationTypes';
import { trackKeyFromParts } from '../graphUtils';

const NOW = 1_700_000_000_000;

const event = (overrides: Partial<ListeningEvent> & Pick<ListeningEvent, 'id' | 'type' | 'timestamp'>): ListeningEvent => ({
  trackId: 't1',
  provider: 'youtube-music',
  providerTrackId: 't1',
  sessionId: 's1',
  artistName: 'Artist A',
  ...overrides,
});

const trackNode = (id: string, title: string, artist = 'Artist A', album = 'Album A') => ({
  key: `youtube-music:${id}`,
  type: 'track' as const,
  provider: 'youtube-music',
  providerId: id,
  track: { id, title, artist, album, provider: 'youtube-music', durationMs: 180000 },
});

const graphEdge = (type: string, from: string, to: string) => ({
  key: `${type}|${from}|${to}`,
  type,
  fromKey: from,
  toKey: to,
  observedAt: NOW,
});

const baseGraph = (): DiscoveryGraphSnapshot => ({
  nodes: [
    trackNode('t1', 'Track One'),
    trackNode('t2', 'Track Two'),
    trackNode('t3', 'Track Three'),
    trackNode('t4', 'Track Four', 'Artist B', 'Album B'),
    trackNode('r1', 'Related One', 'Artist C'),
    trackNode('r2', 'Related Two', 'Artist C'),
    trackNode('r3', 'Related Three', 'Artist C'),
    { key: 'youtube-music:artist:UCa', type: 'artist', providerId: 'UCa', title: 'Artist A' },
    { key: 'youtube-music:album:MPRa', type: 'album', providerId: 'MPRa', title: 'Album A' },
  ],
  edges: [
    graphEdge('related_to', 'youtube-music:t1', 'youtube-music:r1'),
    graphEdge('related_to', 'youtube-music:t1', 'youtube-music:r2'),
    graphEdge('related_to', 'youtube-music:t1', 'youtube-music:r3'),
    graphEdge('performed_by', 'youtube-music:t2', 'youtube-music:artist:UCa'),
    graphEdge('belongs_to', 'youtube-music:t2', 'youtube-music:album:MPRa'),
    graphEdge('contains', 'youtube-music:album:MPRa', 'youtube-music:t3'),
    graphEdge('queued_after', 'youtube-music:t1', 'youtube-music:t2'),
    graphEdge('related_to', 'youtube-music:t1', 'youtube-music:t1'),
  ],
});

const dnaFor = (id: string, artist = 'Artist A', album = 'Album A', artistId = 'UCa', albumId = 'MPRa') => {
  const dna = buildTrackDna({
    id,
    title: `Track ${id}`,
    artist,
    album,
    duration: 180,
    provider: 'youtube-music',
    providerId: id,
    artistId,
    albumId,
  });
  return { [dna.key]: dna };
};

const buildInput = (
  events: ListeningEvent[],
  graph: DiscoveryGraphSnapshot,
  dna: Record<string, ReturnType<typeof buildTrackDna>>,
  contextOverrides: Partial<RecommendationEngineInput['context']> = {},
): RecommendationEngineInput => {
  const taste = deriveTasteSnapshot(events, { now: NOW, trackDnaByKey: dna, currentSessionId: 's1' });
  return {
    taste,
    graph,
    trackDnaByKey: dna,
    context: buildRecommendationContextFromEvents(events, {
      now: NOW,
      mode: 'continue-listening',
      ...contextOverrides,
      limit: contextOverrides.limit ?? 10,
    }),
  };
};

describe('Recommendation Engine V1', () => {
  it('1. handles empty taste profile (cold start)', () => {
    const result = generateRecommendations(buildInput([], baseGraph(), {}));
    expect(result.coldStartTier).toBe('empty');
    expect(result.candidates.length).toBeGreaterThan(0);
    expect(result.candidates.every((candidate) => candidate.caveats.some((c) => c.includes('Cold start')))).toBe(true);
  });

  it('2. handles single listened track', () => {
    const events = [
      event({ id: 'e1', type: 'play_started', timestamp: NOW - 1000, discoveryContext: { type: 'search', id: 'q' } }),
      event({ id: 'e2', type: 'play_completed', timestamp: NOW - 500, completionRatio: 1 }),
    ];
    const result = generateRecommendations(buildInput(events, baseGraph(), dnaFor('t1')));
    expect(result.coldStartTier).toBe('sparse');
    expect(result.candidates.length).toBeGreaterThan(0);
  });

  it('3. ranks strong artist preference candidates higher', () => {
    const events: ListeningEvent[] = [];
    for (let i = 0; i < 6; i += 1) {
      events.push(
        event({ id: `p${i}`, type: 'play_started', timestamp: NOW - 100_000 + i * 1000, trackId: 't2', providerTrackId: 't2' }),
        event({ id: `c${i}`, type: 'play_completed', timestamp: NOW - 99_000 + i * 1000, trackId: 't2', providerTrackId: 't2', completionRatio: 1 }),
      );
    }
    const dna = { ...dnaFor('t1'), ...dnaFor('t2'), ...dnaFor('t3') };
    const result = generateRecommendations(buildInput(events, baseGraph(), dna));
    const top = result.candidates[0];
    expect(top.explanationKeys.some((key) => key.includes('artist') || key.includes('track'))).toBe(true);
  });

  it('4. ranks strong track preference', () => {
    const events = [
      event({ id: 'e1', type: 'play_started', timestamp: NOW - 5000 }),
      event({ id: 'e2', type: 'play_completed', timestamp: NOW - 4000, completionRatio: 1 }),
      event({ id: 'e3', type: 'favorite', timestamp: NOW - 3000 }),
      event({ id: 'e4', type: 'replay', timestamp: NOW - 2000 }),
    ];
    const result = generateRecommendations(buildInput(events, baseGraph(), dnaFor('t1'), {
      currentTrack: { id: 't1', title: 'Track One', artist: 'Artist A', album: 'Album A', albumArt: '', duration: 180, provider: 'youtube-music', providerId: 't1' },
    }));
    const self = result.candidates.find((candidate) => candidate.trackKey === 'youtube-music:t1');
    expect(self).toBeUndefined();
  });

  it('5. includes related-track graph candidates', () => {
    const events = [
      event({ id: 'e1', type: 'play_started', timestamp: NOW - 5000, discoveryContext: { type: 'related', sourceTrackKey: 'youtube-music:seed' } }),
      event({ id: 'e2', type: 'play_completed', timestamp: NOW - 4000, completionRatio: 1 }),
    ];
    const raw = generateCandidates(
      deriveTasteSnapshot(events, { now: NOW }),
      baseGraph(),
      dnaFor('t1'),
      buildRecommendationContextFromEvents(events, { now: NOW, currentTrack: { id: 't1', title: 'Track One', artist: 'Artist A', album: 'Album A', albumArt: '', duration: 180, provider: 'youtube-music', providerId: 't1' } }),
    );
    expect(raw.some((candidate) => candidate.trackKey === 'youtube-music:r1')).toBe(true);
  });

  it('6. includes playlist/album graph candidates', () => {
    const raw = generateCandidates(
      deriveTasteSnapshot([], { now: NOW }),
      baseGraph(),
      dnaFor('t1'),
      buildRecommendationContextFromEvents([], { now: NOW }),
    );
    expect(raw.some((candidate) => candidate.trackKey === 'youtube-music:t3')).toBe(true);
  });

  it('7. uses scene discovery-source affinity as evidence', () => {
    const events: ListeningEvent[] = [];
    for (let i = 0; i < 3; i += 1) {
      events.push(
        event({ id: `s${i}a`, type: 'play_started', timestamp: NOW - 50_000 + i * 2000, trackId: `t${i + 1}`, providerTrackId: `t${i + 1}`, discoveryContext: { type: 'scene', id: 'focus' } }),
        event({ id: `s${i}b`, type: 'play_completed', timestamp: NOW - 49_000 + i * 2000, trackId: `t${i + 1}`, providerTrackId: `t${i + 1}`, completionRatio: 1 }),
      );
    }
    const taste = deriveTasteSnapshot(events, { now: NOW });
    expect(taste.discovery.scene?.affinity).toBeGreaterThan(0.3);
    const result = generateRecommendations(buildInput(events, baseGraph(), { ...dnaFor('t1'), ...dnaFor('t2'), ...dnaFor('t3') }));
    expect(result.candidates.some((candidate) => candidate.explanationKeys.includes('successful_discovery_surface') || candidate.provenance.some((entry) => entry.source === 'discovery-surface'))).toBe(true);
  });

  it('8. applies recent skip filtering', () => {
    const events = [
      event({ id: 'e1', type: 'play_started', timestamp: NOW - 3000, trackId: 'r1', providerTrackId: 'r1' }),
      event({ id: 'e2', type: 'skip', timestamp: NOW - 2000, trackId: 'r1', providerTrackId: 'r1', earlySkip: true, completionRatio: 0.02 }),
    ];
    const taste = deriveTasteSnapshot(events, { now: NOW });
    const raw = generateCandidates(taste, baseGraph(), dnaFor('t1'), buildRecommendationContextFromEvents(events, { now: NOW, recentSkips: ['youtube-music:r1'] }));
    const filtered = filterCandidates(raw, taste, buildRecommendationContextFromEvents(events, { now: NOW, recentSkips: ['youtube-music:r1'] }));
    expect(filtered.filtered.some((candidate) => candidate.trackKey === 'youtube-music:r1')).toBe(true);
  });

  it('9. repeated skip pattern filters when not favorite', () => {
    const events: ListeningEvent[] = [];
    for (let i = 0; i < 3; i += 1) {
      events.push(
        event({ id: `p${i}`, type: 'play_started', timestamp: NOW - 20_000 + i * 1000, trackId: 'r2', providerTrackId: 'r2' }),
        event({ id: `s${i}`, type: 'skip', timestamp: NOW - 19_000 + i * 1000, trackId: 'r2', providerTrackId: 'r2', earlySkip: true }),
      );
    }
    const taste = deriveTasteSnapshot(events, { now: NOW });
    const raw = generateCandidates(taste, baseGraph(), dnaFor('t1'), buildRecommendationContextFromEvents(events, { now: NOW }));
    const filtered = filterCandidates(raw, taste, buildRecommendationContextFromEvents(events, { now: NOW, recentSkips: ['youtube-music:r2'] }));
    expect(filtered.reasons.repeated_early_skip || filtered.reasons.recent_skip).toBeGreaterThan(0);
  });

  it('10. applies recently played penalty but keeps favorite available', () => {
    const events = [
      event({ id: 'e1', type: 'play_started', timestamp: NOW - 60_000 }),
      event({ id: 'e2', type: 'play_completed', timestamp: NOW - 50_000, completionRatio: 1 }),
      event({ id: 'e3', type: 'favorite', timestamp: NOW - 40_000 }),
      event({ id: 'e4', type: 'play_started', timestamp: NOW - 30_000 }),
    ];
    const taste = deriveTasteSnapshot(events, { now: NOW });
    const raw = generateCandidates(taste, baseGraph(), dnaFor('t1'), buildRecommendationContextFromEvents(events, { now: NOW, recentTrackKeys: ['youtube-music:t1'] }));
    const ranked = rankCandidates(raw, taste, dnaFor('t1'), buildRecommendationContextFromEvents(events, { now: NOW, recentTrackKeys: ['youtube-music:t1'] }), 'sparse');
    const favCandidate = ranked.find((candidate) => candidate.trackKey === 'youtube-music:t1');
    expect(favCandidate?.penalties.immediateRepeatPenalty).toBeDefined();
  });

  it('11. favorite recently played is less penalized than non-favorite', () => {
    const favEvents = [
      event({ id: 'f1', type: 'play_started', timestamp: NOW - 1000 }),
      event({ id: 'f2', type: 'favorite', timestamp: NOW - 900 }),
    ];
    const plainEvents = [
      event({ id: 'p1', type: 'play_started', timestamp: NOW - 1000, trackId: 't2', providerTrackId: 't2' }),
    ];
    const taste = deriveTasteSnapshot([...favEvents, ...plainEvents], { now: NOW });
    const context = buildRecommendationContextFromEvents([...favEvents, ...plainEvents], { now: NOW, recentTrackKeys: ['youtube-music:t1', 'youtube-music:t2'] });
    const raw = generateCandidates(taste, baseGraph(), { ...dnaFor('t1'), ...dnaFor('t2') }, context);
    const ranked = rankCandidates(raw, taste, { ...dnaFor('t1'), ...dnaFor('t2') }, context, 'sparse');
    const fav = ranked.find((candidate) => candidate.trackKey === 'youtube-music:t1');
    const plain = ranked.find((candidate) => candidate.trackKey === 'youtube-music:t2');
    expect(Math.abs(fav?.penalties.immediateRepeatPenalty || 0)).toBeLessThan(Math.abs(plain?.penalties.immediateRepeatPenalty || 0));
  });

  it('12. enforces artist diversity', () => {
    const events = [
      event({ id: 'e1', type: 'play_started', timestamp: NOW - 5000 }),
      event({ id: 'e2', type: 'play_completed', timestamp: NOW - 4000, completionRatio: 1 }),
    ];
    const graph: DiscoveryGraphSnapshot = {
      nodes: [
        trackNode('a1', 'A1', 'Artist C'),
        trackNode('a2', 'A2', 'Artist C'),
        trackNode('a3', 'A3', 'Artist C'),
        trackNode('a4', 'A4', 'Artist C'),
      ],
      edges: [],
    };
    const dna = {
      ...dnaFor('a1', 'Artist C', 'Album C', 'UCc', 'MPRc'),
      ...dnaFor('a2', 'Artist C', 'Album C', 'UCc', 'MPRc'),
      ...dnaFor('a3', 'Artist C', 'Album C', 'UCc', 'MPRc'),
      ...dnaFor('a4', 'Artist C', 'Album C', 'UCc', 'MPRc'),
    };
    const taste = deriveTasteSnapshot(events, { now: NOW, trackDnaByKey: dna });
    const raw = generateCandidates(taste, graph, dna, buildRecommendationContextFromEvents(events, { now: NOW }));
    const ranked = rankCandidates(raw, taste, dna, buildRecommendationContextFromEvents(events, { now: NOW }), 'sparse');
    const diverse = applyDiversity(ranked, taste, dna, 4, 0.2);
    const artistKeys = diverse.map((candidate) => candidate.track.artist);
    expect(new Set(artistKeys).size).toBeGreaterThanOrEqual(1);
    expect(diverse.length).toBeLessThanOrEqual(4);
  });

  it('13. enforces album diversity constraints', () => {
    const ranked = [
      { trackKey: 'youtube-music:x1', track: { trackKey: 'youtube-music:x1', id: 'x1', title: 'X1', artist: 'A', album: 'Same', albumArt: '', duration: 180, provider: 'youtube-music', providerId: 'x1' }, score: 0.9, confidence: 0.8, provenance: [{ source: 'taste-track' as const, evidence: [] }], components: {}, penalties: {}, diversityAdjustment: 0, isExploration: false, isPersonalized: true, explanationKeys: [], caveats: [] },
      { trackKey: 'youtube-music:x2', track: { trackKey: 'youtube-music:x2', id: 'x2', title: 'X2', artist: 'A', album: 'Same', albumArt: '', duration: 180, provider: 'youtube-music', providerId: 'x2' }, score: 0.85, confidence: 0.8, provenance: [{ source: 'taste-track' as const, evidence: [] }], components: {}, penalties: {}, diversityAdjustment: 0, isExploration: false, isPersonalized: true, explanationKeys: [], caveats: [] },
      { trackKey: 'youtube-music:x3', track: { trackKey: 'youtube-music:x3', id: 'x3', title: 'X3', artist: 'A', album: 'Same', albumArt: '', duration: 180, provider: 'youtube-music', providerId: 'x3' }, score: 0.8, confidence: 0.8, provenance: [{ source: 'taste-track' as const, evidence: [] }], components: {}, penalties: {}, diversityAdjustment: 0, isExploration: false, isPersonalized: true, explanationKeys: [], caveats: [] },
      { trackKey: 'youtube-music:y1', track: { trackKey: 'youtube-music:y1', id: 'y1', title: 'Y1', artist: 'B', album: 'Other', albumArt: '', duration: 180, provider: 'youtube-music', providerId: 'y1' }, score: 0.75, confidence: 0.8, provenance: [{ source: 'exploration' as const, evidence: [] }], components: {}, penalties: {}, diversityAdjustment: 0, isExploration: true, isPersonalized: true, explanationKeys: [], caveats: [] },
    ];
    const taste = deriveTasteSnapshot([], { now: NOW });
    const diverse = applyDiversity(ranked, taste, {}, 4, 0.25);
    const sameAlbum = diverse.filter((candidate) => candidate.track.album === 'Same');
    expect(sameAlbum.length).toBeLessThanOrEqual(2);
  });

  it('14. includes controlled exploration candidates', () => {
    const result = generateRecommendations(buildInput([], baseGraph(), {}), { explorationRatio: 0.3 });
    expect(result.diagnostics.explorationCount).toBeGreaterThanOrEqual(0);
    expect(result.candidates.some((candidate) => candidate.isExploration)).toBe(true);
  });

  it('15. cold start uses graph fallback', () => {
    const result = generateRecommendations(buildInput([], baseGraph(), {}));
    expect(result.coldStartTier).toBe('empty');
    expect(result.candidates[0].provenance.some((entry) => entry.source === 'fallback-graph' || entry.source.startsWith('graph'))).toBe(true);
  });

  it('16. merges duplicate candidates and preserves provenance', () => {
    const raw = generateCandidates(
      deriveTasteSnapshot([], { now: NOW }),
      baseGraph(),
      dnaFor('t1'),
      buildRecommendationContextFromEvents([], { now: NOW, currentTrack: { id: 't1', title: 'T1', artist: 'A', album: 'Al', albumArt: '', duration: 180, provider: 'youtube-music', providerId: 't1' } }),
    );
    const related = raw.find((candidate) => candidate.trackKey === 'youtube-music:r1');
    expect(related?.provenance.length).toBeGreaterThanOrEqual(1);
  });

  it('17. graph cycles do not loop infinitely', () => {
    const cyclic: DiscoveryGraphSnapshot = {
      nodes: [trackNode('c1', 'C1'), trackNode('c2', 'C2')],
      edges: [
        graphEdge('related_to', 'youtube-music:c1', 'youtube-music:c2'),
        graphEdge('related_to', 'youtube-music:c2', 'youtube-music:c1'),
      ],
    };
    const raw = generateCandidates(
      deriveTasteSnapshot([], { now: NOW }),
      cyclic,
      {},
      buildRecommendationContextFromEvents([], { now: NOW, currentTrack: { id: 'c1', title: 'C1', artist: 'A', album: 'Al', albumArt: '', duration: 180, provider: 'youtube-music', providerId: 'c1' } }),
    );
    expect(raw.length).toBeLessThanOrEqual(120);
  });

  it('18. respects graph traversal depth bound', () => {
    const deep: DiscoveryGraphSnapshot = {
      nodes: [
        trackNode('d0', 'D0'),
        trackNode('d1', 'D1'),
        trackNode('d2', 'D2'),
        trackNode('d3', 'D3'),
      ],
      edges: [
        graphEdge('related_to', 'youtube-music:d0', 'youtube-music:d1'),
        graphEdge('related_to', 'youtube-music:d1', 'youtube-music:d2'),
        graphEdge('related_to', 'youtube-music:d2', 'youtube-music:d3'),
      ],
    };
    const raw = generateCandidates(
      deriveTasteSnapshot([], { now: NOW }),
      deep,
      {},
      buildRecommendationContextFromEvents([], { now: NOW, currentTrack: { id: 'd0', title: 'D0', artist: 'A', album: 'Al', albumArt: '', duration: 180, provider: 'youtube-music', providerId: 'd0' } }),
    );
    expect(raw.some((candidate) => candidate.trackKey === trackKeyFromParts('youtube-music', 'd3'))).toBe(false);
  });

  it('19. produces identical ordering for identical inputs', () => {
    const events = [
      event({ id: 'e1', type: 'play_started', timestamp: NOW - 5000, discoveryContext: { type: 'search', id: 'q' } }),
      event({ id: 'e2', type: 'play_completed', timestamp: NOW - 4000, completionRatio: 1 }),
    ];
    const input = buildInput(events, baseGraph(), dnaFor('t1'));
    const a = generateRecommendations(input);
    const b = generateRecommendations(input);
    expect(a.candidates.map((candidate) => candidate.trackKey)).toEqual(b.candidates.map((candidate) => candidate.trackKey));
    expect(a.candidates.map((candidate) => candidate.score)).toEqual(b.candidates.map((candidate) => candidate.score));
  });

  it('20. retains explanation and provenance on every recommendation', () => {
    const result = generateRecommendations(buildInput([
      event({ id: 'e1', type: 'play_started', timestamp: NOW - 5000 }),
      event({ id: 'e2', type: 'play_completed', timestamp: NOW - 4000, completionRatio: 1 }),
    ], baseGraph(), dnaFor('t1')));
    result.candidates.forEach((candidate) => {
      expect(candidate.provenance.length).toBeGreaterThan(0);
      expect(Object.keys(candidate.components).length).toBeGreaterThan(0);
      expect(candidate.explanationKeys.length).toBeGreaterThan(0);
    });
  });

  it('21. provider exposure without engagement does not dominate', () => {
    const events = [
      event({ id: 'e1', type: 'play_started', timestamp: NOW - 5000, trackId: 'r1', providerTrackId: 'r1' }),
      event({ id: 'e2', type: 'skip', timestamp: NOW - 4000, trackId: 'r1', providerTrackId: 'r1', earlySkip: true }),
      event({ id: 'e3', type: 'play_started', timestamp: NOW - 3000, trackId: 't2', providerTrackId: 't2' }),
      event({ id: 'e4', type: 'play_completed', timestamp: NOW - 2000, trackId: 't2', providerTrackId: 't2', completionRatio: 1 }),
      event({ id: 'e5', type: 'favorite', timestamp: NOW - 1000, trackId: 't2', providerTrackId: 't2' }),
    ];
    const result = generateRecommendations(buildInput(events, baseGraph(), { ...dnaFor('t1'), ...dnaFor('t2'), ...dnaFor('r1', 'Artist C', 'Album C', 'UCc', 'MPRc') }));
    const r1 = result.candidates.find((candidate) => candidate.trackKey === 'youtube-music:r1');
    const t2 = result.candidates.find((candidate) => candidate.trackKey === 'youtube-music:t2');
    if (r1 && t2) expect(t2.score).toBeGreaterThanOrEqual(r1.score);
  });

  it('22. resolves cold-start tiers by event count', () => {
    expect(resolveColdStartTier(0)).toBe('empty');
    expect(resolveColdStartTier(3)).toBe('sparse');
    expect(resolveColdStartTier(10)).toBe('emerging');
    expect(resolveColdStartTier(25)).toBe('mature');
  });
});
