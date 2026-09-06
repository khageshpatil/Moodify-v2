import { describe, expect, it } from 'vitest';
import {
  PRODUCTION_PLAYBACK_PATHS,
  buildDiscoveryPlaybackRequest,
  discoveryContextForAlbum,
  discoveryContextForArtist,
  discoveryContextForDirect,
  discoveryContextForExplore,
  discoveryContextForHome,
  discoveryContextForLibrary,
  discoveryContextForPlaylist,
  discoveryContextForRecommendation,
  discoveryContextForRelated,
  discoveryContextForScene,
  discoveryContextForSearch,
  discoveryContextForSurface,
  discoveryContextForUpNext,
  discoveryContextForVibe,
  resolvePlaybackStartIndex,
  trackSourceKey,
} from '../discoveryAttribution';
import { ListeningEventCollector } from '../ListeningEventCollector';
import { ListeningEventStore } from '../ListeningEventStore';
import { normalizeDiscoveryContext, type ListeningEvent, type ListeningSession } from '../listeningTypes';
import { toDiscoveryPlaybackRequest } from '@/services/moodifyMusicApi';
import type { PlaybackSnapshot, PlaybackTrack } from '@/playback/playbackTypes';
import type { Track } from '@/data/mockMusic';

class MemoryStore extends ListeningEventStore {
  events: ListeningEvent[] = [];
  session: ListeningSession | null = null;
  override loadEvents() { return [...this.events]; }
  override append(event: ListeningEvent) { this.events.push(event); return [...this.events]; }
  override loadSession() { return this.session; }
  override saveSession(session: ListeningSession) { this.session = { ...session, tracksPlayed: [...session.tracksPlayed] }; }
}

const makeTrack = (id: string, overrides: Partial<Track> = {}): Track => ({
  id,
  title: `Title ${id}`,
  artist: `Artist ${id}`,
  album: `Album ${id}`,
  albumArt: '',
  duration: 180,
  provider: 'youtube-music',
  providerId: id,
  ...overrides,
});

const playbackTrack = (track: Track): PlaybackTrack => ({
  id: track.id,
  title: track.title,
  artist: track.artist,
  album: track.album,
  albumArt: track.albumArt,
  duration: track.duration,
  provider: track.provider,
  providerId: track.providerId,
});

const playing = (track: PlaybackTrack): PlaybackSnapshot => ({
  status: 'playing',
  currentTrack: track,
  currentTime: 0,
  duration: track.duration || 180,
  buffered: track.duration || 180,
  volume: 70,
  muted: false,
  playbackRate: 1,
  error: null,
});

const attachPlay = (collector: ListeningEventCollector, track: Track, context: ReturnType<typeof discoveryContextForSearch>) => {
  collector.recordPlayIntent(playbackTrack(track), context);
  collector.observe(playing(playbackTrack(track)));
  return collector.getEvents().find((event) => event.type === 'play_started');
};

describe('discovery attribution builders', () => {
  it('covers every production playback path with the expected context type', () => {
    const expected = {
      search: discoveryContextForSearch('late night', 2),
      home: discoveryContextForHome('FEmusic_home', 1),
      explore: discoveryContextForExplore('moodify-discover', 3),
      playlist: discoveryContextForPlaylist('pl-1', 4),
      album: discoveryContextForAlbum('MPRalbum', 0),
      artist: discoveryContextForArtist('UCartist', 1),
      related: discoveryContextForRelated('youtube-music:source'),
      'up-next': discoveryContextForUpNext('youtube-music:source', 2),
      queue: discoveryContextForLibrary('queue', 5),
      favorites: discoveryContextForLibrary('favorites', 0),
      history: discoveryContextForLibrary('history', 1),
      library: discoveryContextForLibrary('recently-played', 2),
      scene: discoveryContextForScene('focus', 0),
      vibe: discoveryContextForVibe('late-train-home', 0),
      direct: discoveryContextForDirect('listen-together'),
      recommendation: discoveryContextForRecommendation('rec-1', 0, 'continue-listening', ['taste-track']),
    } as const;

    for (const entry of PRODUCTION_PLAYBACK_PATHS) {
      expect(expected[entry.path].type).toBe(entry.contextType);
      expect(normalizeDiscoveryContext(expected[entry.path])).toEqual(expected[entry.path]);
    }
  });

  it('keeps related/up-next provenance on sourceTrackKey', () => {
    expect(discoveryContextForRelated('youtube-music:abc')).toEqual({ type: 'related', sourceTrackKey: 'youtube-music:abc' });
    expect(discoveryContextForUpNext('youtube-music:abc', 3)).toEqual({ type: 'up-next', sourceTrackKey: 'youtube-music:abc', position: 3 });
  });

  it('resolves playlist start index from discovery position', () => {
    const tracks = [makeTrack('a'), makeTrack('b'), makeTrack('c')];
    expect(resolvePlaybackStartIndex(tracks, discoveryContextForPlaylist('pl', 2))).toBe(2);
    expect(resolvePlaybackStartIndex(tracks, discoveryContextForPlaylist('pl', 0), tracks[1])).toBe(1);
  });
});

