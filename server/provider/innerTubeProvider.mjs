import { randomUUID } from 'node:crypto';
import { Constants, Innertube, Platform } from 'youtubei.js/web';
import { buildSabrFormat } from 'googlevideo/utils';
import { getPoToken } from './poToken.mjs';
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
import { makeCatalogPage } from './catalogModels.mjs';

Platform.shim.eval = async (data) => new Function(data.output)();

const requestTimeoutMs = 20_000;
const maxAttempts = 2;
const sourceSafetyWindowMs = 30_000;
// youtubei.js does not add the public InnerTube API key when the request is
// rewritten from www.youtube.com to youtubei.googleapis.com.  Google accepts
// the former without the query key, but the latter returns 404 without it.
const INNERTUBE_API_KEY = 'AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8';
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

const itemIsTrack = (item) => Boolean(item?.item_type === 'song' || item?.item_type === 'video' || item?.item_type === 'non_music_track' || item?.video_id || item?.videoId || item?.duration?.seconds);

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
    this.innertubePromises = new Map();
    this.sourceSessions = new Map();
    this.trackCache = new Map();
    this.infoCache = new Map();
    this.continuations = new ContinuationRegistry();
  }

  async getClient(clientType = null) {
    // Music.getInfo() supplies the YTMUSIC request client itself. Reuse the
    // default session so its visitor/session state also matches the PO token.
    if (clientType === 'YTMUSIC') return this.getClient();
    const cacheKey = clientType || 'DEFAULT';
    if (!this.innertubePromises.has(cacheKey)) {
      const promise = Innertube.create({
        ...(clientType ? { client_type: clientType } : {}),
        generate_session_locally: true,
        fetch: async (input, init) => {
        // youtubei.js supplies its adjusted request body through `init`.
        // Reconstructing from `input` alone would reuse the unprocessed body
        // and drop the selected client context.
        const request = new Request(input, init);
        const originalTarget = new URL(request.url);
        const target = new URL(request.url);
        const isYouTubeInnerTube =
          (target.hostname === 'www.youtube.com' || target.hostname === 'music.youtube.com') &&
          target.pathname.startsWith('/youtubei/') &&
          !target.pathname.includes('/att/');

        if (isYouTubeInnerTube) {
          // Render's shared egress is challenged by www.youtube.com. The
          // same InnerTube request is accepted by the Google API hostname;
          // keep the library's client context and deciphering unchanged.
          target.hostname = 'youtubei.googleapis.com';
          if (!target.searchParams.has('key')) target.searchParams.set('key', INNERTUBE_API_KEY);
          // Preserve the client identity headers. BotGuard/attestation and
          // SABR depend on the exact InnerTube client context; reducing this
          // to content-type and user-agent yields a challenge without a WebPO
          // minter and silently limits playback to the cold-start window.
          const headers = new Headers(request.headers);
          headers.set('content-type', request.headers.get('content-type') || 'application/json');
          headers.set('user-agent', request.headers.get('user-agent') || 'Mozilla/5.0');
          headers.delete('host');
          const requestInit = {
            method: request.method,
            headers,
            redirect: request.redirect,
          };
          if (request.method !== 'GET' && request.method !== 'HEAD') {
            requestInit.body = await request.clone().text();
          }
          const response = await globalThis.fetch(new Request(target, requestInit));
          if (response.status !== 404) return response;

          // Some datacenter egress ranges receive a generic 404 from the
          // googleapis hostname even though the same InnerTube request is
          // valid on YouTube's public host. Keep the Google-host path first,
          // then retry the original endpoint without changing the request
          // body or client headers.
          return globalThis.fetch(new Request(originalTarget, requestInit));
        }

          return globalThis.fetch(request);
        },
      });
      this.innertubePromises.set(cacheKey, promise);
      if (!clientType) this.innertubePromise = promise;
    }
    return this.innertubePromises.get(cacheKey);
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

  async resolvePlaybackPoToken(id) {
    if (!id) throw Object.assign(new Error('Track id is required'), { code: 'INVALID_ID', status: 400 });
    const tokenClient = await this.getClient();
    return this.withTimeout(getPoToken(tokenClient, id), 30_000);
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
      catalog: makeCatalogPage({
        surface: 'search',
        title: normalizedQuery,
        sections: searchShelves(search),
        normalizeTrack: toCanonicalTrack,
      }),
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

  async resolvePlayback(id, forceRefresh = false) {
    if (!id) throw Object.assign(new Error('Track id is required'), { code: 'INVALID_ID', status: 400 });

    // Return cached source if still fresh (YouTube CDN URLs last ~6 hours but we cache 5 min to be safe)
    const cached = this.sourceSessions.get(id);
    if (!forceRefresh && cached && Date.now() < cached.expiresAt) {
      return cached.source;
    }

    let poToken;
    try {
      // GVS playback tokens are video-bound and must be minted for every
      // video. Without this token YouTube can return a perfectly valid-looking
      // format URL that later fails with HTTP 403 at googlevideo.com.
      const tokenClient = await this.getClient();
      poToken = await this.withTimeout(getPoToken(tokenClient, id), 30_000);
    } catch (error) {
      diagnostics('playback.po_token.failed', { id, error: error.message });
    }

    const loadBasicInfo = (client) => client.getBasicInfo(
      id,
      poToken ? { po_token: poToken } : undefined,
    );

    // Resolve a normal signed audio format. SABR is intentionally not used
    // here: cloud-hosted requests can receive an attestation-pending response
    // and stall after the initialization segment. This is the same bounded
    // format flow used by native InnerTube clients.
    const playbackAttempts = [
      { name: 'MWEB', load: loadBasicInfo },
      { name: 'WEB', load: loadBasicInfo },
      { name: 'ANDROID', load: loadBasicInfo },
      { name: 'YTMUSIC', load: (client) => client.music.getInfo(id) },
      { name: 'DEFAULT_IOS', clientType: 'IOS', load: loadBasicInfo },
    ];

    let url;
    let bestFormat;
    let selectedClient;
    let selectedClientName;
    let lastPlaybackError;

    for (const attempt of playbackAttempts) {
      let info;
      let candidateClient;
      try {
        candidateClient = await this.getClient(attempt.clientType === undefined ? attempt.name : attempt.clientType);
        info = await this.withRetry(() => this.withTimeout(attempt.load(candidateClient)));
      } catch (error) {
        lastPlaybackError = error;
        continue;
      }

      // A client can be rejected by YouTube while another client still
      // provides usable audio. Record the failure and continue through the
      // fallback chain instead of aborting on the first client.
      if (info.playability_status?.status && info.playability_status.status !== 'OK') {
        lastPlaybackError = Object.assign(
          new Error(info.playability_status.reason || 'Video is not playable'),
          { code: 'PLAYBACK_RESTRICTED', status: 403 },
        );
        continue;
      }

      const allFormats = [
        ...(info.streaming_data?.adaptive_formats || []),
        ...(info.streaming_data?.formats || []),
      ];

      // Audio-only, prefer AAC/m4a for widest Android ExoPlayer compat, then highest bitrate
      const audioFormats = allFormats.filter((f) => f.has_audio && !f.has_video);
      if (!audioFormats.length) continue;

      audioFormats.sort((a, b) => {
        const aM4a = (a.mime_type || '').includes('mp4') || (a.mime_type || '').includes('m4a') ? 1 : 0;
        const bM4a = (b.mime_type || '').includes('mp4') || (b.mime_type || '').includes('m4a') ? 1 : 0;
        if (aM4a !== bM4a) return bM4a - aM4a;
        return (b.bitrate || 0) - (a.bitrate || 0);
      });

      const player = candidateClient.session.player;
      for (const fmt of audioFormats) {
        try {
          // Formats from MWEB/WEB/ANDROID commonly contain signatureCipher
          // rather than url. Calling Player.decipher explicitly handles both
          // forms and also transforms YouTube's n parameter.
          // NOTE: always call decipher even when fmt.url is present — the n
          // throttle parameter must be transformed by the player JavaScript
          // regardless of whether the URL is already deciphered.
          const deciphered = await player?.decipher(fmt.url, fmt.signature_cipher, fmt.cipher) || fmt.url;
          if (deciphered) {
            // Web/MWeb GVS URLs require the WebPO token in the URL. The token
            // is content-bound, so it is safe to use when the media relay
            // fetches the URL later from the same provider session.
            url = poToken && ['MWEB', 'WEB'].includes(attempt.name)
              ? (() => {
                const candidate = new URL(deciphered);
                if (!candidate.searchParams.has('pot')) candidate.searchParams.set('pot', poToken);
                return candidate.toString();
              })()
              : deciphered;
            bestFormat = fmt;
            selectedClient = candidateClient;
            selectedClientName = attempt.name;
            break;
          }
        } catch {
          // Try the next audio format/client.
        }
      }
      if (url) break;

      lastPlaybackError = Object.assign(new Error(`${attempt.name} returned no decipherable audio format`), { code: 'DECIPHER_FAILED', status: 502 });
    }

    if (!url) {
      throw lastPlaybackError || Object.assign(new Error('Could not resolve audio stream URL'), { code: 'DECIPHER_FAILED', status: 502 });
    }

    const source = {
      url,
      mimeType: bestFormat?.mime_type || 'audio/mp4',
      contentLength: bestFormat?.content_length ? Number(bestFormat.content_length) : undefined,
      bitrate: bestFormat?.bitrate,
      userAgent: selectedClient?.session.context.client.userAgent
        || Constants.CLIENTS[selectedClientName]?.USER_AGENT
        || 'Mozilla/5.0',
      referer: selectedClientName === 'YTMUSIC' ? 'https://music.youtube.com/' : 'https://www.youtube.com/',
      clientType: selectedClientName,
      poToken,
      expiresAt: Number(new URL(url).searchParams.get('expire')) * 1000 || undefined,
    };

    // Cache for 5 minutes (YouTube CDN URLs typically last ~6 hours)
    this.sourceSessions.set(id, { source, expiresAt: Date.now() + 5 * 60 * 1000 });
    return source;
  }

  /**
   * Decipher a media URL that was resolved on a client device.
   *
   * The media request must remain on the device: a URL generated from the
   * device's InnerTube session can be rejected when Render fetches it. The
   * backend is only used as a small JavaScript-decipher service for the `n`
   * parameter/signature; it never downloads or relays the media bytes.
   */
  async decipherPlaybackUrl(rawUrl, signatureCipher, cipher) {
    const candidate = rawUrl || signatureCipher || cipher;
    if (!candidate) {
      throw Object.assign(new Error('A YouTube stream URL is required'), { code: 'INVALID_STREAM_URL', status: 400 });
    }

    const encodedMediaUrl = rawUrl || new URLSearchParams(candidate).get('url');
    let mediaUrl;
    try {
      mediaUrl = new URL(encodedMediaUrl || candidate);
    } catch {
      throw Object.assign(new Error('Invalid YouTube stream URL'), { code: 'INVALID_STREAM_URL', status: 400 });
    }

    const isGoogleVideo = mediaUrl.hostname === 'googlevideo.com' || mediaUrl.hostname.endsWith('.googlevideo.com');
    if (!isGoogleVideo) {
      throw Object.assign(new Error('Only YouTube media URLs may be deciphered'), { code: 'INVALID_STREAM_URL', status: 400 });
    }

    // IOS is intentionally used only to load the player JavaScript. The
    // returned URL is still the device-resolved URL, so no server IP is
    // embedded into the media request.
    const client = await this.getClient('IOS');
    const deciphered = await this.withTimeout(
      client.session.player?.decipher(rawUrl, signatureCipher, cipher),
    );
    if (!deciphered) {
      throw Object.assign(new Error('YouTube did not return a deciphered URL'), { code: 'DECIPHER_FAILED', status: 502 });
    }

    return { url: deciphered };
  }

  async resolveSabrPlayback(id) {
    if (!id) throw Object.assign(new Error('Track id is required'), { code: 'INVALID_ID', status: 400 });

    const tokenClient = await this.getClient();
    let poToken;
    try {
      poToken = await this.withTimeout(getPoToken(tokenClient, id), 30_000);
    } catch (error) {
      // A BotGuard challenge can be temporarily unavailable from a cloud
      // egress. Keep trying playback clients; YTMUSIC and some fallback
      // clients can still return usable SABR data without a PO token.
      poToken = undefined;
    }
    const loadBasicInfo = (client) => client.getBasicInfo(id, poToken ? { po_token: poToken } : undefined);
    const playbackAttempts = [
      { name: 'YTMUSIC', load: (client) => client.music.getInfo(id) },
      { name: 'ANDROID', load: loadBasicInfo },
      { name: 'MWEB', load: loadBasicInfo },
      { name: 'WEB', load: loadBasicInfo },
    ];

    let info;
    let selectedClient = 'YTMUSIC';
    let playbackClient;
    let lastPlaybackError;
    for (const attempt of playbackAttempts) {
      try {
        const candidateClient = await this.getClient(attempt.name);
        const candidate = await this.withRetry(() => this.withTimeout(attempt.load(candidateClient)));
        if (candidate.playability_status?.status && candidate.playability_status.status !== 'OK') {
          lastPlaybackError = Object.assign(
            new Error(candidate.playability_status.reason || `${attempt.name} client rejected playback`),
            { code: 'PLAYBACK_RESTRICTED', status: 403 },
          );
          continue;
        }
        const candidateStreaming = candidate.streaming_data;
        const candidateUstreamerConfig = candidate.player_config?.media_common_config
          ?.media_ustreamer_request_config?.video_playback_ustreamer_config;
        if (!candidateStreaming?.server_abr_streaming_url || !candidateUstreamerConfig) {
          lastPlaybackError = Object.assign(
            new Error(`${attempt.name} client did not return SABR playback data`),
            { code: 'SABR_UNAVAILABLE', status: 502 },
          );
          continue;
        }
        info = candidate;
        selectedClient = attempt.name;
        playbackClient = candidateClient;
        break;
      } catch (error) {
        lastPlaybackError = error;
      }
    }

    if (!info) throw lastPlaybackError || Object.assign(new Error('YouTube did not return playable media'), { code: 'PLAYBACK_RESTRICTED', status: 403 });

    const streaming = info.streaming_data;
    const rawStreamingUrl = streaming.server_abr_streaming_url;
    const ustreamerConfig = info.player_config.media_common_config
      .media_ustreamer_request_config.video_playback_ustreamer_config;
    if (!rawStreamingUrl || !ustreamerConfig) {
      throw Object.assign(new Error('YouTube did not return SABR playback data'), { code: 'SABR_UNAVAILABLE', status: 502 });
    }

    // server_abr_streaming_url contains an undeciphered `n` parameter. Sending
    // it unchanged produces the misleading 403 we previously saw from CDN.
    const serverAbrStreamingUrl = await playbackClient.session.player?.decipher(rawStreamingUrl);
    if (!serverAbrStreamingUrl) {
      throw Object.assign(new Error('Could not decipher YouTube SABR URL'), { code: 'SABR_DECIPHER_FAILED', status: 502 });
    }

    const client = playbackClient.session.context.client;
    const clientProfile = Constants.CLIENTS[selectedClient] || Constants.CLIENTS.YTMUSIC;
    const clientName = Constants.CLIENT_NAME_IDS[clientProfile.NAME] || Constants.CLIENT_NAME_IDS[client.clientName];
    const clientInfo = {
      clientName: parseInt(clientName || '67', 10),
      clientVersion: client.clientVersion || clientProfile.VERSION,
      osName: client.osName || clientProfile.OS_NAME || (selectedClient === 'ANDROID' ? 'Android' : undefined),
      osVersion: client.osVersion || clientProfile.OS_VERSION || (selectedClient === 'ANDROID' ? '13' : undefined),
      deviceMake: client.deviceMake,
      deviceModel: client.deviceModel,
      acceptLanguage: client.hl,
      acceptRegion: client.gl,
      androidSdkVersion: client.androidSdkVersion,
      screenWidthPoints: client.screenWidthPoints,
      screenHeightPoints: client.screenHeightPoints,
      screenPixelDensity: client.screenPixelDensity,
      screenDensityFloat: client.screenDensityFloat,
      utcOffsetMinutes: String(client.utcOffsetMinutes),
      timeZone: client.timeZone,
    };
    return {
      clientType: selectedClient,
      serverAbrStreamingUrl,
      ustreamerConfig,
      clientInfo,
      poToken,
      userAgent: client.userAgent || clientProfile.USER_AGENT || 'Mozilla/5.0',
      formats: (streaming.adaptive_formats || []).map(buildSabrFormat),
      durationMs: Number(info.basic_info?.duration || 0) * 1000,
      title: info.basic_info?.title || id,
      mimeType: 'audio/mp4',
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
      catalog: makeCatalogPage({
        surface: 'artist',
        title: text(artist.header?.title) || id,
        sections: artist.sections,
        normalizeTrack: toCanonicalTrack,
      }),
    });
  }

  async getAlbum(id, cursor) {
    const observedAt = Date.now();
    const album = cursor ? await this.continuations.consume(cursor) : await this.request(async () => (await this.getClient()).music.getAlbum(id));
    const provenance = this.provenance('getAlbum', 'album', id, observedAt);
    const albumNode = makeEntityNode({ type: NODE_TYPES.ALBUM, id, title: text(album.header?.title), artworkUrl: toThumbnailUrl(album.header, album), provenance });
    const items = [...(album.contents || []), ...sectionItems(album.sections)];
    return this.collectionSnapshot({
      operation: 'getAlbum',
      surface: 'album',
      sourceId: id,
      parent: albumNode,
      items,
      provenance,
      continuationOwner: album,
      catalog: makeCatalogPage({
        surface: 'album',
        title: text(album.header?.title) || id,
        sections: album.sections,
        primaryItems: album.contents || [],
        normalizeTrack: toCanonicalTrack,
      }),
    });
  }

  async getPlaylist(id, cursor) {
    const observedAt = Date.now();
    const playlist = cursor ? await this.continuations.consume(cursor) : await this.request(async () => (await this.getClient()).music.getPlaylist(id));
    const provenance = this.provenance('getPlaylist', 'playlist', id, observedAt);
    const playlistNode = makeEntityNode({ type: NODE_TYPES.PLAYLIST, id, title: text(playlist.header?.title), artworkUrl: toThumbnailUrl(playlist.header, playlist), provenance });
    const playlistItems = playlist.items || playlist.contents || [];
    return this.collectionSnapshot({
      operation: 'getPlaylist',
      surface: 'playlist',
      sourceId: id,
      parent: playlistNode,
      items: playlistItems,
      provenance,
      continuationOwner: playlist,
      catalog: makeCatalogPage({
        surface: 'playlist',
        title: text(playlist.header?.title) || id,
        sections: playlist.sections,
        primaryItems: playlistItems,
        normalizeTrack: toCanonicalTrack,
      }),
    });
  }

  async getExplore(cursor) {
    const observedAt = Date.now();
    const explore = cursor ? await this.continuations.consume(cursor) : await this.request(async () => (await this.getClient()).music.getExplore());
    const provenance = this.provenance('getExplore', 'explore', 'FEmusic_explore', observedAt);
    const context = makeContextNode({ surface: 'explore', id: 'FEmusic_explore', title: 'YouTube Music Explore', provenance });
    return this.contextSnapshot({
      operation: 'getExplore',
      surface: 'explore',
      sourceId: 'FEmusic_explore',
      context,
      sections: explore.sections || [],
      provenance,
      continuationOwner: null,
      catalog: makeCatalogPage({
        surface: 'explore',
        title: 'Explore',
        sections: explore.sections,
        actions: explore.top_buttons,
        normalizeTrack: toCanonicalTrack,
      }),
    });
  }

  async parseBrowseResponse(client, response, browseId) {
    const root = response?.contents?.item?.() || response?.contents?.[0] || response?.contents;
    const tabs = asList(root?.tabs);
    const tab = tabs.find((entry) => entry?.selected) || tabs[0];
    const content = tab?.content || root?.content || root;
    // Playlist and community pages are returned as TwoColumnBrowseResults.
    // Their header lives in the selected tab, while the actual track shelf
    // lives in secondary_contents. Reading only tab.content made these pages
    // look valid to callers but produced an empty catalog with title "N/A".
    const secondaryContent = root?.secondary_contents || root?.secondaryContents;
    const sections = [
      ...asList(content?.contents || response?.contents),
      ...asList(secondaryContent?.contents),
    ];
    const continuation = content?.continuation || secondaryContent?.continuation;
    const continuationOwner = continuation ? {
      has_continuation: true,
      getContinuation: async () => {
        const nextResponse = await this.request(() => client.actions.execute('/browse', {
          continuation,
          client: 'YTMUSIC',
          parse: true,
        }));
        return this.parseBrowseResponse(client, nextResponse, browseId);
      },
    } : null;
    return {
      title: text(response?.microformat?.title) || text(content?.header?.title) || text(tab?.title) || browseId,
      sections,
      continuationOwner,
    };
  }

  async getBrowse(browseId, params, cursor) {
    if (!browseId) throw Object.assign(new Error('Browse id is required'), { code: 'INVALID_BROWSE_ID', status: 400 });
    const observedAt = Date.now();
    const page = cursor
      ? await this.continuations.consume(cursor)
      : await this.request(async () => {
        const client = await this.getClient();
        const response = await client.actions.execute('/browse', {
          browseId,
          ...(params ? { params } : {}),
          client: 'YTMUSIC',
          parse: true,
        });
        return this.parseBrowseResponse(client, response, browseId);
      });
    const provenance = this.provenance('browse', 'browse', browseId, observedAt);
    const context = makeContextNode({ surface: 'browse', id: browseId, title: page.title || browseId, provenance, metadata: { browseId, params } });
    return this.contextSnapshot({
      operation: 'browse',
      surface: 'browse',
      sourceId: browseId,
      context,
      sections: page.sections || [],
      provenance,
      continuationOwner: page.continuationOwner,
      catalog: makeCatalogPage({
        surface: 'browse',
        title: page.title || browseId,
        sections: page.sections,
        normalizeTrack: toCanonicalTrack,
      }),
    });
  }

  async getCharts(cursor) {
    return this.getBrowse('FEmusic_charts', undefined, cursor);
  }

  async getHome(cursor) {
    const observedAt = Date.now();
    const home = cursor ? await this.continuations.consume(cursor) : await this.request(async () => (await this.getClient()).music.getHomeFeed());
    const provenance = this.provenance('getHomeFeed', 'home', 'FEmusic_home', observedAt);
    const context = makeContextNode({ surface: 'home', id: 'FEmusic_home', title: 'YouTube Music Home', provenance });
    return this.contextSnapshot({
      operation: 'getHomeFeed',
      surface: 'home',
      sourceId: 'FEmusic_home',
      context,
      sections: home.sections || [],
      provenance,
      continuationOwner: home,
      catalog: makeCatalogPage({
        surface: 'home',
        title: 'Home',
        sections: home.sections,
        normalizeTrack: toCanonicalTrack,
      }),
    });
  }

  async getRelated(id, cursor) {
    const observedAt = Date.now();
    const info = await this.getTrackInfo(id);
    const related = cursor ? await this.continuations.consume(cursor) : await this.request(() => info.getRelated());
    const provenance = this.provenance('getRelated', 'related', id, observedAt);
    const parentTrack = await this.getTrack(id);
    const parentNode = parentTrack ? makeTrackNode(parentTrack, provenance) : makeEntityNode({ type: NODE_TYPES.TRACK, id, provenance });
    const relatedItems = sectionItems(related?.contents || related);
    return this.collectionSnapshot({
      operation: 'getRelated',
      surface: 'related',
      sourceId: id,
      parent: parentNode,
      items: relatedItems,
      provenance,
      continuationOwner: related,
      edgeType: EDGE_TYPES.RELATED_TO,
      catalog: makeCatalogPage({ surface: 'related', title: 'Related', primaryItems: relatedItems, normalizeTrack: toCanonicalTrack }),
    });
  }

  async getUpNext(id, cursor) {
    const observedAt = Date.now();
    const info = await this.getTrackInfo(id);
    const panel = cursor ? await this.continuations.consume(cursor) : await this.request(() => info.getUpNext());
    const provenance = this.provenance('getUpNext', 'up-next', id, observedAt);
    const parentTrack = await this.getTrack(id);
    const parentNode = parentTrack ? makeTrackNode(parentTrack, provenance) : makeEntityNode({ type: NODE_TYPES.TRACK, id, provenance });
    const items = (panel?.contents || []).flatMap((item) => item?.primary ? [item.primary] : [item]);
    return this.collectionSnapshot({
      operation: 'getUpNext',
      surface: 'up-next',
      sourceId: id,
      parent: parentNode,
      items,
      provenance,
      continuationOwner: { info, panel },
      edgeType: EDGE_TYPES.QUEUED_AFTER,
      catalog: makeCatalogPage({ surface: 'up-next', title: 'Up next', primaryItems: items, normalizeTrack: toCanonicalTrack }),
    });
  }

  collectionSnapshot({ operation, surface, sourceId, parent, items, provenance, continuationOwner, edgeType = EDGE_TYPES.CONTAINS, catalog }) {
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
    return makeSnapshot({ operation, surface, sourceId, nodes: unique(nodes), edges: unique(edges), observedAt: provenance.observedAt, nextCursor, catalog });
  }

  contextSnapshot({ operation, surface, sourceId, context, sections, provenance, continuationOwner, catalog }) {
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
    return makeSnapshot({ operation, surface, sourceId, nodes: unique(nodes), edges: unique(edges), observedAt: provenance.observedAt, nextCursor, catalog });
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
