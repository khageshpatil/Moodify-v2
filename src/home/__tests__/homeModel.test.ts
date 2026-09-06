import { describe, expect, it } from 'vitest';
import type { ListeningEvent } from '@/listening/listeningTypes';
import { deriveTasteSnapshot } from '@/taste/tasteModel';
import { generateRecommendations, buildRecommendationContextFromEvents } from '@/recommendation/recommendationEngine';
import { RECOMMENDATION_ENGINE_VERSION, type DiscoveryGraphSnapshot, type RankedCandidate, type RecommendationResult } from '@/recommendation/recommendationTypes';
import { buildTrackDna } from '@/trackDna/trackDna';
import {
  buildTrackCatalog,
  consumedTrackKeys,
  deriveHomeModel,
  isRotationProfile,
  resolveContinueListening,
  resolveForYou,
  resolveFreshFinds,
  resolveMoodifyMix,
  resolveSectionProminence,
  resolveYourRotation,
} from '../homeModel';
import { HOME_MODEL_VERSION, MOODIFY_MIX_CONTRACT_VERSION, SCENE_CONTRACT_VERSION } from '../homeTypes';

const NOW = 1_700_000_000_000;

const track = (id: string, title = `Track ${id}`, artist = 'Artist A') => ({
  id,
  title,
  artist,
  album: 'Album A',
  albumArt: '',
  duration: 180,
  provider: 'youtube-music',
  providerId: id,
});

const event = (overrides: Partial<ListeningEvent> & Pick<ListeningEvent, 'id' | 'type' | 'timestamp'>): ListeningEvent => ({
  trackId: 't1',
  provider: 'youtube-music',
  providerTrackId: 't1',
  sessionId: 's1',
  artistName: 'Artist A',
  ...overrides,
});

const candidate = (id: string, score: number, extras: Partial<RankedCandidate> = {}): RankedCandidate => ({
  trackKey: `youtube-music:${id}`,
  track: { ...track(id), trackKey: `youtube-music:${id}` },
  score,
  confidence: 0.5,
  provenance: extras.provenance || [{ source: 'taste-track', evidence: ['taste'] }],
  components: {},
  penalties: {},
  diversityAdjustment: 1,
  isExploration: false,
  isPersonalized: true,
  explanationKeys: extras.explanationKeys || ['taste-affinity'],
  caveats: [],
  ...extras,
});

const emptyResult = (tier: RecommendationResult['coldStartTier'] = 'empty', candidates: RankedCandidate[] = []): RecommendationResult => ({
  version: RECOMMENDATION_ENGINE_VERSION,
  generatedAt: NOW,
  context: { now: NOW, mode: 'continue-listening', recentTrackKeys: [], recentSkips: [], recentReplays: [], queueTrackKeys: [] },
  strategy: 'continue-listening',
  coldStartTier: tier,
  candidates,
  diagnostics: {
    coldStartTier: tier,
    rawCandidateCount: candidates.length,
    filteredCount: candidates.length,
    rankedCount: candidates.length,
    finalCount: candidates.length,
    explorationCount: candidates.filter((entry) => entry.isExploration).length,
    personalizedCount: candidates.filter((entry) => entry.isPersonalized).length,
    filteredReasons: {},
  },
});

const emptyLibrary = () => ({ playlists: [], favorites: [], history: [], recentlyPlayed: [] });

