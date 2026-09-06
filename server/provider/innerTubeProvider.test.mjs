import assert from 'node:assert/strict';
import test from 'node:test';
import { InnerTubeProviderAdapter } from './innerTubeProvider.mjs';

const song = (id, title) => ({
  item_type: 'song',
  id,
  title,
  artists: [{ name: 'Artist', channel_id: 'UCartist' }],
  album: { id: 'MPRalbum', name: 'Album' },
  duration: { seconds: 180 },
  thumbnails: [{ url: `https://example.test/${id}.jpg` }],
});

test('normalizes a provider playlist into canonical nodes, edges, and a continuation cursor', async () => {
  const adapter = new InnerTubeProviderAdapter();
  const firstPage = {
    header: { title: 'Road Songs' },
    items: [song('track-1', 'Track One')],
    has_continuation: true,
    getContinuation: async () => ({ header: { title: 'Road Songs' }, items: [song('track-2', 'Track Two')], has_continuation: false }),
  };
  adapter.getClient = async () => ({ music: { getPlaylist: async () => firstPage } });

  const first = await adapter.getPlaylist('PLroad');
  assert.equal(first.provider, 'youtube-music');
  assert.equal(first.nodes.find((node) => node.type === 'track')?.track.title, 'Track One');
  assert.ok(first.edges.some((edge) => edge.type === 'contains'));
  assert.ok(first.edges.some((edge) => edge.type === 'performed_by'));
  assert.ok(first.pageInfo.hasMore);

  const second = await adapter.getPlaylist('PLroad', first.pageInfo.nextCursor);
  assert.equal(second.nodes.find((node) => node.type === 'track')?.track.id, 'track-2');
  assert.equal(second.pageInfo.hasMore, false);
});

test('normalizes up-next as a dated directional relationship', async () => {
  const adapter = new InnerTubeProviderAdapter();
  adapter.getTrack = async (id) => ({ id, provider: 'youtube-music', title: 'Current', artist: 'Artist' });
  adapter.getTrackInfo = async () => ({
    getUpNext: async () => ({ contents: [song('track-next', 'Next Track')] }),
  });

  const snapshot = await adapter.getUpNext('current-track');
  const edge = snapshot.edges.find((entry) => entry.type === 'queued_after');
  assert.ok(edge);
  assert.equal(snapshot.nodes.find((node) => node.type === 'track' && node.providerId === 'track-next')?.track.title, 'Next Track');
  assert.equal(edge.provenance.operation, 'getUpNext');
});

test('uses readable provider titles and a stable artwork url instead of ids or nested thumbnail objects', async () => {
  const adapter = new InnerTubeProviderAdapter();
  adapter.getClient = async () => ({ music: { search: async () => ({
    songs: { contents: [{
      item_type: 'song',
      id: 'Q5lohxkoBLE',
      title: { text: 'late at night', toString: () => '[object Object]' },
      artists: [{ name: 'Roddy Ricch', channel_id: 'UCabc' }],
      album: { name: 'LIVE LIFE FAST' },
      duration: { seconds: 194 },
      thumbnails: [
        { url: 'https://lh3.googleusercontent.com/art=w60-h60-l90-rj', width: 60, height: 60 },
        { url: 'https://lh3.googleusercontent.com/art=w544-h544-l90-rj', width: 544, height: 544 },
      ],
    }] },
    has_continuation: false,
  }) } });

  const snapshot = await adapter.getSearch('late night');
  const track = snapshot.nodes.find((node) => node.type === 'track')?.track;
  assert.equal(track.title, 'late at night');
  assert.equal(track.artist, 'Roddy Ricch');
  assert.equal(track.artwork.url, 'https://lh3.googleusercontent.com/art=w544-h544-l90-rj');
  assert.notEqual(track.title, track.id);
});

test('normalizes search into canonical nodes, edges, and a continuation cursor', async () => {
  const adapter = new InnerTubeProviderAdapter();
  const firstPage = {
    songs: { contents: [song('search-track-1', 'Search Track')] },
    has_continuation: true,
    getContinuation: async () => ({ songs: { contents: [song('search-track-2', 'Search Track Two')] }, has_continuation: false }),
  };
  adapter.getClient = async () => ({ music: { search: async () => firstPage } });

  const first = await adapter.getSearch('late night');
  assert.equal(first.nodes.find((node) => node.type === 'context')?.metadata.query, 'late night');
  assert.ok(first.edges.some((edge) => edge.type === 'returned_for'));
  assert.ok(first.edges.some((edge) => edge.type === 'performed_by'));
  assert.ok(first.pageInfo.hasMore);

  const second = await adapter.getSearch('late night', first.pageInfo.nextCursor);
  assert.equal(second.nodes.find((node) => node.type === 'track')?.track.id, 'search-track-2');
  assert.equal(second.pageInfo.hasMore, false);
});

test('ingests locale-titled search shelves without relying on English Songs getters', async () => {
  const adapter = new InnerTubeProviderAdapter();
  adapter.getClient = async () => ({ music: { search: async () => ({
    contents: [
      { title: 'गाने', contents: [song('locale-track', 'Locale Track')] },
      { title: 'कलाकार', contents: [{ item_type: 'artist', id: 'UClocale', title: 'Locale Artist' }] },
    ],
    has_continuation: false,
  }) } });
  const snapshot = await adapter.getSearch('rod wave');
  assert.equal(snapshot.nodes.find((node) => node.type === 'track')?.track.id, 'locale-track');
  assert.equal(snapshot.nodes.find((node) => node.providerId === 'UClocale')?.title, 'Locale Artist');
});

