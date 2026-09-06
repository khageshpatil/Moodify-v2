import type { DiscoveryContextType, ListeningEvent } from '@/listening/listeningTypes';
import { identityKey } from '@/listening/listeningTypes';
import { MISSING_TASTE_SIGNALS, TASTE_WEIGHTS } from './tasteWeights';
import type {
  AlbumTasteProfile,
  ArtistTasteProfile,
  DiscoverySurfaceTaste,
  TasteContribution,
  TasteEvidence,
  TasteModelOptions,
  TasteSnapshot,
  TasteWindow,
  TasteWindowSlice,
  TrackTasteProfile,
} from './tasteTypes';
import { TASTE_MODEL_VERSION } from './tasteTypes';

const clamp01 = (value: number) => Math.min(1, Math.max(0, value));
const round4 = (value: number) => Math.round(value * 10000) / 10000;
const average = (values: number[]) => (values.length ? values.reduce((sum, value) => sum + value, 0) / values.length : 0);
const sat = (count: number, scale: number) => clamp01(count / scale);

const normalizeName = (value?: string) => (value || '').normalize('NFKC').trim().toLowerCase().replace(/\s+/g, ' ');

const recencyBoost = (lastPlayedAt: number | null, now: number) => {
  if (!lastPlayedAt) return 0;
  const age = Math.max(0, now - lastPlayedAt);
  return clamp01(Math.pow(0.5, age / TASTE_WEIGHTS.recencyHalfLifeMs));
};

const pushId = (ids: string[], id: string, max: number) => {
  if (ids.length < max) ids.push(id);
};

const contribution = (signal: string, weight: number, raw: number): TasteContribution => ({
  signal,
  weight,
  raw: round4(raw),
  contribution: round4(weight * raw),
});

interface TrackAccumulator {
  trackKey: string;
  trackId: string;
  provider: string;
  providerTrackId: string;
  artistName?: string;
  playStarts: number;
  completions: number;
  skips: number;
  earlySkips: number;
  replays: number;
  favorites: number;
  queueAdds: number;
  queueRemoves: number;
  completionSamples: number[];
  sessions: Set<string>;
  eventIds: string[];
  lastPlayedAt: number | null;
  firstPlayedAt: number | null;
}

interface DiscoveryAccumulator {
  surface: DiscoveryContextType;
  playStarts: number;
  completions: number;
  earlySkips: number;
  skips: number;
  replays: number;
  completionSamples: number[];
  eventIds: string[];
}

const emptyTrack = (event: ListeningEvent): TrackAccumulator => ({
  trackKey: identityKey(event),
  trackId: event.trackId,
  provider: event.provider,
  providerTrackId: event.providerTrackId,
  artistName: event.artistName,
  playStarts: 0,
  completions: 0,
  skips: 0,
  earlySkips: 0,
  replays: 0,
  favorites: 0,
  queueAdds: 0,
  queueRemoves: 0,
  completionSamples: [],
  sessions: new Set(),
  eventIds: [],
  lastPlayedAt: null,
  firstPlayedAt: null,
});

const filterEvents = (events: ListeningEvent[], window: TasteWindow, now: number, recentWindowMs: number, sessionId?: string) => {
  if (window === 'session') return sessionId ? events.filter((event) => event.sessionId === sessionId) : [];
  if (window === 'recent') return events.filter((event) => now - event.timestamp <= recentWindowMs);
  return events;
};

const accumulateTracks = (events: ListeningEvent[], maxEvidenceIds: number) => {
  const tracks = new Map<string, TrackAccumulator>();
  events.forEach((event) => {
    const key = identityKey(event);
    const current = tracks.get(key) || emptyTrack(event);
    if (event.artistName) current.artistName = event.artistName;
    pushId(current.eventIds, event.id, maxEvidenceIds);
    current.sessions.add(event.sessionId);

    if (event.type === 'play_started') {
      current.playStarts += 1;
      current.lastPlayedAt = Math.max(current.lastPlayedAt || 0, event.timestamp);
      current.firstPlayedAt = current.firstPlayedAt == null ? event.timestamp : Math.min(current.firstPlayedAt, event.timestamp);
    }
    if (event.type === 'play_completed') {
      current.completions += 1;
      current.completionSamples.push(event.completionRatio ?? 1);
    }
    if (event.type === 'skip') {
      current.skips += 1;
      if (event.earlySkip) current.earlySkips += 1;
      current.completionSamples.push(event.completionRatio ?? 0);
    }
    if (event.type === 'replay') current.replays += 1;
    if (event.type === 'favorite') current.favorites += 1;
    if (event.type === 'unfavorite') current.favorites = Math.max(0, current.favorites - 1);
    if (event.type === 'queue_added') current.queueAdds += 1;
    if (event.type === 'queue_removed') current.queueRemoves += 1;
    tracks.set(key, current);
  });
  return tracks;
};

