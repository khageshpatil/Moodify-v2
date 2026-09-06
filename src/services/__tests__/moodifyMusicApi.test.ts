import { describe, expect, it } from 'vitest';
import { parseDiscoverySnapshot, parseSearchSuggestions, parseTrackLyrics, toDiscoveryPlaybackRequest, toTrack } from '../moodifyMusicApi';

describe('discovery API mapping', () => {
  it('maps search snapshots into tracks and entity collections', () => {
    const parsed = parseDiscoverySnapshot({
      surface: 'search',
      sourceId: 'late night',
      pageInfo: { hasMore: true, nextCursor: 'cursor-1' },
      nodes: [
        { type: 'context', providerId: 'search:late night', title: 'late night' },
        {
          type: 'track',
          track: {
            id: 't1',
            title: 'Track One',
            artist: { name: 'Artist A', id: 'UCa' },
            album: { title: 'Album A', id: 'MPRa' },
            durationMs: 180000,
            artwork: { url: '/a.jpg' },
            provider: 'youtube-music',
          },
        },
        { type: 'artist', providerId: 'UCa', title: 'Artist A' },
        { type: 'album', providerId: 'MPRa', title: 'Album A' },
      ],
    });
    expect(parsed.tracks).toHaveLength(1);
    expect(parsed.tracks[0].artistId).toBe('UCa');
    expect(parsed.tracks[0].albumId).toBe('MPRa');
    expect(parsed.entities.map((entry) => entry.type).sort()).toEqual(['album', 'artist']);
    expect(parsed.nextCursor).toBe('cursor-1');
  });

  it('drops tracks and entities whose titles are internal ids or provider labels', () => {
    const parsed = parseDiscoverySnapshot({
      surface: 'search',
      nodes: [
        { type: 'track', track: { id: 'YW6deNQMoe8', title: 'YW6deNQMoe8', artist: 'Someone' } },
        { type: 'artist', providerId: 'UCx', title: 'UCx' },
        { type: 'artist', providerId: 'UCy', title: 'Roddy Ricch' },
      ],
    });
    expect(parsed.tracks).toHaveLength(0);
    expect(parsed.entities).toEqual([{ id: 'UCy', type: 'artist', title: 'Roddy Ricch', artworkUrl: undefined }]);
  });

  it('stamps search playback with query and position and keeps the result queue', () => {
    const tracks = [
      toTrack({ id: 'a', title: 'A', artist: 'One', durationMs: 1000 })!,
      toTrack({ id: 'b', title: 'B', artist: 'Two', durationMs: 1000 })!,
    ];
    const request = toDiscoveryPlaybackRequest('search', tracks, { id: 'rain songs', position: 1 });
    expect(request?.discoveryContext).toEqual({ type: 'search', id: 'rain songs', position: 1 });
    expect(request?.tracks.map((track) => track.id)).toEqual(['a', 'b']);
    expect(request?.track.id).toBe('b');
  });

  it('keeps readable search suggestions and drops ids', () => {
    expect(parseSearchSuggestions({ suggestions: ['late night', 'Q5lohxkoBLE', 'UCabc123', 'late night'] })).toEqual(['late night']);
    expect(parseTrackLyrics({ lyrics: '  verse one  ' })).toBe('verse one');
    expect(parseTrackLyrics({ lyrics: '' })).toBeNull();
  });

  it('stamps explore, home, related, and up-next without inventing ranking', () => {
    const tracks = [toTrack({ id: 'x', title: 'X', artist: 'Y', durationMs: 1000 })!];
    expect(toDiscoveryPlaybackRequest('explore', tracks, { position: 0 })?.discoveryContext.type).toBe('explore');
    expect(toDiscoveryPlaybackRequest('home', tracks, { position: 0 })?.discoveryContext.type).toBe('home');
    expect(toDiscoveryPlaybackRequest('related', tracks, { sourceTrack: tracks[0] })?.discoveryContext.type).toBe('related');
    expect(toDiscoveryPlaybackRequest('up-next', tracks, { sourceTrack: tracks[0], position: 0 })?.discoveryContext.type).toBe('up-next');
    expect(toDiscoveryPlaybackRequest('artist', tracks, { id: 'UCa', position: 0 })?.discoveryContext).toEqual({ type: 'artist', id: 'UCa', position: 0 });
    expect(toDiscoveryPlaybackRequest('album', tracks, { id: 'MPRa', position: 0 })?.discoveryContext).toEqual({ type: 'album', id: 'MPRa', position: 0 });
  });
});
