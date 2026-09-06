import { describe, expect, it } from 'vitest';
import { buildTrackDna, trackDnaKeyFromEvent } from '../trackDna';
import { TrackDnaStore } from '../TrackDnaStore';

const input = {
  id: 'song-1',
  title: '  Midnight Rain (Acoustic) ',
  artist: 'The Example',
  album: 'Quiet Rooms',
  duration: 214,
  provider: 'youtube-music',
  providerId: 'yt-song-1',
  artistId: 'artist-1',
  albumId: 'album-1',
};

describe('Track DNA', () => {
  it('keeps source facts separate from deterministic derived fields', () => {
    const dna = buildTrackDna(input, 1234);

    expect(dna.identity.title).toBe(input.title);
    expect(dna.observable.artistId).toBe('artist-1');
    expect(dna.derived.normalizedTitle).toBe('midnight rain acoustic');
    expect(dna.derived.versionMarkers).toEqual(['acoustic']);
    expect(dna.derived.durationBucket).toBe('standard');
    expect(dna.inferred).toEqual({});
  });

  it('joins to listening events using the existing provider identity key', () => {
    const dna = buildTrackDna(input);
    expect(dna.key).toBe(trackDnaKeyFromEvent({
      trackId: input.id,
      provider: input.provider,
      providerTrackId: input.providerId,
    }));
  });

  it('bounds persisted records and ignores incompatible schema versions', () => {
    const storage = new Map<string, string>();
    const fakeStorage = {
      getItem: (key: string) => storage.get(key) || null,
      setItem: (key: string, value: string) => storage.set(key, value),
      removeItem: (key: string) => storage.delete(key),
      clear: () => storage.clear(),
      key: (index: number) => [...storage.keys()][index] || null,
      length: 0,
    } as Storage;
    const store = new TrackDnaStore({ maxRecords: 1, storage: fakeStorage });
    const first = buildTrackDna({ ...input, id: 'first', providerId: 'first' }, 1);
    const second = buildTrackDna({ ...input, id: 'second', providerId: 'second' }, 2);
    store.upsert(first);
    store.upsert(second);

    expect(store.get(first.key)).toBeUndefined();
    expect(store.get(second.key)?.identity.trackId).toBe('second');
  });
});