describe('production playback path attribution', () => {
  const cases: Array<{ path: string; context: ReturnType<typeof discoveryContextForSearch> }> = [
    { path: 'search', context: discoveryContextForSearch('query', 1) },
    { path: 'home', context: discoveryContextForHome('FEmusic_home', 0) },
    { path: 'explore', context: discoveryContextForExplore('moodify-discover', 2) },
    { path: 'playlist', context: discoveryContextForPlaylist('pl-9', 3) },
    { path: 'album', context: discoveryContextForAlbum('album-1', 0) },
    { path: 'artist', context: discoveryContextForArtist('artist-1', 1) },
    { path: 'related', context: discoveryContextForRelated('youtube-music:source') },
    { path: 'up-next', context: discoveryContextForUpNext('youtube-music:source', 4) },
    { path: 'queue', context: discoveryContextForLibrary('queue', 0) },
    { path: 'favorites', context: discoveryContextForLibrary('favorites', 1) },
    { path: 'history', context: discoveryContextForLibrary('history', 0) },
    { path: 'library', context: discoveryContextForLibrary('recently-played', 2) },
    { path: 'scene', context: discoveryContextForScene('chill', 0) },
    { path: 'vibe', context: discoveryContextForVibe('late-train-home', 0) },
    { path: 'direct', context: discoveryContextForDirect('deep-link') },
    { path: 'recommendation', context: discoveryContextForRecommendation('rec-1', 0, 'continue-listening', ['taste-track']) },
  ];

  for (const { path, context } of cases) {
    it(`attaches discoveryContext for ${path} → play`, () => {
      const collector = new ListeningEventCollector({ store: new MemoryStore() });
      const track = makeTrack(`${path}-track`);
      const event = attachPlay(collector, track, context);
      expect(event?.discoveryContext).toEqual(context);
    });
  }

  it('does not leak a prior discoveryContext into an unrelated context-free play', () => {
    const collector = new ListeningEventCollector({ store: new MemoryStore() });
    const first = makeTrack('first');
    const second = makeTrack('second');
    attachPlay(collector, first, discoveryContextForSearch('keep me', 0));
    collector.recordPlayIntent(playbackTrack(second));
    collector.observe(playing(playbackTrack(second)));
    const started = collector.getEvents().filter((event) => event.type === 'play_started');
    expect(started[0].discoveryContext).toEqual(discoveryContextForSearch('keep me', 0));
    expect(started[1].discoveryContext).toBeUndefined();
  });

  it('preserves FIFO semantics for repeated same-track play intents', () => {
    const collector = new ListeningEventCollector({ store: new MemoryStore() });
    const track = makeTrack('same');
    collector.recordPlayIntent(playbackTrack(track), discoveryContextForSearch('first'));
    collector.recordPlayIntent(playbackTrack(track), discoveryContextForRelated(trackSourceKey(track)));
    collector.observe(playing(playbackTrack(track)));
    expect(collector.getEvents()[0].discoveryContext).toEqual(discoveryContextForSearch('first'));
  });

  it('builds discovery API play requests for album/artist/related/up-next/home/explore', () => {
    const tracks = [makeTrack('t1'), makeTrack('t2')];
    const source = makeTrack('source');
    expect(toDiscoveryPlaybackRequest('album', tracks, { id: 'MPRalbum', position: 1 })?.discoveryContext)
      .toEqual(discoveryContextForAlbum('MPRalbum', 1));
    expect(toDiscoveryPlaybackRequest('artist', tracks, { id: 'UCartist', position: 0 })?.discoveryContext)
      .toEqual(discoveryContextForArtist('UCartist', 0));
    expect(toDiscoveryPlaybackRequest('related', tracks, { sourceTrack: source })?.discoveryContext)
      .toEqual(discoveryContextForRelated(trackSourceKey(source)));
    expect(toDiscoveryPlaybackRequest('up-next', tracks, { sourceTrack: source, position: 1 })?.discoveryContext)
      .toEqual(discoveryContextForUpNext(trackSourceKey(source), 1));
    expect(toDiscoveryPlaybackRequest('home', tracks, { position: 0 })?.discoveryContext)
      .toEqual(discoveryContextForHome('FEmusic_home', 0));
    expect(toDiscoveryPlaybackRequest('explore', tracks, { position: 2 })?.discoveryContext)
      .toEqual(discoveryContextForExplore('moodify-discover', 2));
    expect(toDiscoveryPlaybackRequest('search', tracks, { id: 'rain', position: 0 })?.discoveryContext)
      .toEqual(discoveryContextForSearch('rain', 0));
    expect(toDiscoveryPlaybackRequest('playlist', tracks, { id: 'PL123', position: 0 })?.discoveryContext)
      .toEqual(discoveryContextForPlaylist('PL123', 0));
  });

  it('normalizes album and artist contexts and rejects unknown types', () => {
    expect(normalizeDiscoveryContext(discoveryContextForAlbum('a1', 2))).toEqual({ type: 'album', id: 'a1', position: 2 });
    expect(normalizeDiscoveryContext(discoveryContextForArtist('ar1'))).toEqual({ type: 'artist', id: 'ar1' });
    expect(normalizeDiscoveryContext({ type: 'recommendation' })).toEqual({ type: 'recommendation' });
    expect(normalizeDiscoveryContext({ type: 'unknown-surface' })).toBeUndefined();
    expect(discoveryContextForSurface('related', { sourceTrackKey: 'youtube-music:x' })).toEqual({
      type: 'related',
      sourceTrackKey: 'youtube-music:x',
    });
    const request = buildDiscoveryPlaybackRequest('scene', makeTrack('s1'), [makeTrack('s1')], discoveryContextForScene('party', 0));
    expect(request.path).toBe('scene');
    expect(request.discoveryContext).toEqual({ type: 'scene', id: 'party', position: 0 });
  });
});