describe('Moodify Home Model V1', () => {
  it('hides continue listening and rotation on cold start with no evidence', () => {
    const home = deriveHomeModel({
      now: NOW,
      events: [],
      taste: null,
      recommendations: emptyResult('empty'),
      graph: { nodes: [], edges: [] },
      library: emptyLibrary(),
    });
    const byId = Object.fromEntries(home.sections.map((section) => [section.id, section]));
    expect(byId.continueListening.prominence).toBe('hidden');
    expect(byId.yourRotation.prominence).toBe('hidden');
    expect(byId.freshFinds.prominence).toBe('hidden');
    expect(byId.forYou.prominence).toBe('hidden');
    expect(byId.scenes.prominence).toBe('prominent');
    expect(byId.moodifyMix.prominence).toBe('hidden');
    expect(home.publishedScenes).toEqual([]);
  });

  it('resolves continue listening from the open thread only', () => {
    const catalog = buildTrackCatalog(emptyLibrary(), null, { nodes: [], edges: [] });
    const items = resolveContinueListening([], emptyLibrary(), catalog, {
      track: track('t9'),
      remainder: [track('t8')],
      origin: { type: 'search', id: 'late night' },
      owner: 'user',
      updatedAt: NOW,
    });
    expect(items).toHaveLength(1);
    expect(items[0].source).toBe('open-thread');
    expect(items[0].queue?.map((entry) => entry.id)).toEqual(['t9', 't8']);
    expect(items[0].discoveryContext).toEqual({ type: 'search', id: 'late night' });
    expect(resolveContinueListening([], emptyLibrary(), catalog, null)).toEqual([]);
  });

  it('resolves rotation from return patterns rather than recommendation score', () => {
    const events = [
      event({ id: 'a1', type: 'play_started', timestamp: NOW - 80_000, sessionId: 's1' }),
      event({ id: 'a2', type: 'play_completed', timestamp: NOW - 70_000, sessionId: 's1', completionRatio: 1 }),
      event({ id: 'a3', type: 'favorite', timestamp: NOW - 65_000, sessionId: 's1' }),
      event({ id: 'a4', type: 'replay', timestamp: NOW - 40_000, sessionId: 's2' }),
      event({ id: 'a5', type: 'play_started', timestamp: NOW - 39_000, sessionId: 's2' }),
      event({ id: 'a6', type: 'play_completed', timestamp: NOW - 20_000, sessionId: 's2', completionRatio: 1 }),
    ];
    const taste = deriveTasteSnapshot(events, { now: NOW, currentSessionId: 's2' });
    expect(isRotationProfile(taste.longTerm.tracks['youtube-music:t1'])).toBe(true);
    const catalog = buildTrackCatalog({ ...emptyLibrary(), favorites: [track('t1')] }, null, { nodes: [], edges: [] });
    const rotation = resolveYourRotation(taste, catalog);
    expect(rotation[0].source).toBe('return-pattern');
    expect(rotation[0].explanationKeys).toEqual(expect.arrayContaining(['replayed', 'favorited', 'multi-session']));
  });

  it('maps For You from RecommendationResult without re-ranking', () => {
    const ranked = [candidate('b', 0.2), candidate('a', 0.9)];
    const result = emptyResult('mature', ranked);
    const items = resolveForYou(result);
    expect(items.map((item) => item.track?.id)).toEqual(['b', 'a']);
    expect(items[0].score).toBe(0.2);
    expect(items[0].provenance).toEqual(ranked[0].provenance);
    expect(items[0].explanationKeys).toEqual(['taste-affinity']);
  });

  it('keeps Fresh Finds unconsumed and never random', () => {
    const graph: DiscoveryGraphSnapshot = {
      nodes: [
        { key: 'youtube-music:r1', type: 'track', provider: 'youtube-music', providerId: 'r1', track: { id: 'r1', title: 'Related One', artist: 'C', provider: 'youtube-music' } },
        { key: 'youtube-music:t1', type: 'track', provider: 'youtube-music', providerId: 't1', track: { id: 't1', title: 'Known', artist: 'A', provider: 'youtube-music' } },
      ],
      edges: [],
    };
    const events = [
      event({ id: 'e1', type: 'play_started', timestamp: NOW - 2_000 }),
      event({ id: 'e2', type: 'play_completed', timestamp: NOW - 1_000, completionRatio: 1 }),
    ];
    const recs = emptyResult('sparse', [
      candidate('r1', 0.4, { isExploration: true, provenance: [{ source: 'exploration', evidence: ['unseen'] }], explanationKeys: ['exploration'] }),
      candidate('t1', 0.9, { isExploration: true, provenance: [{ source: 'exploration', evidence: ['seen'] }] }),
    ]);
    const consumed = consumedTrackKeys(events);
    const catalog = buildTrackCatalog(emptyLibrary(), recs, graph);
    const fresh = resolveFreshFinds(recs, graph, consumed, catalog);
    expect(fresh.map((item) => item.track?.id)).not.toContain('t1');
    expect(fresh.some((item) => item.track?.id === 'r1')).toBe(true);
    expect(JSON.stringify(fresh)).toBe(JSON.stringify(resolveFreshFinds(recs, graph, consumed, catalog)));
  });

  it('makes continue listening and rotation prominent once listening is mature', () => {
    expect(resolveSectionProminence('mature', 'continueListening', 2)).toBe('prominent');
    expect(resolveSectionProminence('mature', 'yourRotation', 2)).toBe('prominent');
    expect(resolveSectionProminence('mature', 'forYou', 2)).toBe('hidden');
    expect(resolveSectionProminence('mature', 'freshFinds', 2)).toBe('hidden');
    expect(resolveSectionProminence('mature', 'scenes', 0)).toBe('available');
    expect(resolveSectionProminence('mature', 'moodifyMix', 1)).toBe('prominent');
    expect(resolveSectionProminence('sparse', 'moodifyMix', 1)).toBe('hidden');
  });

  it('defines the Scene contract without publishing a mood catalogue', () => {
    const home = deriveHomeModel({
      now: NOW,
      events: [],
      taste: null,
      recommendations: emptyResult('empty'),
      graph: { nodes: [], edges: [] },
      library: emptyLibrary(),
    });
    expect(home.sceneContractVersion).toBe(SCENE_CONTRACT_VERSION);
    expect(home.publishedScenes).toEqual([]);
    expect(home.sections.find((section) => section.id === 'scenes')?.items).toEqual([]);
  });

  it('defines Moodify Mix as a living session over Recommendation Engine V1', () => {
    const mix = resolveMoodifyMix(emptyResult('emerging', [candidate('x', 0.7)]));
    expect(mix?.version).toBe(MOODIFY_MIX_CONTRACT_VERSION);
    expect(mix?.source).toBe('recommendation-engine-v1');
    expect(mix?.engineVersion).toBe(RECOMMENDATION_ENGINE_VERSION);
    expect(mix?.candidateKeys).toEqual(['youtube-music:x']);
  });

  it('preserves recommendation provenance on Home For You items', () => {
    const provenance = [{ source: 'graph-related' as const, graphEdgeType: 'related_to', evidence: ['edge'] }];
    const items = resolveForYou(emptyResult('emerging', [candidate('r2', 0.55, { provenance, explanationKeys: ['graph-relationship'] })]));
    expect(items[0].provenance).toEqual(provenance);
    expect(items[0].explanationKeys).toEqual(['graph-relationship']);
  });

  it('does not invent Home shown evaluation events', () => {
    const home = deriveHomeModel({
      now: NOW,
      events: [],
      taste: null,
      recommendations: emptyResult('sparse', [candidate('z', 0.1, { isExploration: true, provenance: [{ source: 'exploration', evidence: [] }] })]),
      graph: { nodes: [], edges: [] },
      library: emptyLibrary(),
    });
    expect(JSON.stringify(home)).not.toContain('"shown"');
    expect(home.version).toBe(HOME_MODEL_VERSION);
    expect(home.sections.find((section) => section.id === 'moodifyMix')?.prominence).toBe('hidden');
    expect(home.sections.find((section) => section.id === 'continueListening')?.items).toEqual([]);
  });

  it('is deterministic and does not use random ordering', () => {
    const recs = emptyResult('mature', [candidate('b', 0.4, { isExploration: true, provenance: [{ source: 'exploration', evidence: [] }] }), candidate('a', 0.8)]);
    const input = {
      now: NOW,
      events: [event({ id: 'e1', type: 'play_started', timestamp: NOW - 1_000 })],
      taste: deriveTasteSnapshot([event({ id: 'e1', type: 'play_started', timestamp: NOW - 1_000 })], { now: NOW }),
      recommendations: recs,
      graph: { nodes: [], edges: [] },
      library: { ...emptyLibrary(), recentlyPlayed: [track('t1')] },
    };
    expect(deriveHomeModel(input)).toEqual(deriveHomeModel(input));
  });

  it('does not fabricate genres or moods', () => {
    const home = deriveHomeModel({
      now: NOW,
      events: [],
      taste: null,
      recommendations: emptyResult('empty'),
      graph: { nodes: [], edges: [] },
      library: emptyLibrary(),
    });
    const blob = JSON.stringify(home).toLowerCase();
    expect(blob.includes('genre')).toBe(false);
    expect(blob.includes('chill')).toBe(false);
    expect(blob.includes('happy')).toBe(false);
  });

  it('rebuilds Home from canonical evidence plus Recommendation Engine V1', () => {
    const events = [
      event({ id: 'e1', type: 'play_started', timestamp: NOW - 30_000 }),
      event({ id: 'e2', type: 'play_completed', timestamp: NOW - 20_000, completionRatio: 1 }),
    ];
    const dna = buildTrackDna({ id: 't1', title: 'Track t1', artist: 'Artist A', album: 'Album A', duration: 180, provider: 'youtube-music', providerId: 't1' });
    const graph: DiscoveryGraphSnapshot = {
      nodes: [{ key: 'youtube-music:t1', type: 'track', provider: 'youtube-music', providerId: 't1', track: { id: 't1', title: 'Track t1', artist: 'Artist A', provider: 'youtube-music' } }],
      edges: [],
    };
    const taste = deriveTasteSnapshot(events, { now: NOW, trackDnaByKey: { [dna.key]: dna } });
    const recommendations = generateRecommendations({
      taste,
      graph,
      trackDnaByKey: { [dna.key]: dna },
      context: buildRecommendationContextFromEvents(events, { now: NOW, mode: 'continue-listening', limit: 8 }),
    });
    const home = deriveHomeModel({
      now: NOW,
      events,
      taste,
      recommendations,
      graph,
      library: { ...emptyLibrary(), recentlyPlayed: [track('t1')] },
    });
    const forYou = home.sections.find((section) => section.id === 'forYou')!;
    expect(forYou.visible).toBe(false);
    expect(forYou.items.map((item) => item.track && `${item.track.provider || 'youtube-music'}:${item.track.providerId || item.track.id}`)).toEqual(recommendations.candidates.map((entry) => entry.trackKey));
    expect(home.moodifyMix?.source).toBe('recommendation-engine-v1');
    const mix = home.sections.find((section) => section.id === 'moodifyMix')!;
    expect(mix.items[0]?.kind).toBe('mix');
    expect(mix.items[0].queue?.map((entry) => `${entry.provider || 'youtube-music'}:${entry.providerId || entry.id}`)).toEqual(recommendations.candidates.map((entry) => entry.trackKey));
    expect(mix.items[0].discoveryContext?.type).toBe('recommendation');
    expect(mix.visible).toBe(recommendations.coldStartTier !== 'empty' && recommendations.coldStartTier !== 'sparse');
  });

  it('surfaces continue from the open thread and Mix from recommendation candidates', () => {
    const recs = emptyResult('mature', [candidate('a', 0.8), candidate('b', 0.4)]);
    const home = deriveHomeModel({
      now: NOW,
      events: [event({ id: 'e1', type: 'play_started', timestamp: NOW - 1_000 })],
      taste: null,
      recommendations: recs,
      graph: { nodes: [], edges: [] },
      library: { ...emptyLibrary(), recentlyPlayed: [track('t1')] },
      openThread: {
        track: track('t9'),
        remainder: [track('t8')],
        origin: { type: 'vibe', id: 'late-train-home' },
        owner: 'user',
        updatedAt: NOW,
      },
    });
    const cont = home.sections.find((section) => section.id === 'continueListening')!;
    expect(cont.visible).toBe(true);
    expect(cont.items[0].source).toBe('open-thread');
    expect(cont.items[0].queue?.map((entry) => entry.id)).toEqual(['t9', 't8']);
    expect(cont.items[0].discoveryContext?.type).toBe('vibe');
    expect(home.sections.find((section) => section.id === 'moodifyMix')?.visible).toBe(true);
  });
});