const buildTrackProfile = (
  acc: TrackAccumulator,
  window: TasteWindow,
  now: number,
  dna?: { identity: { artist?: string; album?: string }; observable?: { artistId?: string; albumId?: string } },
): TrackTasteProfile => {
  const w = TASTE_WEIGHTS.track;
  const plays = Math.max(1, acc.playStarts);
  const averageCompletion = average(acc.completionSamples);
  const completionRaw = clamp01(averageCompletion) * sat(acc.playStarts + acc.completions, 3);
  const replayRaw = sat(acc.replays, 2);
  const favoriteRaw = clamp01(acc.favorites);
  const repetitionRaw = sat(acc.sessions.size, 2);
  const recencyRaw = recencyBoost(acc.lastPlayedAt, now);
  const queueAddRaw = sat(Math.max(0, acc.queueAdds - acc.queueRemoves), 2);
  const earlySkipRaw = clamp01(acc.earlySkips / plays);
  const lateSkipRaw = clamp01(Math.max(0, acc.skips - acc.earlySkips) / plays);
  const queueRemoveRaw = sat(acc.queueRemoves, 2);

  const contributions = [
    contribution('completion', w.completion, completionRaw),
    contribution('replay', w.replay, replayRaw),
    contribution('favorite', w.favorite, favoriteRaw),
    contribution('repetition', w.repetition, repetitionRaw),
    contribution('recency', w.recency, recencyRaw),
    contribution('queue_add', w.queueAdd, queueAddRaw),
    contribution('early_skip_penalty', -w.earlySkip, earlySkipRaw),
    contribution('skip_penalty', -w.skip, lateSkipRaw),
    contribution('queue_remove_penalty', -w.queueRemove, queueRemoveRaw),
  ];
  const affinity = round4(clamp01(contributions.reduce((sum, item) => sum + item.contribution, 0)));

  const caveats: string[] = [];
  if (acc.playStarts < 2) caveats.push('Low sample size: affinity is provisional.');
  if (!acc.completionSamples.length) caveats.push('No completion/skip ratio samples; completion term is weak.');
  if (acc.earlySkips > 0) caveats.push('Early skips reduce affinity but are not treated as permanent dislike.');

  const artistName = dna?.identity?.artist || acc.artistName;
  const albumTitle = dna?.identity?.album;
  const artistId = dna?.observable?.artistId;
  const albumId = dna?.observable?.albumId;

  const evidence: TasteEvidence = {
    subjectKind: 'track',
    subjectKey: acc.trackKey,
    signal: 'affinity',
    value: affinity,
    sampleCount: acc.playStarts + acc.completions + acc.skips + acc.replays + acc.favorites,
    window,
    evidenceEventIds: [...acc.eventIds],
    contributions,
    generatedAt: now,
    version: TASTE_MODEL_VERSION,
    caveats,
  };

  return {
    trackKey: acc.trackKey,
    trackId: acc.trackId,
    provider: acc.provider,
    providerTrackId: acc.providerTrackId,
    artistKey: artistId ? `artist-id:${artistId}` : artistName ? `artist-name:${normalizeName(artistName)}` : undefined,
    artistName,
    albumKey: albumId ? `album-id:${albumId}` : albumTitle ? `album-name:${normalizeName(albumTitle)}` : undefined,
    albumTitle,
    playStarts: acc.playStarts,
    completions: acc.completions,
    skips: acc.skips,
    earlySkips: acc.earlySkips,
    replays: acc.replays,
    favorites: acc.favorites,
    queueAdds: acc.queueAdds,
    queueRemoves: acc.queueRemoves,
    averageCompletion: round4(averageCompletion),
    distinctSessions: acc.sessions.size,
    lastPlayedAt: acc.lastPlayedAt,
    firstPlayedAt: acc.firstPlayedAt,
    affinity,
    evidence,
  };
};

