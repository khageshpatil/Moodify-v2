import type { TasteSnapshot } from '@/taste/tasteTypes';
import type { TrackDna } from '@/trackDna/trackDnaTypes';
import { RECOMMENDATION_WEIGHTS } from './recommendationWeights';
import type { RawCandidate, RankedCandidate, RecommendationContext } from './recommendationTypes';

const clamp01 = (value: number) => Math.min(1, Math.max(0, value));
const round4 = (value: number) => Math.round(value * 10000) / 10000;

const decayPenalty = (ageMs: number, halfLife: number) => clamp01(Math.pow(0.5, ageMs / halfLife));

const artistKeyFor = (trackKey: string, trackDnaByKey: Record<string, TrackDna>, taste: TasteSnapshot) => {
  const dna = trackDnaByKey[trackKey];
  if (dna?.observable?.artistId) return `artist-id:${dna.observable.artistId}`;
  const profile = taste.longTerm.tracks[trackKey];
  if (profile?.artistKey) return profile.artistKey;
  if (dna?.identity.artist) return `artist-name:${dna.identity.artist.toLowerCase()}`;
  return undefined;
};

const albumKeyFor = (trackKey: string, trackDnaByKey: Record<string, TrackDna>, taste: TasteSnapshot) => {
  const dna = trackDnaByKey[trackKey];
  if (dna?.observable?.albumId) return `album-id:${dna.observable.albumId}`;
  const profile = taste.longTerm.tracks[trackKey];
  if (profile?.albumKey) return profile.albumKey;
  if (dna?.identity.album) return `album-name:${dna.identity.album.toLowerCase()}`;
  return undefined;
};

