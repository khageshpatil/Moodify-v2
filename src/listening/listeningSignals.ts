import type { ListeningEvent, ListeningSession, ListeningSignals, ArtistListeningSignals, TrackListeningSignals } from './listeningTypes';
import { identityKey } from './listeningTypes';

const average = (values: number[]) => values.length ? values.reduce((sum, value) => sum + value, 0) / values.length : 0;

export const deriveListeningSignals = (events: ListeningEvent[], sessions: ListeningSession[] = []): ListeningSignals => {
  const tracks: Record<string, TrackListeningSignals> = {};
  const artists: Record<string, ArtistListeningSignals> = {};
  const sessionBuckets: Record<string, { completions: number[]; skips: number; activity: number }> = {};
  const trackArtists: Record<string, string> = {};
  const trackCompletionValues: Record<string, number[]> = {};
  const trackListenValues: Record<string, number[]> = {};

  events.forEach((event) => {
    const key = identityKey(event);
    const current = tracks[key] || (tracks[key] = { ...event, playCount: 0, completionCount: 0, skipCount: 0, earlySkipCount: 0, replayCount: 0, favoriteCount: 0, averageCompletion: 0, averageListenDuration: 0, lastPlayedAt: null });
    const artist = event.artistName;
    if (artist) trackArtists[key] = artist;
    if (event.type === 'play_started') current.playCount += 1;
    if (event.type === 'play_completed') current.completionCount += 1;
    if (event.type === 'skip') { current.skipCount += 1; if (event.earlySkip) current.earlySkipCount += 1; }
    if (event.type === 'replay') current.replayCount += 1;
    if (event.type === 'favorite') current.favoriteCount += 1;
    if (event.type === 'unfavorite') current.favoriteCount = Math.max(0, current.favoriteCount - 1);
    if (event.type === 'play_started') current.lastPlayedAt = Math.max(current.lastPlayedAt || 0, event.timestamp);

    if (event.type === 'play_completed' || event.type === 'skip') {
      const completion = event.completionRatio ?? 0;
      (trackCompletionValues[key] || (trackCompletionValues[key] = [])).push(completion);
      (trackListenValues[key] || (trackListenValues[key] = [])).push(event.listenedSeconds ?? event.positionSeconds ?? 0);
    }

    const session = sessionBuckets[event.sessionId] || (sessionBuckets[event.sessionId] = { completions: [], skips: 0, activity: 0 });
    session.activity += 1;
    if (event.type === 'play_completed') session.completions.push(event.completionRatio ?? 0);
    if (event.type === 'skip') session.skips += 1;
  });

  Object.entries(tracks).forEach(([key, track]) => {
    track.averageCompletion = average(trackCompletionValues[key] || []);
    track.averageListenDuration = average(trackListenValues[key] || []);
  });

  Object.entries(tracks).forEach(([key, track]) => {
    const artist = trackArtists[key];
    if (!artist) return;
    const current = artists[artist] || (artists[artist] = { artist, artistPlayCount: 0, artistCompletionRate: 0, artistSkipRate: 0, artistFavoriteCount: 0, lastPlayedAt: null });
    current.artistPlayCount += track.playCount;
    current.artistCompletionRate += track.completionCount;
    current.artistSkipRate += track.skipCount;
    current.artistFavoriteCount += track.favoriteCount;
    current.lastPlayedAt = Math.max(current.lastPlayedAt || 0, track.lastPlayedAt || 0);
  });
  Object.values(artists).forEach((artist) => {
    artist.artistCompletionRate = artist.artistPlayCount ? artist.artistCompletionRate / artist.artistPlayCount : 0;
    artist.artistSkipRate = artist.artistPlayCount ? artist.artistSkipRate / artist.artistPlayCount : 0;
  });

  const derivedSessions = Object.fromEntries(Object.entries(sessionBuckets).map(([sessionId, bucket]) => [sessionId, {
    sessionId,
    tracksPlayed: sessions.find((session) => session.sessionId === sessionId)?.tracksPlayed.length || 0,
    averageCompletion: average(bucket.completions),
    skipRate: bucket.activity ? bucket.skips / bucket.activity : 0,
  }]));
  return { tracks, artists, sessions: derivedSessions };
};