const aggregateArtists = (tracks: TrackTasteProfile[], window: TasteWindow, now: number): Record<string, ArtistTasteProfile> => {
  const buckets = new Map<string, {
    artistKey: string;
    artistName: string;
    artistId?: string;
    tracks: TrackTasteProfile[];
    sessions: Set<string>;
    eventIds: string[];
  }>();

  tracks.forEach((track) => {
    if (!track.artistKey || !track.artistName) return;
    const bucket = buckets.get(track.artistKey) || {
      artistKey: track.artistKey,
      artistName: track.artistName,
      artistId: track.artistKey.startsWith('artist-id:') ? track.artistKey.slice('artist-id:'.length) : undefined,
      tracks: [],
      sessions: new Set<string>(),
      eventIds: [],
    };
    bucket.tracks.push(track);
    track.evidence.evidenceEventIds.forEach((id) => pushId(bucket.eventIds, id, 40));
    buckets.set(track.artistKey, bucket);
  });

  const w = TASTE_WEIGHTS.artist;
  const artists: Record<string, ArtistTasteProfile> = {};

  buckets.forEach((bucket) => {
    const playStarts = bucket.tracks.reduce((sum, track) => sum + track.playStarts, 0);
    const completions = bucket.tracks.reduce((sum, track) => sum + track.completions, 0);
    const skips = bucket.tracks.reduce((sum, track) => sum + track.skips, 0);
    const earlySkips = bucket.tracks.reduce((sum, track) => sum + track.earlySkips, 0);
    const replays = bucket.tracks.reduce((sum, track) => sum + track.replays, 0);
    const favorites = bucket.tracks.reduce((sum, track) => sum + track.favorites, 0);
    const distinctSessions = bucket.tracks.reduce((max, track) => Math.max(max, track.distinctSessions), 0);
    const lastPlayedAt = bucket.tracks.reduce<number | null>((max, track) => Math.max(max || 0, track.lastPlayedAt || 0) || null, null);
    const plays = Math.max(1, playStarts);
    const meanTrackAffinity = average(bucket.tracks.map((track) => track.affinity));
    const completionRate = clamp01(completions / plays);
    const replayRate = clamp01(replays / plays);
    const earlySkipRate = clamp01(earlySkips / plays);
    const skipRate = clamp01(skips / plays);
    const favoriteRaw = clamp01(favorites);
    const repetitionRaw = sat(distinctSessions, 2);
    const recencyRaw = recencyBoost(lastPlayedAt, now);

    const contributions = [
      contribution('mean_track_affinity', w.trackAffinity, meanTrackAffinity),
      contribution('completion_rate', w.completionRate, completionRate),
      contribution('replay_rate', w.replayRate, replayRate),
      contribution('favorite', w.favorite, favoriteRaw),
      contribution('repetition', w.repetition, repetitionRaw),
      contribution('recency', w.recency, recencyRaw),
      contribution('early_skip_penalty', -w.earlySkipRate, earlySkipRate),
      contribution('skip_penalty', -w.skipRate, skipRate),
    ];
    const preferenceScore = round4(clamp01(contributions.reduce((sum, item) => sum + item.contribution, 0)));
    const exposureScore = round4(clamp01(TASTE_WEIGHTS.artist.exposureCap * sat(playStarts, 8)));
    // Preference requires behaviour beyond mere exposure.
    const affinity = round4(clamp01(preferenceScore));

    artists[bucket.artistKey] = {
      artistKey: bucket.artistKey,
      artistName: bucket.artistName,
      artistId: bucket.artistId,
      trackKeys: bucket.tracks.map((track) => track.trackKey),
      playStarts,
      completions,
      skips,
      earlySkips,
      replays,
      favorites,
      distinctSessions,
      distinctTracks: bucket.tracks.length,
      lastPlayedAt,
      exposureScore,
      preferenceScore,
      affinity,
      evidence: {
        subjectKind: 'artist',
        subjectKey: bucket.artistKey,
        signal: 'preference',
        value: affinity,
        sampleCount: playStarts + completions + skips + replays + favorites,
        window,
        evidenceEventIds: bucket.eventIds,
        contributions: [
          ...contributions,
          contribution('exposure_only', 0, exposureScore),
        ],
        generatedAt: now,
        version: TASTE_MODEL_VERSION,
        caveats: [
          'Exposure score counts plays only and is not preference.',
          ...(bucket.tracks.length < 2 ? ['Artist affinity spans few tracks; treat as provisional.'] : []),
          ...(bucket.artistKey.startsWith('artist-name:') ? ['Artist keyed by normalized name; ID unavailable.'] : []),
        ],
      },
    };
  });

  return artists;
};

