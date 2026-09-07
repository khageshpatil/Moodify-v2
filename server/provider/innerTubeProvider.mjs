import { randomUUID } from 'node:crypto';
import { Innertube, Platform } from 'youtubei.js/web';
import {
  EDGE_TYPES,
  NODE_TYPES,
  PROVIDER,
  makeContextNode,
  makeEdge,
  makeEntityNode,
  makeProvenance,
  makeSnapshot,
  makeTrackNode,
  nodeKey,
} from './canonicalModels.mjs';

Platform.shim.eval = async (data) => new Function(data.output)();

const requestTimeoutMs = 20_000;
const maxAttempts = 2;
const sourceSafetyWindowMs = 30_000;
const maxContinuationEntries = 256;
const continuationTtlMs = 10 * 60 * 1000;

const text = (value, depth = 0) => {
  if (value == null || depth > 4) return '';
  if (typeof value === 'string' || typeof value === 'number') return String(value).trim();
  if (Array.isArray(value)) return value.map((entry) => text(entry, depth + 1)).filter(Boolean).join(' ').trim();
  if (typeof value !== 'object') return '';
  if (typeof value.text === 'string') return value.text.trim();
  if (typeof value.simpleText === 'string') return value.simpleText.trim();
  if (Array.isArray(value.runs)) return text(value.runs, depth + 1);
  return '';
};

const collectThumbnails = (value, into, depth = 0) => {
  if (!value || depth > 6) return;
  if (typeof value === 'string') {
    into.push({ url: value, width: 0 });
    return;
  }
  if (Array.isArray(value)) {
    value.forEach((entry) => collectThumbnails(entry, into, depth + 1));
    return;
  }
  if (typeof value !== 'object') return;
  if (typeof value.url === 'string') into.push({ url: value.url, width: Number(value.width) || 0 });
  else collectThumbnails(value.url, into, depth + 1);
  collectThumbnails(value.thumbnail, into, depth + 1);
  collectThumbnails(value.thumbnails, into, depth + 1);
  collectThumbnails(value.contents, into, depth + 1);
  collectThumbnails(value.sources, into, depth + 1);
  collectThumbnails(value.artwork, into, depth + 1);
  collectThumbnails(value.header, into, depth + 1);
  collectThumbnails(value.image, into, depth + 1);
};

