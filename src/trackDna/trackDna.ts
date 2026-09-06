import type { Track } from '@/data/mockMusic';
import type { PlaybackTrack } from '@/playback/playbackTypes';
import { identityKey, toListeningIdentity } from '@/listening/listeningTypes';
import type {
  TrackDna,
  TrackDnaDerived,
  TrackDnaIdentity,
  TrackDnaObservable,
} from './trackDnaTypes';
import { TRACK_DNA_SCHEMA_VERSION } from './trackDnaTypes';

export type TrackDnaInput = Pick<Track, 'id' | 'title' | 'artist' | 'album' | 'duration'> & {
  artistId?: string;
  artists?: Array<{ id?: string; name: string }>;
  albumId?: string;
  albumArt?: string;
  provider?: string;
  providerId?: string;
  providerResultType?: string;
};

const versionMarkerPatterns: Array<[string, RegExp]> = [
  ['acoustic', /\bacoustic\b/i],
  ['instrumental', /\binstrumental\b/i],
  ['karaoke', /\bkaraoke\b/i],
  ['cover', /\bcover(?:ed)?\b/i],
  ['live', /\blive\b/i],
  ['remix', /\bremix(?:ed)?\b/i],
  ['lofi', /\blo[- ]?fi\b/i],
  ['slowed', /\bslowed\b/i],
  ['reverb', /\breverb\b/i],
  ['sped-up', /\bsped[ -]?up\b/i],
  ['nightcore', /\bnightcore\b/i],
  ['edit', /\b(?:radio|extended|club)? ?edit\b/i],
  ['unplugged', /\bunplugged\b/i],
  ['theme', /\btheme\b|\bost\b|soundtrack/i],
];

const normalize = (value = '') => value
  .normalize('NFKC')
  .toLocaleLowerCase()
  .replace(/[^\p{L}\p{N}]+/gu, ' ')
  .trim()
  .replace(/\s+/g, ' ');

const tokens = (value = '') => normalize(value)
  .split(' ')
  .filter((token) => token.length > 1);

const durationBucket = (durationSeconds?: number): TrackDnaDerived['durationBucket'] => {
  if (!durationSeconds || durationSeconds <= 0) return 'unknown';
  if (durationSeconds < 120) return 'short';
  if (durationSeconds < 300) return 'standard';
  if (durationSeconds < 480) return 'long';
  return 'extended';
};

const derive = (identity: TrackDnaIdentity): TrackDnaDerived => {
  const title = normalize(identity.title);
  const artist = normalize(identity.artist);
  const album = normalize(identity.album);
  const sourceText = `${identity.title} ${identity.album || ''}`;
  return {
    normalizedTitle: title,
    normalizedArtist: artist,
    normalizedAlbum: album || undefined,
    titleTokens: tokens(identity.title),
    artistTokens: tokens(identity.artist),
    albumTokens: tokens(identity.album),
    versionMarkers: versionMarkerPatterns.filter(([, pattern]) => pattern.test(sourceText)).map(([marker]) => marker),
    durationBucket: durationBucket(identity.durationSeconds),
  };
};

export const trackDnaKey = (input: Pick<TrackDnaInput, 'id' | 'provider' | 'providerId'>) => identityKey({
  trackId: input.id,
  provider: input.provider || 'unknown',
  providerTrackId: input.providerId || input.id,
});

const toIdentity = (input: TrackDnaInput): TrackDnaIdentity => ({
  trackId: input.id,
  provider: input.provider || 'unknown',
  providerTrackId: input.providerId || input.id,
  title: input.title,
  artist: input.artist,
  album: input.album || undefined,
  durationSeconds: input.duration > 0 ? input.duration : undefined,
});

const toObservable = (input: TrackDnaInput): TrackDnaObservable => ({
  artistId: input.artistId,
  artists: input.artists?.length ? input.artists : undefined,
  albumId: input.albumId,
  artworkUrl: input.albumArt || undefined,
  providerResultType: input.providerResultType,
});

export const buildTrackDna = (input: TrackDnaInput, generatedAt = Date.now()): TrackDna => {
  const identity = toIdentity(input);
  return {
    schemaVersion: TRACK_DNA_SCHEMA_VERSION,
    key: trackDnaKey(input),
    generatedAt,
    identity,
    observable: toObservable(input),
    derived: derive(identity),
    inferred: {},
  };
};

export const buildTrackDnaFromPlaybackTrack = (track: PlaybackTrack, generatedAt = Date.now()) => buildTrackDna({
  id: track.id,
  title: track.title,
  artist: track.artist,
  album: track.album || '',
  duration: track.duration || 0,
  provider: track.provider,
  providerId: track.providerId,
  albumArt: track.albumArt,
}, generatedAt);

export const trackDnaKeyFromEvent = (event: Parameters<typeof identityKey>[0]) => identityKey(event);

export const trackDnaKeyFromPlaybackTrack = (track: PlaybackTrack) => trackDnaKey({
  id: track.id,
  provider: track.provider,
  providerId: track.providerId,
});

export const toTrackDnaInput = (track: Track): TrackDnaInput => track;