const aggregateAlbums = (tracks: TrackTasteProfile[], window: TasteWindow, now: number): Record<string, AlbumTasteProfile> => {
  const buckets = new Map<string, {
    albumKey: string;
    albumTitle: string;
    albumId?: string;
    tracks: TrackTasteProfile[];
    eventIds: string[];
  }>();

  tracks.forEach((track) => {
    if (!track.albumKey || !track.albumTitle) return;
    const bucket = buckets.get(track.albumKey) || {
      albumKey: track.albumKey,
      albumTitle: track.albumTitle,
      albumId: track.albumKey.startsWith('album-id:') ? track.albumKey.slice('album-id:'.length) : undefined,
      tracks: [],
      eventIds: [],
    };
    bucket.tracks.push(track);
    track.evidence.evidenceEventIds.forEach((id) => pushId(bucket.eventIds, id, 40));
    buckets.set(track.albumKey, bucket);
  });

  const w = TASTE_WEIGHTS.album;
  const albums: Record<string, AlbumTasteProfile> = {};

  buckets.forEach((bucket) => {
    const playStarts = bucket.tracks.reduce((sum, track) => sum + track.playStarts, 0);
    const completions = bucket.tracks.reduce((sum, track) => sum + track.completions, 0);
    const skips = bucket.tracks.reduce((sum, track) => sum + track.skips, 0);
    const earlySkips = bucket.tracks.reduce((sum, track) => sum + track.earlySkips, 0);
    const replays = bucket.tracks.reduce((sum, track) => sum + track.replays, 0);
    const favorites = bucket.tracks.reduce((sum, track) => sum + track.favorites, 0);
    const distinctSessions = bucket.tracks.reduce((max, track) => Math.max(max, track.distinctSessions), 0);
    const lastPlayedAt = bucket.tracks.reduce<number | null>((max, track) => Math.max(max || 0, track.lastPlayedAt || 0) || null, null);
    const plays = Math.max(1, playStarts);
    const engagedTracks = bucket.tracks.filter((track) => track.completions > 0 || track.replays > 0 || track.favorites > 0 || track.averageCompletion >= 0.5).length;
    const engagementBreadth = clamp01(engagedTracks / Math.max(1, bucket.tracks.length));
    const meanTrackAffinity = average(bucket.tracks.map((track) => track.affinity));
    const completionRate = clamp01(completions / plays);
    const replayRate = clamp01(replays / plays);
    const earlySkipRate = clamp01(earlySkips / plays);
    const skipRate = clamp01(skips / plays);

    const contributions = [
      contribution('mean_track_affinity', w.trackAffinity, meanTrackAffinity),
      contribution('completion_rate', w.completionRate, completionRate),
      contribution('replay_rate', w.replayRate, replayRate),
      contribution('favorite', w.favorite, clamp01(favorites)),
      contribution('engagement_breadth', w.breadth, engagementBreadth),
      contribution('recency', w.recency, recencyBoost(lastPlayedAt, now)),
      contribution('early_skip_penalty', -w.earlySkipRate, earlySkipRate),
      contribution('skip_penalty', -w.skipRate, skipRate),
    ];

    let affinity = round4(clamp01(contributions.reduce((sum, item) => sum + item.contribution, 0)));
    const caveats = [
      'Playing one album track does not imply preference for the entire album.',
    ];
    if (bucket.tracks.length < w.minTracksForConfidence) {
      affinity = round4(affinity * 0.5);
      caveats.push(`Fewer than ${w.minTracksForConfidence} album tracks observed; affinity down-weighted.`);
    }
    if (bucket.albumKey.startsWith('album-name:')) caveats.push('Album keyed by normalized title; ID unavailable.');

    albums[bucket.albumKey] = {
      albumKey: bucket.albumKey,
      albumTitle: bucket.albumTitle,
      albumId: bucket.albumId,
      trackKeys: bucket.tracks.map((track) => track.trackKey),
      playStarts,
      completions,
      skips,
      earlySkips,
      replays,
      favorites,
      distinctSessions,
      distinctTracks: bucket.tracks.length,
      lastPlayedAt,
      engagementBreadth: round4(engagementBreadth),
      affinity,
      evidence: {
        subjectKind: 'album',
        subjectKey: bucket.albumKey,
        signal: 'affinity',
        value: affinity,
        sampleCount: playStarts + completions + skips + replays + favorites,
        window,
        evidenceEventIds: bucket.eventIds,
        contributions,
        generatedAt: now,
        version: TASTE_MODEL_VERSION,
        caveats,
      },
    };
  });

  return albums;
};

