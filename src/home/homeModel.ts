import type { Track } from '@/data/mockMusic';
import type { ListeningEvent } from '@/listening/listeningTypes';
import type { TasteSnapshot, TrackTasteProfile } from '@/taste/tasteTypes';
import { identityKey } from '@/listening/listeningTypes';
import { resolveColdStartTier } from '@/recommendation/recommendationEngine';
import { graphNodeToTrack, isTrackNode, trackKeyFromTrack } from '@/recommendation/graphUtils';
import type { DiscoveryGraphSnapshot, RankedCandidate, RecommendationResult } from '@/recommendation/recommendationTypes';
import type { OpenListeningThread } from '@/listening/openThread';
import { queueFromOpenThread } from '@/listening/openThread';
import {
  HOME_MODEL_VERSION,
  MOODIFY_MIX_CONTRACT_VERSION,
  SCENE_CONTRACT_VERSION,
  type HomeItem,
  type HomeProminence,
  type HomeSection,
  type HomeSectionId,
  type MoodifyHomeModel,
  type MoodifyMixContract,
} from './homeTypes';

export interface HomeLibraryInput {
  playlists: Array<{ id: string; name: string; tracks: Track[] }>;
  favorites: Track[];
  history: Array<{ track: Track }>;
  recentlyPlayed: Track[];
}

export interface DeriveHomeModelInput {
  now: number;
  events: ListeningEvent[];
  taste: TasteSnapshot | null;
  recommendations: RecommendationResult | null;
  graph: DiscoveryGraphSnapshot;
  library: HomeLibraryInput;
  openThread?: OpenListeningThread | null;
}

const SECTION_TITLES: Record<HomeSectionId, string> = {
  continueListening: 'Continue Listening',
  yourRotation: 'Your Rotation',
  forYou: 'For You',
  freshFinds: 'Fresh Finds',
  scenes: 'Scenes',
  moodifyMix: 'Moodify Mix',
};

const compareItems = (a: HomeItem, b: HomeItem) => {
  const scoreDelta = (b.score ?? 0) - (a.score ?? 0);
  if (scoreDelta !== 0) return scoreDelta;
  return a.id.localeCompare(b.id);
};

export const buildTrackCatalog = (library: HomeLibraryInput, recommendations: RecommendationResult | null, graph: DiscoveryGraphSnapshot): Map<string, Track> => {
  const catalog = new Map<string, Track>();
  const add = (track?: Track | null) => {
    if (!track?.id) return;
    const key = trackKeyFromTrack(track);
    if (!catalog.has(key)) catalog.set(key, track);
  };
  library.favorites.forEach(add);
  library.recentlyPlayed.forEach(add);
  library.history.forEach((item) => add(item.track));
  library.playlists.forEach((playlist) => playlist.tracks.forEach(add));
  recommendations?.candidates.forEach((candidate) => add(candidate.track));
  graph.nodes.forEach((node) => {
    if (!isTrackNode(node)) return;
    add(graphNodeToTrack(node));
  });
  return catalog;
};

export const consumedTrackKeys = (events: ListeningEvent[]): Set<string> => {
  const starts = new Map<string, number>();
  const completions = new Map<string, number>();
  const favorites = new Set<string>();
  events.forEach((event) => {
    const key = identityKey(event);
    if (event.type === 'play_started') starts.set(key, (starts.get(key) || 0) + 1);
    if (event.type === 'play_completed') completions.set(key, (completions.get(key) || 0) + 1);
    if (event.type === 'favorite') favorites.add(key);
  });
  const consumed = new Set<string>();
  new Set([...starts.keys(), ...completions.keys(), ...favorites]).forEach((key) => {
    const startCount = starts.get(key) || 0;
    const completeCount = completions.get(key) || 0;
    if (completeCount >= 1 || startCount >= 2 || favorites.has(key)) consumed.add(key);
  });
  return consumed;
};

export const isRotationProfile = (profile: TrackTasteProfile) =>
  profile.replays > 0
  || profile.favorites > 0
  || profile.distinctSessions >= 2
  || (profile.completions >= 2 && profile.affinity >= 0.35);