export const rankCandidates = (
  candidates: RawCandidate[],
  taste: TasteSnapshot,
  trackDnaByKey: Record<string, TrackDna>,
  context: RecommendationContext,
  coldStartTier: 'empty' | 'sparse' | 'emerging' | 'mature',
): RankedCandidate[] => {
  const w = RECOMMENDATION_WEIGHTS.scoring;
  const now = context.now;
  const recentSet = new Set(context.recentTrackKeys);
  const skipSet = new Set(context.recentSkips);
  const isPersonalized = coldStartTier !== 'empty';

  const ranked = candidates.map((candidate) => {
    const { trackKey, track, provenance, graphStrength, isExploration } = candidate;
    const profile = taste.longTerm.tracks[trackKey] || taste.recent.tracks[trackKey];
    const artistKey = artistKeyFor(trackKey, trackDnaByKey, taste);
    const albumKey = albumKeyFor(trackKey, trackDnaByKey, taste);
    const artistProfile = artistKey ? taste.longTerm.artists[artistKey] : undefined;
    const albumProfile = albumKey ? taste.longTerm.albums[albumKey] : undefined;

    const tasteAffinity = profile?.affinity || 0;
    const artistAffinity = artistProfile?.preferenceScore || 0;
    const albumAffinity = albumProfile?.affinity || 0;

    const discoveryAffinity = provenance.reduce((max, entry) => Math.max(max, entry.discoveryAffinity || 0), 0)
      || (provenance.some((entry) => entry.source === 'discovery-surface')
        ? Object.values(taste.discovery).reduce((max, surface) => Math.max(max, surface.affinity), 0)
        : 0);

    const sessionContext = provenance.some((entry) => entry.source === 'session-sequence')
      ? RECOMMENDATION_WEIGHTS.session.transitionBoost
      : recentSet.has(provenance[0]?.sourceTrackKey || '') ? 0.05 : 0;

    const lastPlayedAt = profile?.lastPlayedAt || null;
    const age = lastPlayedAt ? now - lastPlayedAt : Number.POSITIVE_INFINITY;
    const isFavorite = (profile?.favorites || 0) > 0;
    const recentPlayPenaltyRaw = age < RECOMMENDATION_WEIGHTS.filter.recentPlayWindowMs
      ? (isFavorite ? 0.15 : 0.55) * (1 - age / RECOMMENDATION_WEIGHTS.filter.recentPlayWindowMs)
      : 0;
    const recencyFit = lastPlayedAt
      ? clamp01(Math.pow(0.5, age / RECOMMENDATION_WEIGHTS.negativeDecayHalfLifeMs)) * (isFavorite ? 1 : 0.7)
      : 0.15;

    const familiarity = tasteAffinity >= 0.45 || isFavorite || (profile?.replays || 0) > 0 ? tasteAffinity : 0;
    const explorationBonus = isExploration ? 0.35 + graphStrength * 0.25 : 0;

    const components: Record<string, number> = {
      tasteAffinity: round4(tasteAffinity * w.tasteAffinity),
      artistAffinity: round4(artistAffinity * w.artistAffinity),
      albumAffinity: round4(albumAffinity * w.albumAffinity),
      graphRelationship: round4(graphStrength * w.graphRelationship),
      discoverySourceAffinity: round4(discoveryAffinity * w.discoverySourceAffinity),
      sessionContext: round4(sessionContext * w.sessionContext),
      recencyFit: round4(recencyFit * w.recencyFit),
      familiarity: round4(familiarity * w.familiarity),
      explorationBonus: round4(explorationBonus * w.explorationBonus),
    };

    const penalties: Record<string, number> = {};
    if (skipSet.has(trackKey)) {
      penalties.recentSkipPenalty = round4(-w.recentSkipPenalty * decayPenalty(0, RECOMMENDATION_WEIGHTS.negativeDecayHalfLifeMs));
    }
    if ((profile?.earlySkips || 0) >= 2) {
      penalties.repeatedSkipPenalty = round4(-w.repeatedSkipPenalty * clamp01((profile?.earlySkips || 0) / 3));
    }
    if (recentSet.has(trackKey) && trackKey !== `${context.currentTrack?.provider || 'youtube-music'}:${context.currentTrack?.providerId || context.currentTrack?.id}`) {
      penalties.immediateRepeatPenalty = round4(-w.immediateRepeatPenalty * recentPlayPenaltyRaw);
    }
    if (context.queueTrackKeys.includes(trackKey)) {
      penalties.queueDuplicatePenalty = round4(-w.queueDuplicatePenalty);
    }

    const positive = Object.values(components).reduce((sum, value) => sum + value, 0);
    const negative = Object.values(penalties).reduce((sum, value) => sum + value, 0);
    const score = round4(clamp01(positive + negative));

    const explanationKeys: string[] = [];
    if (components.tasteAffinity > 0.08) explanationKeys.push('strong_track_affinity');
    if (components.artistAffinity > 0.06) explanationKeys.push('strong_artist_affinity');
    if (components.graphRelationship > 0.05) explanationKeys.push('graph_relationship');
    if (components.discoverySourceAffinity > 0.04) explanationKeys.push('successful_discovery_surface');
    if (components.sessionContext > 0.02) explanationKeys.push('session_sequence');
    if (components.familiarity > 0.05) explanationKeys.push('familiar_engagement');
    if (components.explorationBonus > 0.02) explanationKeys.push('controlled_exploration');
    if (penalties.recentSkipPenalty) explanationKeys.push('recent_skip_penalty');
    if (penalties.immediateRepeatPenalty) explanationKeys.push('recently_played_penalty');

    const sampleCount = (profile?.playStarts || 0) + provenance.length;
    const confidence = round4(clamp01(
      (isPersonalized ? 0.35 : 0.15)
      + Math.min(0.35, sampleCount / 10)
      + (profile ? 0.2 : 0)
      + (provenance.length > 1 ? 0.1 : 0),
    ));

    return {
      trackKey,
      track,
      score,
      confidence,
      provenance,
      components,
      penalties,
      diversityAdjustment: 0,
      isExploration,
      isPersonalized,
      explanationKeys,
      caveats: coldStartTier === 'empty' ? ['Cold start: recommendations are graph/fallback only.'] : [],
    } satisfies RankedCandidate;
  });

  return ranked.sort((a, b) => b.score - a.score || a.trackKey.localeCompare(b.trackKey));
};