const accumulateDiscovery = (events: ListeningEvent[], maxEvidenceIds: number): Record<string, DiscoverySurfaceTaste> => {
  const pending = new Map<string, { surface: DiscoveryContextType; playEventId: string }>();
  const lastSurface = new Map<string, DiscoveryContextType>();
  const buckets = new Map<DiscoveryContextType, DiscoveryAccumulator>();

  const ensure = (surface: DiscoveryContextType) => {
    const current = buckets.get(surface) || {
      surface,
      playStarts: 0,
      completions: 0,
      earlySkips: 0,
      skips: 0,
      replays: 0,
      completionSamples: [],
      eventIds: [],
    };
    buckets.set(surface, current);
    return current;
  };

  const sorted = [...events].sort((a, b) => a.timestamp - b.timestamp || a.id.localeCompare(b.id));
  sorted.forEach((event) => {
    const key = `${event.sessionId}|${identityKey(event)}`;
    if (event.type === 'play_started') {
      if (event.discoveryContext?.type) {
        const surface = event.discoveryContext.type;
        const bucket = ensure(surface);
        bucket.playStarts += 1;
        pushId(bucket.eventIds, event.id, maxEvidenceIds);
        pending.set(key, { surface, playEventId: event.id });
        lastSurface.set(key, surface);
      } else {
        pending.delete(key);
        lastSurface.delete(key);
      }
      return;
    }

    if (event.type === 'replay') {
      const surface = lastSurface.get(key);
      if (!surface) return;
      const bucket = ensure(surface);
      bucket.replays += 1;
      pushId(bucket.eventIds, event.id, maxEvidenceIds);
      return;
    }

    const attributed = pending.get(key);
    if (!attributed) return;
    const bucket = ensure(attributed.surface);
    pushId(bucket.eventIds, event.id, maxEvidenceIds);

    if (event.type === 'play_completed') {
      bucket.completions += 1;
      bucket.completionSamples.push(event.completionRatio ?? 1);
      pending.delete(key);
    } else if (event.type === 'skip') {
      bucket.skips += 1;
      if (event.earlySkip) bucket.earlySkips += 1;
      bucket.completionSamples.push(event.completionRatio ?? 0);
      pending.delete(key);
      lastSurface.delete(key);
    }
  });

  const w = TASTE_WEIGHTS.discovery;
  const discovery: Record<string, DiscoverySurfaceTaste> = {};

  buckets.forEach((bucket) => {
    const plays = Math.max(1, bucket.playStarts);
    const successRate = clamp01(bucket.completions / plays);
    const earlySkipRate = clamp01(bucket.earlySkips / plays);
    const skipRate = clamp01(bucket.skips / plays);
    const replayRate = clamp01(bucket.replays / plays);
    const averageCompletion = average(bucket.completionSamples);
    const contributions = [
      contribution('success_rate', w.successRate, successRate),
      contribution('average_completion', w.averageCompletion, clamp01(averageCompletion)),
      contribution('replay_rate', w.replayRate, replayRate),
      contribution('early_skip_penalty', -w.earlySkipRate, earlySkipRate),
      contribution('skip_penalty', -w.skipRate, skipRate),
    ];
    let affinity = round4(clamp01(contributions.reduce((sum, item) => sum + item.contribution, 0)));
    const caveats = [
      'Discovery affinity measures listener response after attributed plays, not impressions.',
    ];
    if (bucket.playStarts < w.minSamples) {
      affinity = round4(affinity * (bucket.playStarts / w.minSamples));
      caveats.push(`Fewer than ${w.minSamples} attributed plays; affinity down-weighted.`);
    }

    discovery[bucket.surface] = {
      surface: bucket.surface,
      playStarts: bucket.playStarts,
      completions: bucket.completions,
      earlySkips: bucket.earlySkips,
      skips: bucket.skips,
      replays: bucket.replays,
      averageCompletion: round4(averageCompletion),
      successRate: round4(successRate),
      earlySkipRate: round4(earlySkipRate),
      affinity,
      evidence: {
        subjectKind: 'discovery',
        subjectKey: bucket.surface,
        signal: 'discovery',
        value: affinity,
        sampleCount: bucket.playStarts,
        window: 'contextual',
        evidenceEventIds: bucket.eventIds,
        contributions,
        generatedAt: 0,
        version: TASTE_MODEL_VERSION,
        caveats,
      },
    };
  });

  return discovery;
};