export const resolveSectionProminence = (
  tier: RecommendationResult['coldStartTier'],
  section: HomeSectionId,
  itemCount: number,
): HomeProminence => {
  if (section === 'continueListening') {
    if (itemCount === 0) return 'hidden';
    return 'prominent';
  }
  if (section === 'yourRotation') {
    if (itemCount === 0) return 'hidden';
    if (tier === 'empty') return 'hidden';
    if (tier === 'sparse') return 'available';
    return 'prominent';
  }
  if (section === 'forYou') {
    if (itemCount === 0) return 'hidden';
    return 'hidden';
  }
  if (section === 'freshFinds') {
    if (itemCount === 0) return 'hidden';
    return 'hidden';
  }
  if (section === 'scenes') {
    return tier === 'empty' || tier === 'sparse' ? 'prominent' : 'available';
  }
  if (section === 'moodifyMix') {
    if (itemCount === 0) return 'hidden';
    if (tier === 'empty' || tier === 'sparse') return 'hidden';
    return 'prominent';
  }
  if (itemCount === 0) return 'available';
  return tier === 'empty' ? 'available' : 'prominent';
};

const section = (id: HomeSectionId, prominence: HomeProminence, items: HomeItem[]): HomeSection => ({
  id,
  title: SECTION_TITLES[id],
  visible: prominence !== 'hidden',
  prominence,
  items,
});

export const resolveContinueListening = (
  _events: ListeningEvent[],
  _library: HomeLibraryInput,
  _catalog: Map<string, Track>,
  openThread?: OpenListeningThread | null,
): HomeItem[] => {
  if (openThread?.track) {
    return [{
      id: 'continue-open-thread',
      kind: 'track',
      track: openThread.track,
      queue: queueFromOpenThread(openThread),
      discoveryContext: openThread.origin,
      owner: openThread.owner,
      source: openThread.owner === 'mix' ? 'moodify-mix-thread' : 'open-thread',
    }];
  }
  return [];
};

export const resolveYourRotation = (taste: TasteSnapshot | null, catalog: Map<string, Track>): HomeItem[] => {
  if (!taste) return [];
  const profiles = Object.values(taste.longTerm.tracks)
    .filter(isRotationProfile)
    .sort((a, b) => b.affinity - a.affinity || a.trackKey.localeCompare(b.trackKey));
  return profiles
    .map((profile) => {
      const track = catalog.get(profile.trackKey);
      if (!track) return null;
      return {
        id: `rotation:${profile.trackKey}`,
        kind: 'track' as const,
        track,
        source: 'return-pattern',
        score: profile.affinity,
        explanationKeys: [
          profile.replays > 0 ? 'replayed' : '',
          profile.favorites > 0 ? 'favorited' : '',
          profile.distinctSessions >= 2 ? 'multi-session' : '',
          profile.completions >= 2 ? 'completed' : '',
        ].filter(Boolean),
      };
    })
    .filter((item): item is NonNullable<typeof item> => item !== null)
    .slice(0, 12);
};

export const resolveForYou = (recommendations: RecommendationResult | null): HomeItem[] => {
  if (!recommendations) return [];
  return recommendations.candidates.slice(0, 12).map((candidate) => ({
    id: `for-you:${candidate.trackKey}`,
    kind: 'track' as const,
    track: candidate.track,
    source: candidate.provenance[0]?.source || 'recommendation-engine-v1',
    score: candidate.score,
    provenance: candidate.provenance,
    explanationKeys: candidate.explanationKeys,
  }));
};

const isFreshCandidate = (candidate: RankedCandidate) =>
  candidate.isExploration
  || candidate.provenance.some((entry) => entry.source === 'exploration' || entry.source.startsWith('graph-'));