const stabilizeArtworkUrl = (url) => {
  let next = String(url || '').trim();
  if (next.startsWith('//')) next = `https:${next}`;
  if (!/^https?:\/\//i.test(next)) return '';
  return next.replace(/=w(\d+)-h(\d+)/, (match, width) => (
    Number(width) > 0 && Number(width) < 120 ? '=w226-h226' : match
  ));
};

const toThumbnailUrl = (...sources) => {
  const found = [];
  sources.forEach((source) => collectThumbnails(source, found));
  const urls = found
    .map((entry) => ({ url: stabilizeArtworkUrl(entry.url), width: entry.width }))
    .filter((entry) => entry.url);
  if (!urls.length) return '';
  const preferred = urls.filter((entry) => entry.width >= 120 && entry.width <= 640);
  const pool = preferred.length ? preferred : urls;
  return [...pool].sort((a, b) => b.width - a.width)[0].url;
};

const toProviderId = (item) => item?.id
  || item?.video_id
  || item?.videoId
  || item?.endpoint?.payload?.videoId
  || item?.endpoint?.payload?.browseId
  || '';

const toArtist = (artist) => {
  if (!artist) return null;
  const name = text(artist.name || artist.title || artist.text);
  if (!name) return null;
  return { id: artist.id || artist.channel_id || artist.endpoint?.payload?.browseId || '', name };
};

const toArtists = (item) => {
  const artists = Array.isArray(item?.artists) ? item.artists : item?.author ? [item.author] : item?.authors || [];
  return artists.map(toArtist).filter(Boolean);
};

const toAlbum = (item) => {
  const album = item?.album;
  if (!album) return null;
  const title = text(album.title || album.name || album.text);
  const id = album.id || album.endpoint?.payload?.browseId || '';
  return title || id ? { id, title } : null;
};

const toDurationSeconds = (item) => {
  const seconds = item?.duration?.seconds ?? item?.duration_seconds ?? item?.length_seconds;
  return Number.isFinite(Number(seconds)) && Number(seconds) > 0 ? Number(seconds) : undefined;
};

const normalizeTrackItem = (item, fallback = {}) => {
  const id = toProviderId(item) || fallback.id;
  const title = text(item?.title || item?.name || fallback.title);
  if (!id || !title || title === id) return null;
  const artists = toArtists(item);
  const artist = artists[0]?.name || text(item?.author || fallback.artist) || undefined;
  const album = toAlbum(item);
  const duration = toDurationSeconds(item) ?? fallback.duration;
  const artworkUrl = toThumbnailUrl(item, item?.thumbnail, item?.thumbnails, fallback.artwork, fallback);
  return {
    id,
    provider: PROVIDER,
    title,
    artist,
    artists: artists.length ? artists : undefined,
    album: album?.title ? { id: album.id || '', title: album.title, provider: PROVIDER } : (typeof fallback.album === 'object' && fallback.album?.title ? fallback.album : undefined),
    durationMs: duration ? duration * 1000 : undefined,
    artwork: artworkUrl || undefined,
    providerMetadata: {
      resultType: item?.item_type || item?.type || fallback.resultType || 'song',
    },
  };
};

const toCanonicalTrack = (item, fallback = {}) => {
  const normalized = normalizeTrackItem(item, fallback);
  if (!normalized) return null;
  return {
    id: normalized.id,
    provider: PROVIDER,
    title: normalized.title,
    artist: normalized.artist,
    artists: normalized.artists || [],
    album: normalized.album,
    durationMs: normalized.durationMs,
    artwork: normalized.artwork ? { url: normalized.artwork } : undefined,
    providerMetadata: normalized.providerMetadata,
  };
};

const itemIsTrack = (item) => Boolean(item?.item_type === 'song' || item?.item_type === 'video' || item?.video_id || item?.videoId || item?.duration?.seconds);

const unique = (items) => [...new Map(items.filter(Boolean).map((item) => [item.key, item])).values()];

const YOUTUBE_VIDEO_ID = /^[A-Za-z0-9_-]{11}$/;
const PROVIDER_BROWSE_ID = /^(UC|MPREb_|MPRE|MPR|VL|PL|RD|OLAK5uy_|FEmusic_)/i;
const PLACEHOLDER_LABELS = new Set([
  'artist', 'album', 'playlist', 'track', 'song', 'video', 'search', 'explore', 'home',
  'youtube music', 'youtubemusic', 'unknown title', 'unknown artist', 'unknown album',
]);

const isPresentableQuery = (value) => {
  const query = text(value).trim();
  if (!query || PLACEHOLDER_LABELS.has(query.toLowerCase())) return false;
  if (query.startsWith('youtube-music:')) return false;
  if (PROVIDER_BROWSE_ID.test(query)) return false;
  if (YOUTUBE_VIDEO_ID.test(query) && !query.includes(' ')) return false;
  return true;
};

const sectionItems = (sections) => (Array.isArray(sections) ? sections : sections ? [sections] : []).flatMap((section) => {
  if (Array.isArray(section)) return section;
  if (Array.isArray(section?.contents)) return section.contents;
  if (Array.isArray(section?.items)) return section.items;
  if (section?.item_type || section?.video_id || section?.videoId || section?.duration?.seconds) return [section];
  return [];
});

const asList = (value) => {
  if (!value) return [];
  if (Array.isArray(value)) return value;
  if (typeof value.toArray === 'function') return value.toArray();
  if (typeof value.length === 'number' || typeof value[Symbol.iterator] === 'function') {
    try { return Array.from(value); } catch { return []; }
  }
  return [];
};

const searchShelves = (search) => {
  const named = [search?.songs, search?.videos, search?.artists, search?.albums, search?.playlists].filter(Boolean);
  const contents = asList(search?.contents);
  const continuation = asList(search?.contents?.contents).length ? [search.contents] : [];
  return [...named, ...contents, ...continuation];
};

const searchPageItems = (search) => {
  const items = searchShelves(search).flatMap((shelf) => {
    const inner = asList(shelf?.contents);
    if (inner.length) return inner;
    const videoId = shelf?.on_tap?.payload?.videoId || shelf?.endpoint?.payload?.videoId;
    if (videoId) {
      return [{
        ...shelf,
        id: videoId,
        video_id: videoId,
        item_type: shelf?.item_type || 'song',
        title: shelf?.title,
      }];
    }
    return [];
  });
  return [...new Map(items.filter(Boolean).map((item) => [toProviderId(item) || item, item])).values()];
};

const suggestionEntries = (sections) => (Array.isArray(sections) ? sections : []).flatMap((section) => (
  Array.isArray(section?.contents) ? section.contents : [section]
));

const entityTypeForItem = (item) => {
  if (item?.item_type === 'artist' || item?.item_type === 'library_artist') return NODE_TYPES.ARTIST;
  if (item?.item_type === 'album') return NODE_TYPES.ALBUM;
  if (item?.item_type === 'playlist' || item?.item_type === 'community_playlist') return NODE_TYPES.PLAYLIST;
  return null;
};

const makeEntityFromItem = (item, type, provenance) => {
  const id = toProviderId(item);
  const title = text(item?.title || item?.name);
  return id && title ? makeEntityNode({
    type,
    id,
    title,
    artworkUrl: toThumbnailUrl(item),
    provenance,
    metadata: {
      itemType: item?.item_type,
      year: item?.year || undefined,
      itemCount: item?.item_count || undefined,
    },
  }) : null;
};

const appendTrackAssociations = (track, trackNode, nodes, edges, provenance) => {
  track.artists?.forEach((artist) => {
    if (!artist.id) return;
    const artistNode = makeEntityNode({ type: NODE_TYPES.ARTIST, id: artist.id, title: artist.name, artworkUrl: toThumbnailUrl(track.artwork), provenance });
    nodes.push(artistNode);
    edges.push(makeEdge({ fromKey: trackNode.key, toKey: artistNode.key, type: EDGE_TYPES.PERFORMED_BY, provenance }));
  });
  if (track.album?.id) {
    const albumNode = makeEntityNode({ type: NODE_TYPES.ALBUM, id: track.album.id, title: track.album.title, artworkUrl: toThumbnailUrl(track.artwork, track.album), provenance });
    nodes.push(albumNode);
    edges.push(makeEdge({ fromKey: trackNode.key, toKey: albumNode.key, type: EDGE_TYPES.BELONGS_TO, provenance }));
  }
};

class ContinuationRegistry {
  constructor() { this.entries = new Map(); }

  register(loader, metadata) {
    this.prune();
    const cursor = `moodify_cursor_${randomUUID()}`;
    this.entries.set(cursor, { loader, metadata, createdAt: Date.now(), lastUsedAt: Date.now() });
    while (this.entries.size > maxContinuationEntries) this.entries.delete(this.entries.keys().next().value);
    return cursor;
  }

  async consume(cursor) {
    this.prune();
    const entry = this.entries.get(cursor);
    if (!entry) throw Object.assign(new Error('Continuation cursor is unknown or expired'), { code: 'CONTINUATION_EXPIRED', status: 400 });
    this.entries.delete(cursor);
    entry.lastUsedAt = Date.now();
    return entry.loader();
  }

  prune() {
    const expiry = Date.now() - continuationTtlMs;
    for (const [cursor, entry] of this.entries) if (entry.lastUsedAt < expiry) this.entries.delete(cursor);
  }
}

export class InnerTubeProviderAdapter {
  constructor() {
    this.innertubePromise = null;
    this.sourceSessions = new Map();
    this.trackCache = new Map();
    this.infoCache = new Map();
    this.continuations = new ContinuationRegistry();
  }

  async getClient() {
    this.innertubePromise ||= Innertube.create({ generate_session_locally: true });
    return this.innertubePromise;
  }

  async withTimeout(promise, timeoutMs = requestTimeoutMs) {
    let timer;
    try {
      return await Promise.race([
        promise,
        new Promise((_, reject) => { timer = setTimeout(() => reject(Object.assign(new Error(`Request timed out after ${timeoutMs}ms`), { code: 'TIMEOUT' })), timeoutMs); }),
      ]);
    } finally { clearTimeout(timer); }
  }

  async withRetry(operation) {
    let lastError;
    for (let attempt = 1; attempt <= maxAttempts; attempt += 1) {
      try { return await operation(attempt); }
      catch (error) { lastError = error; if (attempt < maxAttempts) await new Promise((resolve) => setTimeout(resolve, 250 * attempt)); }
    }
    throw lastError;
  }

  async request(operation) {
    return this.withRetry(() => this.withTimeout(operation()));
  }

  async getTrackInfo(id) {
    if (!id) throw Object.assign(new Error('Track id is required'), { code: 'INVALID_ID', status: 400 });
    if (this.infoCache.has(id)) return this.infoCache.get(id);
    const yt = await this.getClient();
    const info = await this.withRetry(() => this.withTimeout(yt.music.getInfo(id)));
    this.infoCache.set(id, info);
    return info;
  }

  async search(query) {
    const snapshot = await this.getSearch(query);
    return snapshot.nodes.filter((node) => node.type === NODE_TYPES.TRACK && node.track).map((node) => node.track);
  }

  async getSearchSuggestions(query) {
    const normalizedQuery = text(query).trim();
    if (normalizedQuery.length < 2) return { suggestions: [] };
    const yt = await this.getClient();
    const sections = await this.request(() => yt.music.getSearchSuggestions(normalizedQuery));
    const suggestions = [...new Set(suggestionEntries(sections)
      .map((entry) => text(entry?.suggestion || entry?.query || entry))
      .filter(isPresentableQuery))];
    return { suggestions: suggestions.slice(0, 8) };
  }

  async getLyrics(id) {
    if (!id) throw Object.assign(new Error('Track id is required'), { code: 'INVALID_ID', status: 400 });
    let shelf = null;
    try {
      const info = await this.getTrackInfo(id);
      if (typeof info.getLyrics === 'function') {
        shelf = await this.request(() => info.getLyrics());
      }
    } catch {
      shelf = null;
    }
    if (!shelf) {
      try {
        const yt = await this.getClient();
        shelf = await this.request(() => yt.music.getLyrics(id));
      } catch {
        return null;
      }
    }
    const lyrics = text(shelf?.description || shelf?.text || shelf?.lyrics).trim();
    return lyrics ? { id, lyrics } : null;
  }

  async getSearch(query, cursor, searchType) {
    const normalizedQuery = text(query).trim();
    if (!normalizedQuery) throw Object.assign(new Error('Search query is required'), { code: 'INVALID_QUERY', status: 400 });
    const allowedTypes = new Set(['all', 'song', 'video', 'album', 'artist', 'playlist']);
    const type = allowedTypes.has(searchType) ? searchType : 'all';
    const observedAt = Date.now();
    let search = cursor
      ? await this.continuations.consume(cursor)
      : await this.request(async () => (await this.getClient()).music.search(normalizedQuery, { type }));
    if (!cursor && type === 'all' && searchPageItems(search).length === 0) {
      search = await this.request(async () => (await this.getClient()).music.search(normalizedQuery, { type: 'song' }));
    }
    const provenance = this.provenance('search', 'search', normalizedQuery, observedAt);
    const context = makeContextNode({
      surface: 'search',
      id: normalizedQuery,
      title: normalizedQuery,
      provenance,
      metadata: { query: normalizedQuery },
    });
    const nodes = [context];
    const edges = [];
    searchPageItems(search).forEach((item, index) => {
      const entityType = entityTypeForItem(item);
      if (entityType) {
        const entityNode = makeEntityFromItem(item, entityType, provenance);
        if (!entityNode) return;
        nodes.push(entityNode);
        edges.push(makeEdge({ fromKey: context.key, toKey: entityNode.key, type: EDGE_TYPES.RETURNED_FOR, position: index, provenance, metadata: { query: normalizedQuery, shelf: entityType } }));
        return;
      }
      if (!itemIsTrack(item)) return;
      const track = toCanonicalTrack(item);
      if (!track) return;
      const trackNode = makeTrackNode(track, provenance);
      nodes.push(trackNode);
      edges.push(makeEdge({ fromKey: context.key, toKey: trackNode.key, type: EDGE_TYPES.RETURNED_FOR, position: index, provenance, metadata: { query: normalizedQuery } }));
      appendTrackAssociations(track, trackNode, nodes, edges, provenance);
    });
    return makeSnapshot({
      operation: 'search',
      surface: 'search',
      sourceId: normalizedQuery,
      nodes: unique(nodes),
      edges: unique(edges),
      observedAt,
      nextCursor: this.registerSearchContinuation(search, normalizedQuery),
    });
  }

  async getTrack(id) {
    if (this.trackCache.has(id)) return this.trackCache.get(id);
    const info = await this.getTrackInfo(id);
    if (info.playability_status?.status && info.playability_status.status !== 'OK') return null;
    const track = toCanonicalTrack({
      id,
      title: info.basic_info?.title,
      author: info.basic_info?.author,
      artists: info.basic_info?.author ? [{ name: info.basic_info.author, channel_id: info.basic_info.channel_id }] : [],
      duration_seconds: Number(info.basic_info?.duration),
      thumbnails: info.basic_info?.thumbnail,
    }, { id });
    if (track) this.trackCache.set(id, track);
    return track;
  }

  async resolvePlayback(id) {
    if (!id) throw Object.assign(new Error('Track id is required'), { code: 'INVALID_ID', status: 400 });
    return {
      videoId: id,
      provider: 'youtube-music',
    };
  }

  stats() {
    return {
      trackCache: this.trackCache.size,
      sourceCache: this.sourceSessions.size,
      continuationCache: this.continuations.entries.size,
    };
  }

  provenance(operation, surface, sourceId, observedAt) {
    const endpoints = {
      artist: '/browse', album: '/browse', playlist: '/browse', explore: '/browse', home: '/browse',
      related: '/next', 'up-next': '/next',
    };
    return makeProvenance({ operation, surface, sourceId, endpoint: endpoints[surface] || '/browse', observedAt });
  }

  async getArtist(id, cursor) {
    const observedAt = Date.now();
    const artist = cursor ? await this.continuations.consume(cursor) : await this.request(async () => (await this.getClient()).music.getArtist(id));
    const provenance = this.provenance('getArtist', 'artist', id, observedAt);
    const artistNode = makeEntityNode({ type: NODE_TYPES.ARTIST, id, title: text(artist.header?.title), artworkUrl: toThumbnailUrl(artist.header, artist), provenance });
    const items = sectionItems(artist.sections);
    return this.collectionSnapshot({
      operation: 'getArtist',
      surface: 'artist',
      sourceId: id,
      parent: artistNode,
      items,
      provenance,
      continuationOwner: this.artistContinuationOwner(artist),
    });
  }

  async getAlbum(id, cursor) {
    const observedAt = Date.now();
    const album = cursor ? await this.continuations.consume(cursor) : await this.request(async () => (await this.getClient()).music.getAlbum(id));
    const provenance = this.provenance('getAlbum', 'album', id, observedAt);
    const albumNode = makeEntityNode({ type: NODE_TYPES.ALBUM, id, title: text(album.header?.title), artworkUrl: toThumbnailUrl(album.header, album), provenance });
    const items = [...(album.contents || []), ...sectionItems(album.sections)];
    return this.collectionSnapshot({ operation: 'getAlbum', surface: 'album', sourceId: id, parent: albumNode, items, provenance, continuationOwner: album });
  }

  async getPlaylist(id, cursor) {
    const observedAt = Date.now();
    const playlist = cursor ? await this.continuations.consume(cursor) : await this.request(async () => (await this.getClient()).music.getPlaylist(id));
    const provenance = this.provenance('getPlaylist', 'playlist', id, observedAt);
    const playlistNode = makeEntityNode({ type: NODE_TYPES.PLAYLIST, id, title: text(playlist.header?.title), artworkUrl: toThumbnailUrl(playlist.header, playlist), provenance });
    return this.collectionSnapshot({ operation: 'getPlaylist', surface: 'playlist', sourceId: id, parent: playlistNode, items: playlist.items || playlist.contents || [], provenance, continuationOwner: playlist });
  }

  async getExplore(cursor) {
    const observedAt = Date.now();
    const explore = cursor ? await this.continuations.consume(cursor) : await this.request(async () => (await this.getClient()).music.getExplore());
    const provenance = this.provenance('getExplore', 'explore', 'FEmusic_explore', observedAt);
    const context = makeContextNode({ surface: 'explore', id: 'FEmusic_explore', title: 'YouTube Music Explore', provenance });
    return this.contextSnapshot({ operation: 'getExplore', surface: 'explore', sourceId: 'FEmusic_explore', context, sections: [...(explore.top_buttons || []), ...(explore.sections || [])], provenance, continuationOwner: null });
  }

  async getHome(cursor) {
    const observedAt = Date.now();
    const home = cursor ? await this.continuations.consume(cursor) : await this.request(async () => (await this.getClient()).music.getHomeFeed());
    const provenance = this.provenance('getHomeFeed', 'home', 'FEmusic_home', observedAt);
    const context = makeContextNode({ surface: 'home', id: 'FEmusic_home', title: 'YouTube Music Home', provenance });
    return this.contextSnapshot({ operation: 'getHomeFeed', surface: 'home', sourceId: 'FEmusic_home', context, sections: home.sections || [], provenance, continuationOwner: home });
  }

  async getRelated(id, cursor) {
    const observedAt = Date.now();
    const info = await this.getTrackInfo(id);
    const related = cursor ? await this.continuations.consume(cursor) : await this.request(() => info.getRelated());
    const provenance = this.provenance('getRelated', 'related', id, observedAt);
    const parentTrack = await this.getTrack(id);
    const parentNode = parentTrack ? makeTrackNode(parentTrack, provenance) : makeEntityNode({ type: NODE_TYPES.TRACK, id, provenance });
    return this.collectionSnapshot({ operation: 'getRelated', surface: 'related', sourceId: id, parent: parentNode, items: sectionItems(related?.contents || related), provenance, continuationOwner: related, edgeType: EDGE_TYPES.RELATED_TO });
  }

  async getUpNext(id, cursor) {
    const observedAt = Date.now();
    const info = await this.getTrackInfo(id);
    const panel = cursor ? await this.continuations.consume(cursor) : await this.request(() => info.getUpNext());
    const provenance = this.provenance('getUpNext', 'up-next', id, observedAt);
    const parentTrack = await this.getTrack(id);
    const parentNode = parentTrack ? makeTrackNode(parentTrack, provenance) : makeEntityNode({ type: NODE_TYPES.TRACK, id, provenance });
    const items = (panel?.contents || []).flatMap((item) => item?.primary ? [item.primary] : [item]);
    return this.collectionSnapshot({ operation: 'getUpNext', surface: 'up-next', sourceId: id, parent: parentNode, items, provenance, continuationOwner: { info, panel }, edgeType: EDGE_TYPES.QUEUED_AFTER });
  }

  collectionSnapshot({ operation, surface, sourceId, parent, items, provenance, continuationOwner, edgeType = EDGE_TYPES.CONTAINS }) {
    const nodes = [parent];
    const edges = [];
    items.forEach((item, index) => {
      if (itemIsTrack(item)) {
        const track = toCanonicalTrack(item);
        if (!track) return;
        const trackNode = makeTrackNode(track, provenance);
        nodes.push(trackNode);
        edges.push(makeEdge({ fromKey: parent.key, toKey: trackNode.key, type: edgeType, position: index, provenance }));
        appendTrackAssociations(track, trackNode, nodes, edges, provenance);
      } else {
        const entityType = entityTypeForItem(item);
        const entityNode = entityType ? makeEntityFromItem(item, entityType, provenance) : null;
        if (entityNode) {
          nodes.push(entityNode);
          edges.push(makeEdge({ fromKey: parent.key, toKey: entityNode.key, type: EDGE_TYPES.CONTAINS, position: index, provenance }));
        }
      }
    });
    const nextCursor = this.registerContinuation(continuationOwner, operation, surface, sourceId);
    return makeSnapshot({ operation, surface, sourceId, nodes: unique(nodes), edges: unique(edges), observedAt: provenance.observedAt, nextCursor });
  }

  contextSnapshot({ operation, surface, sourceId, context, sections, provenance, continuationOwner }) {
    const nodes = [context];
    const edges = [];
    sectionItems(sections).forEach((item, index) => {
      if (itemIsTrack(item)) {
        const track = toCanonicalTrack(item);
        if (!track) return;
        const trackNode = makeTrackNode(track, provenance);
        nodes.push(trackNode);
        edges.push(makeEdge({ fromKey: context.key, toKey: trackNode.key, type: EDGE_TYPES.SURFACED_IN, position: index, provenance }));
        appendTrackAssociations(track, trackNode, nodes, edges, provenance);
      } else {
        const entityType = entityTypeForItem(item);
        const entityNode = entityType ? makeEntityFromItem(item, entityType, provenance) : null;
        if (entityNode) {
          nodes.push(entityNode);
          edges.push(makeEdge({ fromKey: context.key, toKey: entityNode.key, type: EDGE_TYPES.SURFACED_IN, position: index, provenance }));
        }
      }
    });
    const nextCursor = this.registerContinuation(continuationOwner, operation, surface, sourceId);
    return makeSnapshot({ operation, surface, sourceId, nodes: unique(nodes), edges: unique(edges), observedAt: provenance.observedAt, nextCursor });
  }

  artistContinuationOwner(artist) {
    if (!artist) return null;
    if (typeof artist.getContinuation === 'function' && artist.has_continuation) return artist;
    if (typeof artist.getAllSongs !== 'function') return null;
    return {
      has_continuation: true,
      getContinuation: async () => {
        const shelf = await artist.getAllSongs();
        return { header: artist.header, sections: [{ contents: shelf?.contents || shelf?.items || [] }] };
      },
    };
  }

  registerSearchContinuation(search, sourceId) {
    if (!search) return undefined;
    if (typeof search.getContinuation === 'function' && search.has_continuation) {
      return this.continuations.register(() => this.request(() => search.getContinuation()), { operation: 'search', surface: 'search', sourceId });
    }
    if (typeof search.getMore === 'function') {
      const moreShelf = search.songs?.endpoint
        ? search.songs
        : searchShelves(search).find((shelf) => shelf?.endpoint && asList(shelf.contents).some(itemIsTrack));
      if (moreShelf?.endpoint) {
        return this.continuations.register(() => this.request(() => search.getMore(moreShelf)), { operation: 'search', surface: 'search', sourceId });
      }
    }
    if (search.contents?.continuation && typeof search.getContinuation === 'function') {
      return this.continuations.register(() => this.request(() => search.getContinuation()), { operation: 'search', surface: 'search', sourceId });
    }
    return undefined;
  }

  registerContinuation(owner, operation, surface, sourceId) {
    if (!owner) return undefined;
    if (typeof owner.getContinuation === 'function' && owner.has_continuation) return this.continuations.register(() => this.request(() => owner.getContinuation()), { operation, surface, sourceId });
    if (owner.info && owner.panel?.continuation && typeof owner.info.getUpNextContinuation === 'function') return this.continuations.register(() => this.request(() => owner.info.getUpNextContinuation(owner.panel)), { operation, surface, sourceId });
    return undefined;
  }
}

export const provider = new InnerTubeProviderAdapter();