const sortKeysByAffinity = <T extends { affinity: number }>(records: Record<string, T>, limit = 25) =>
  Object.entries(records)
    .sort(([keyA, a], [keyB, b]) => b.affinity - a.affinity || keyA.localeCompare(keyB))
    .slice(0, limit)
    .map(([key]) => key);

const buildWindowSlice = (
  events: ListeningEvent[],
  window: TasteWindow,
  now: number,
  options: Required<Pick<TasteModelOptions, 'trackDnaByKey' | 'maxEvidenceIds'>>,
): TasteWindowSlice => {
  const trackAcc = accumulateTracks(events, options.maxEvidenceIds);
  const tracks: Record<string, TrackTasteProfile> = {};
  trackAcc.forEach((acc, key) => {
    tracks[key] = buildTrackProfile(acc, window, now, options.trackDnaByKey?.[key]);
  });
  const trackList = Object.values(tracks);
  const artists = aggregateArtists(trackList, window, now);
  const albums = aggregateAlbums(trackList, window, now);
  return {
    window,
    tracks,
    artists,
    albums,
    topTrackKeys: sortKeysByAffinity(tracks),
    topArtistKeys: sortKeysByAffinity(artists),
    topAlbumKeys: sortKeysByAffinity(albums),
  };
};

/**
 * Derive a deterministic TasteSnapshot from listening events.
 * Optional Track DNA supplies album/artist IDs when present; missing DNA never invents them.
 */
export const deriveTasteSnapshot = (events: ListeningEvent[], options: TasteModelOptions = {}): TasteSnapshot => {
  const now = options.now ?? 0;
  const recentWindowMs = options.recentWindowMs ?? TASTE_WEIGHTS.recentWindowMs;
  const maxEvidenceIds = options.maxEvidenceIds ?? 40;
  const trackDnaByKey = options.trackDnaByKey || {};
  const stableEvents = [...events].sort((a, b) => a.timestamp - b.timestamp || a.id.localeCompare(b.id));

  const longTermEvents = filterEvents(stableEvents, 'long_term', now, recentWindowMs);
  const recentEvents = filterEvents(stableEvents, 'recent', now, recentWindowMs);
  const sessionEvents = options.currentSessionId
    ? filterEvents(stableEvents, 'session', now, recentWindowMs, options.currentSessionId)
    : [];

  const shared = { trackDnaByKey, maxEvidenceIds };
  const discovery = accumulateDiscovery(longTermEvents, maxEvidenceIds);
  Object.values(discovery).forEach((surface) => {
    surface.evidence.generatedAt = now;
  });

  return {
    version: TASTE_MODEL_VERSION,
    generatedAt: now,
    eventCount: stableEvents.length,
    missingSignals: [...MISSING_TASTE_SIGNALS],
    longTerm: buildWindowSlice(longTermEvents, 'long_term', now, shared),
    recent: buildWindowSlice(recentEvents, 'recent', now, shared),
    session: options.currentSessionId
      ? buildWindowSlice(sessionEvents, 'session', now, shared)
      : null,
    discovery,
  };
};