export const resolveFreshFinds = (
  recommendations: RecommendationResult | null,
  graph: DiscoveryGraphSnapshot,
  consumed: Set<string>,
  catalog: Map<string, Track>,
): HomeItem[] => {
  const items: HomeItem[] = [];
  const seen = new Set<string>();
  recommendations?.candidates.forEach((candidate) => {
    if (!isFreshCandidate(candidate) || consumed.has(candidate.trackKey) || seen.has(candidate.trackKey)) return;
    seen.add(candidate.trackKey);
    items.push({
      id: `fresh:${candidate.trackKey}`,
      kind: 'track',
      track: candidate.track,
      source: candidate.provenance.find((entry) => entry.source === 'exploration' || entry.source.startsWith('graph-'))?.source || 'exploration',
      score: candidate.score,
      provenance: candidate.provenance,
      explanationKeys: candidate.explanationKeys,
    });
  });

  [...graph.nodes]
    .filter(isTrackNode)
    .map(graphNodeToTrack)
    .filter((track): track is NonNullable<typeof track> => Boolean(track))
    .sort((a, b) => a.trackKey.localeCompare(b.trackKey))
    .forEach((track) => {
      if (consumed.has(track.trackKey) || seen.has(track.trackKey) || !catalog.has(track.trackKey)) return;
      seen.add(track.trackKey);
      items.push({
        id: `fresh-graph:${track.trackKey}`,
        kind: 'track',
        track,
        source: 'discovery-graph',
      });
    });

  return [...items].sort(compareItems).slice(0, 8);
};

export const resolveMoodifyMix = (recommendations: RecommendationResult | null): MoodifyMixContract | null => {
  if (!recommendations || recommendations.candidates.length === 0) return null;
  return {
    version: MOODIFY_MIX_CONTRACT_VERSION,
    id: `mix:${recommendations.generatedAt}:${recommendations.strategy}`,
    engineVersion: recommendations.version,
    strategy: recommendations.strategy,
    generatedAt: recommendations.generatedAt,
    candidateKeys: recommendations.candidates.map((candidate) => candidate.trackKey),
    source: 'recommendation-engine-v1',
  };
};

export const deriveHomeModel = (input: DeriveHomeModelInput): MoodifyHomeModel => {
  const tier = input.recommendations?.coldStartTier || resolveColdStartTier(input.events.length);
  const catalog = buildTrackCatalog(input.library, input.recommendations, input.graph);
  const consumed = consumedTrackKeys(input.events);
  const continueItems = resolveContinueListening(input.events, input.library, catalog, input.openThread);
  const rotationItems = resolveYourRotation(input.taste, catalog);
  const forYouItems = resolveForYou(input.recommendations);
  const freshItems = resolveFreshFinds(input.recommendations, input.graph, consumed, catalog);
  const mix = resolveMoodifyMix(input.recommendations);
  const mixItems: HomeItem[] = mix
    ? [{
        id: mix.id,
        kind: 'mix',
        source: mix.source,
        queue: (input.recommendations?.candidates || []).map((candidate) => candidate.track),
        discoveryContext: {
          type: 'recommendation',
          id: mix.id,
          position: 0,
          strategy: mix.strategy,
          sources: [mix.source],
        },
        score: input.recommendations?.candidates[0]?.score,
        provenance: input.recommendations?.candidates[0]?.provenance,
        explanationKeys: input.recommendations?.candidates[0]?.explanationKeys,
      }]
    : [];

  const sections: HomeSection[] = [
    section('continueListening', resolveSectionProminence(tier, 'continueListening', continueItems.length), continueItems),
    section('yourRotation', resolveSectionProminence(tier, 'yourRotation', rotationItems.length), rotationItems),
    section('forYou', resolveSectionProminence(tier, 'forYou', forYouItems.length), forYouItems),
    section('freshFinds', resolveSectionProminence(tier, 'freshFinds', freshItems.length), freshItems),
    section('scenes', resolveSectionProminence(tier, 'scenes', 0), []),
    section('moodifyMix', resolveSectionProminence(tier, 'moodifyMix', mixItems.length), mixItems),
  ];

  return {
    version: HOME_MODEL_VERSION,
    generatedAt: input.now,
    coldStartTier: tier,
    sceneContractVersion: SCENE_CONTRACT_VERSION,
    publishedScenes: [],
    moodifyMix: mix,
    sections,
  };
};