test('includes artist, album, and playlist search shelves as traversable entities', async () => {
  const adapter = new InnerTubeProviderAdapter();
  adapter.getClient = async () => ({ music: { search: async () => ({
    songs: { contents: [song('search-track-1', 'Search Track')] },
    artists: { contents: [{ item_type: 'artist', id: 'UCrich', title: 'Rich Artist' }] },
    albums: { contents: [{ item_type: 'album', id: 'MPRrich', title: 'Rich Album' }] },
    playlists: { contents: [{ item_type: 'playlist', id: 'PLrich', title: 'Rich Playlist' }] },
    has_continuation: false,
  }) } });

  const snapshot = await adapter.getSearch('late night');
  assert.ok(snapshot.nodes.some((node) => node.type === 'artist' && node.title === 'Rich Artist'));
  assert.ok(snapshot.nodes.some((node) => node.type === 'album' && node.title === 'Rich Album'));
  assert.ok(snapshot.nodes.some((node) => node.type === 'playlist' && node.title === 'Rich Playlist'));
});

test('registers unfiltered search continuation through getMore when has_continuation is absent', async () => {
  const adapter = new InnerTubeProviderAdapter();
  const firstPage = {
    songs: { contents: [song('search-track-1', 'Search Track')], endpoint: { path: '/search' } },
    has_continuation: false,
    getMore: async () => ({
      songs: { contents: [song('search-track-2', 'Search Track Two')] },
      has_continuation: false,
    }),
  };
  adapter.getClient = async () => ({ music: { search: async () => firstPage } });
  const first = await adapter.getSearch('late night');
  assert.equal(first.pageInfo.hasMore, true);
  const second = await adapter.getSearch('late night', first.pageInfo.nextCursor);
  assert.equal(second.nodes.find((node) => node.type === 'track')?.track.id, 'search-track-2');
});

test('normalizes search suggestions and drops ids', async () => {
  const adapter = new InnerTubeProviderAdapter();
  adapter.getClient = async () => ({ music: { getSearchSuggestions: async () => ([
    { contents: [{ suggestion: { text: 'late night' } }, { suggestion: 'Q5lohxkoBLE' }, { suggestion: 'UCabc123xyz' }] },
  ]) } });
  const result = await adapter.getSearchSuggestions('la');
  assert.deepEqual(result.suggestions, ['late night']);
});

test('extracts lyrics text and fails closed when empty', async () => {
  const adapter = new InnerTubeProviderAdapter();
  adapter.getTrackInfo = async () => ({ getLyrics: async () => ({ description: { text: 'line one\nline two' } }) });
  const found = await adapter.getLyrics('track-1');
  assert.equal(found.lyrics, 'line one\nline two');
  adapter.getTrackInfo = async () => ({ getLyrics: async () => ({ description: '' }) });
  adapter.getClient = async () => ({ music: { getLyrics: async () => { throw new Error('missing'); } } });
  assert.equal(await adapter.getLyrics('track-1'), null);
});

test('registers artist all-songs continuation and album carousel entities', async () => {
  const adapter = new InnerTubeProviderAdapter();
  adapter.getClient = async () => ({ music: {
    getArtist: async () => ({
      header: { title: 'Artist Page' },
      sections: [
        { contents: [song('artist-track', 'Artist Track')] },
        { contents: [{ item_type: 'album', id: 'MPRmore', title: 'Another Album' }] },
      ],
      getAllSongs: async () => ({ contents: [song('artist-more', 'All Songs Track')] }),
    }),
    getAlbum: async () => ({
      header: { title: 'Album Page' },
      contents: [song('album-track', 'Album Track')],
      sections: [{ contents: [{ item_type: 'album', id: 'MPRrelated', title: 'Related Album' }] }],
    }),
  } });

  const artist = await adapter.getArtist('UCartist');
  assert.ok(artist.nodes.some((node) => node.type === 'album' && node.title === 'Another Album'));
  assert.equal(artist.pageInfo.hasMore, true);
  const more = await adapter.getArtist('UCartist', artist.pageInfo.nextCursor);
  assert.equal(more.nodes.find((node) => node.type === 'track')?.track.id, 'artist-more');

  const album = await adapter.getAlbum('MPRalbum');
  assert.ok(album.nodes.some((node) => node.type === 'album' && node.title === 'Related Album'));
});

test('normalizes artist, album, Explore, Home, and related surfaces without semantic inference', async () => {
  const adapter = new InnerTubeProviderAdapter();
  adapter.getClient = async () => ({ music: {
    getArtist: async () => ({ header: { title: 'Artist Page' }, sections: [{ contents: [song('artist-track', 'Artist Track')] }] }),
    getAlbum: async () => ({ header: { title: 'Album Page' }, contents: [song('album-track', 'Album Track')] }),
    getExplore: async () => ({ top_buttons: [], sections: [{ contents: [song('explore-track', 'Explore Track')] }] }),
    getHomeFeed: async () => ({ sections: [{ contents: [song('home-track', 'Home Track')] }] }),
  } });
  adapter.getTrackInfo = async () => ({ getRelated: async () => ({ contents: [song('related-track', 'Related Track')] }) });
  adapter.getTrack = async (id) => ({ id, provider: 'youtube-music', title: 'Source', artist: 'Artist' });

  const artist = await adapter.getArtist('UCartist');
  const album = await adapter.getAlbum('MPRalbum');
  const explore = await adapter.getExplore();
  const home = await adapter.getHome();
  const related = await adapter.getRelated('source-track');

  assert.equal(artist.nodes.find((node) => node.type === 'track')?.track.id, 'artist-track');
  assert.ok(album.edges.some((edge) => edge.type === 'belongs_to'));
  assert.ok(explore.edges.some((edge) => edge.type === 'surfaced_in'));
  assert.ok(home.edges.some((edge) => edge.type === 'surfaced_in'));
  assert.ok(related.edges.some((edge) => edge.type === 'related_to'));
});
