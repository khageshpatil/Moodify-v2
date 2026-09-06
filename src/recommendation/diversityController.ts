import type { TrackDna } from '@/trackDna/trackDnaTypes';
import type { TasteSnapshot } from '@/taste/tasteTypes';
import { RECOMMENDATION_WEIGHTS } from './recommendationWeights';
import type { RankedCandidate } from './recommendationTypes';

const artistKeyFor = (candidate: RankedCandidate, trackDnaByKey: Record<string, TrackDna>, taste: TasteSnapshot) => {
  const dna = trackDnaByKey[candidate.trackKey];
  if (dna?.observable?.artistId) return `artist-id:${dna.observable.artistId}`;
  const profile = taste.longTerm.tracks[candidate.trackKey];
  if (profile?.artistKey) return profile.artistKey;
  return candidate.track.artist.toLowerCase();
};

const albumKeyFor = (candidate: RankedCandidate, trackDnaByKey: Record<string, TrackDna>, taste: TasteSnapshot) => {
  const dna = trackDnaByKey[candidate.trackKey];
  if (dna?.observable?.albumId) return `album-id:${dna.observable.albumId}`;
  const profile = taste.longTerm.tracks[candidate.trackKey];
  if (profile?.albumKey) return profile.albumKey;
  return candidate.track.album.toLowerCase();
};

const primarySource = (candidate: RankedCandidate) => candidate.provenance[0]?.source || 'unknown';

export const applyDiversity = (
  ranked: RankedCandidate[],
  taste: TasteSnapshot,
  trackDnaByKey: Record<string, TrackDna>,
  limit: number,
  explorationRatio: number,
): RankedCandidate[] => {
  const caps = RECOMMENDATION_WEIGHTS.diversity;
  const artistCounts = new Map<string, number>();
  const albumCounts = new Map<string, number>();
  const sourceCounts = new Map<string, number>();
  const selected: RankedCandidate[] = [];
  const deferred: RankedCandidate[] = [];

  const explorationTarget = Math.max(0, Math.round(limit * explorationRatio));
  let explorationSelected = 0;

  const trySelect = (candidate: RankedCandidate, force = false) => {
    const artist = artistKeyFor(candidate, trackDnaByKey, taste);
    const album = albumKeyFor(candidate, trackDnaByKey, taste);
    const source = primarySource(candidate);
    const artistCount = artistCounts.get(artist) || 0;
    const albumCount = albumCounts.get(album) || 0;
    const sourceCount = sourceCounts.get(source) || 0;

    if (artistCount >= caps.maxPerArtist || albumCount >= caps.maxPerAlbum) return false;
    if (sourceCount >= caps.maxPerSource) return false;
    if (candidate.isExploration && explorationSelected >= explorationTarget) return false;

    let adjustment = 0;
    if (!force) {
      if (artistCount >= caps.maxPerArtist - 1) adjustment -= caps.overflowPenalty * 0.5;
      if (albumCount >= caps.maxPerAlbum - 1) adjustment -= caps.overflowPenalty * 0.5;
    }

    const adjustedScore = candidate.score + adjustment;
    if (!force && adjustment < 0 && selected.length < limit) {
      deferred.push({ ...candidate, diversityAdjustment: adjustment, score: adjustedScore });
      return false;
    }

    selected.push({ ...candidate, diversityAdjustment: adjustment, score: adjustedScore });
    artistCounts.set(artist, artistCount + 1);
    albumCounts.set(album, albumCount + 1);
    sourceCounts.set(source, sourceCount + 1);
    if (candidate.isExploration) explorationSelected += 1;
    return true;
  };

  for (const candidate of ranked) {
    if (selected.length >= limit) break;
    if (!trySelect(candidate)) continue;
  }

  if (selected.length < limit) {
    for (const candidate of deferred.sort((a, b) => b.score - a.score || a.trackKey.localeCompare(b.trackKey))) {
      if (selected.length >= limit) break;
      if (selected.some((item) => item.trackKey === candidate.trackKey)) continue;
      trySelect(candidate, true);
    }
  }

  if (selected.length < limit) {
    for (const candidate of ranked) {
      if (selected.length >= limit) break;
      if (selected.some((item) => item.trackKey === candidate.trackKey)) continue;
      trySelect(candidate, true);
    }
  }

  return selected
    .sort((a, b) => b.score - a.score || a.trackKey.localeCompare(b.trackKey))
    .slice(0, limit);
};
