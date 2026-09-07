import http from 'node:http';
import { readFile } from 'node:fs/promises';
import { provider } from './provider/innerTubeProvider.mjs';
import { discoveryGraphStore } from './discovery/DiscoveryGraphStore.mjs';

// Render injects PORT; MOODIFY_SERVER_PORT is the local dev override
const port = Number(process.env.PORT || process.env.MOODIFY_SERVER_PORT || 8787);
const requestTimeoutMs = 20000;
const maxAttempts = 2;
const defaultRelayChunkBytes = 1024 * 1024;

const smokeHtml = await readFile(new URL('./smoke.html', import.meta.url), 'utf8');

const diagnostics = (event, data = {}) => {
  if (process.env.NODE_ENV !== 'production') {
    console.info(JSON.stringify({ event, at: new Date().toISOString(), ...data }));
  }
};

const requestHeaderSnapshot = (request) => ({
  method: request.method,
  path: new URL(request.url, `http://${request.headers.host || 'localhost'}`).pathname,
  range: request.headers.range || null,
  userAgent: request.headers['user-agent'] || null,
  accept: request.headers.accept || null,
  origin: request.headers.origin || null,
  referer: request.headers.referer || null,
  mediaHeaders: Object.fromEntries(Object.entries(request.headers).filter(([key]) => ['if-range', 'cache-control', 'accept-encoding', 'sec-fetch-dest', 'sec-fetch-mode', 'sec-fetch-site'].includes(key))),
});

const responseHeaderSnapshot = (response) => ({
  status: response.status,
  contentType: response.headers.get('content-type'),
  contentLength: response.headers.get('content-length'),
  contentRange: response.headers.get('content-range'),
  acceptRanges: response.headers.get('accept-ranges'),
  crossOriginResourcePolicy: response.headers.get('cross-origin-resource-policy'),
  location: response.headers.get('location'),
});

const sourceUrlSnapshot = (sourceUrl) => {
  const parsed = new URL(sourceUrl);
  return {
    host: parsed.host,
    path: parsed.pathname,
    queryKeys: [...parsed.searchParams.keys()].sort(),
    itag: parsed.searchParams.get('itag'),
    contentLength: parsed.searchParams.get('clen'),
    expiresAt: parsed.searchParams.get('expire'),
  };
};

const parseRange = (header, totalLength) => {
  if (!header) return { start: 0, end: Math.min(totalLength - 1, defaultRelayChunkBytes - 1), partial: true };
  const match = /^bytes=(\d*)-(\d*)$/.exec(header);
  if (!match || (!match[1] && !match[2])) return null;
  const start = match[1] ? Number(match[1]) : Math.max(0, totalLength - Number(match[2]));
  const end = match[2] ? Number(match[2]) : Math.min(totalLength - 1, start + defaultRelayChunkBytes - 1);
  if (!Number.isSafeInteger(start) || !Number.isSafeInteger(end) || start < 0 || end < start || start >= totalLength) return null;
  return { start, end: Math.min(end, totalLength - 1), partial: true };
};

const toUpstreamRange = (header, totalLength) => {
  if (!header) return `bytes=0-${Math.min(totalLength - 1, defaultRelayChunkBytes - 1)}`;
  const match = /^bytes=(\d*)-(\d*)$/.exec(header);
  if (!match || (!match[1] && !match[2])) return null;
  const start = match[1] ? Number(match[1]) : Math.max(0, totalLength - Number(match[2]));
  const requestedEnd = match[2] ? Number(match[2]) : start + defaultRelayChunkBytes - 1;
  if (!Number.isSafeInteger(start) || !Number.isSafeInteger(requestedEnd) || start < 0 || requestedEnd < start || start >= totalLength) return null;
  return `bytes=${start}-${Math.min(requestedEnd, totalLength - 1)}`;
};

const sendJson = (response, status, payload) => {
  if (response.headersSent || response.writableEnded || response.destroyed) return;
  try {
    response.writeHead(status, {
      'content-type': 'application/json; charset=utf-8',
      'cache-control': 'no-store',
      'access-control-allow-origin': '*',
    });
    response.end(JSON.stringify(payload));
  } catch {
    if (!response.destroyed) response.destroy();
  }
};

const abortMediaResponse = (response) => {
  if (response.destroyed || response.writableEnded) return;
  response.destroy();
};

const persistDiscoverySnapshot = async (snapshot) => {
  try {
    const stats = await discoveryGraphStore.record(snapshot);
    return { ...snapshot, persistence: { stored: true, stats } };
  } catch (error) {
    diagnostics('discovery.persist.failed', { code: error.code || 'STORAGE_ERROR', error: error.message });
    return { ...snapshot, persistence: { stored: false, error: 'DISCOVERY_STORAGE_FAILED' } };
  }
};

const relay = async (request, response, id) => {
  try {
    await streamMedia(request, response, id);
  } catch (error) {
    diagnostics('relay.failed', { id, code: error.code, error: error.message, headersSent: response.headersSent });
    if (response.headersSent) {
      abortMediaResponse(response);
      return;
    }
    throw error;
  }
};

const streamMedia = async (request, response, id) => {
  diagnostics('relay.request', { id, request: requestHeaderSnapshot(request) });
  let source = await provider.resolvePlayback(id);
  for (let attempt = 1; attempt <= maxAttempts; attempt += 1) {
    const openEndedRange = !request.headers.range || /^bytes=\d+-$/.test(request.headers.range);
    if (openEndedRange && source.contentLength) {
      const start = request.headers.range ? Number(request.headers.range.match(/^bytes=(\d+)-$/)[1]) : 0;
      if (!Number.isSafeInteger(start) || start < 0 || start >= source.contentLength) {
        response.writeHead(416, { 'content-range': `bytes */${source.contentLength}`, 'access-control-allow-origin': '*' });
        response.end();
        return;
      }
      const end = source.contentLength - 1;
      const firstEnd = Math.min(end, start + defaultRelayChunkBytes - 1);
      diagnostics('relay.upstream.request', { id, range: `bytes=${start}-${firstEnd}`, source: sourceUrlSnapshot(source.url) });
      let firstUpstream = await fetch(source.url, {
        headers: {
          Range: `bytes=${start}-${firstEnd}`,
          'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131 Safari/537.36',
          Referer: 'https://music.youtube.com/',
        },
        signal: AbortSignal.timeout(requestTimeoutMs),
      });
      diagnostics('relay.upstream.response', { id, range: `bytes=${start}-${firstEnd}`, response: responseHeaderSnapshot(firstUpstream) });
      if (firstUpstream.status === 403 && attempt < maxAttempts) {
        source = await provider.resolvePlayback(id, true);
        continue;
      }
      if (!firstUpstream.ok || !firstUpstream.body) {
        throw Object.assign(new Error(`Upstream media request failed with ${firstUpstream.status}`), { code: firstUpstream.status === 403 ? 'SOURCE_FORBIDDEN' : 'NETWORK_ERROR', status: firstUpstream.status });
      }
      const firstChunk = new Uint8Array(await firstUpstream.arrayBuffer());
      response.writeHead(request.headers.range ? 206 : 200, {
        'content-type': source.mimeType,
        'content-length': source.contentLength - start,
        ...(request.headers.range ? { 'content-range': `bytes ${start}-${end}/${source.contentLength}` } : {}),
        'accept-ranges': 'bytes',
        'cache-control': 'no-store',
        'access-control-allow-origin': '*',
        'access-control-expose-headers': 'Content-Length, Content-Range, Accept-Ranges, Content-Type',
      });
      diagnostics('relay.response', { id, response: { status: request.headers.range ? 206 : 200, contentType: source.mimeType, contentLength: source.contentLength - start, contentRange: request.headers.range ? `bytes ${start}-${end}/${source.contentLength}` : null, acceptRanges: 'bytes' } });
      response.write(firstChunk);
      for (let chunkStart = start + firstChunk.byteLength; chunkStart <= end; chunkStart += defaultRelayChunkBytes) {
        const chunkEnd = Math.min(end, chunkStart + defaultRelayChunkBytes - 1);
        diagnostics('relay.upstream.request', { id, range: `bytes=${chunkStart}-${chunkEnd}`, source: sourceUrlSnapshot(source.url) });
        const upstream = await fetch(source.url, {
          headers: {
            Range: `bytes=${chunkStart}-${chunkEnd}`,
            'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131 Safari/537.36',
            Referer: 'https://music.youtube.com/',
          },
          signal: AbortSignal.timeout(requestTimeoutMs),
        });
        diagnostics('relay.upstream.response', { id, range: `bytes=${chunkStart}-${chunkEnd}`, response: responseHeaderSnapshot(upstream) });
        if (upstream.status === 403) {
          diagnostics('relay.source_expired', { id, range: `${chunkStart}-${chunkEnd}` });
          response.destroy();
          return;
        }
        if (!upstream.ok || !upstream.body) throw Object.assign(new Error(`Upstream media request failed with ${upstream.status}`), { code: 'NETWORK_ERROR', status: upstream.status });
        for await (const chunk of upstream.body) response.write(chunk);
      }
      if (!response.writableEnded) response.end();
      diagnostics('relay.completed', { id, status: request.headers.range ? 206 : 200, range: request.headers.range || 'full' });
      return;
    }
    const upstreamRange = toUpstreamRange(request.headers.range, source.contentLength || Number.MAX_SAFE_INTEGER);
    if (!upstreamRange) {
      response.writeHead(416, { 'content-range': `bytes */${source.contentLength || '*'}`, 'access-control-allow-origin': '*' });
      response.end();
      return;
    }
    const upstream = await fetch(source.url, {
      headers: {
        Range: upstreamRange,
      },
      signal: AbortSignal.timeout(requestTimeoutMs),
    });
    diagnostics('relay.upstream.response', { id, range: upstreamRange, response: responseHeaderSnapshot(upstream) });
    if (upstream.status === 403 && attempt < maxAttempts) {
      diagnostics('relay.refreshing_source', { id, status: upstream.status });
      source = await provider.resolvePlayback(id, true);
      continue;
    }
    if (!upstream.ok || !upstream.body) {
      throw Object.assign(new Error(`Upstream media request failed with ${upstream.status}`), { code: upstream.status === 403 ? 'SOURCE_FORBIDDEN' : 'NETWORK_ERROR', status: upstream.status });
    }

    const totalLength = Number(upstream.headers.get('content-range')?.split('/')[1] || upstream.headers.get('content-length'));
    const range = parseRange(request.headers.range, Number.isFinite(totalLength) ? totalLength : Number.MAX_SAFE_INTEGER);
    if (!range) {
      response.writeHead(416, { 'content-range': `bytes */${Number.isFinite(totalLength) ? totalLength : '*'}`, 'access-control-allow-origin': '*' });
      response.end();
      return;
    }

    response.writeHead(upstream.status === 206 ? 206 : 200, {
      'content-type': upstream.headers.get('content-type') || source.mimeType,
      'content-length': upstream.headers.get('content-length') || undefined,
      'content-range': upstream.headers.get('content-range') || undefined,
      'accept-ranges': upstream.headers.get('accept-ranges') || 'bytes',
      'cache-control': 'no-store',
      'access-control-allow-origin': '*',
      'access-control-expose-headers': 'Content-Length, Content-Range, Accept-Ranges, Content-Type',
    });
    diagnostics('relay.response', { id, response: { status: upstream.status, contentType: upstream.headers.get('content-type') || source.mimeType, contentLength: upstream.headers.get('content-length'), contentRange: upstream.headers.get('content-range'), acceptRanges: upstream.headers.get('accept-ranges') || 'bytes' } });
    request.on('close', () => upstream.body.cancel().catch(() => {}));
    for await (const chunk of upstream.body) response.write(chunk);
    response.end();
    diagnostics('relay.completed', { id, status: upstream.status, range: request.headers.range || 'initial' });
    return;
  }
};

const server = http.createServer(async (request, response) => {
  const url = new URL(request.url, `http://${request.headers.host || 'localhost'}`);
  if (request.method === 'OPTIONS') {
    response.writeHead(204, { 'access-control-allow-origin': '*', 'access-control-allow-methods': 'GET, OPTIONS', 'access-control-allow-headers': 'Range, Content-Type' });
    response.end();
    return;
  }
  try {
    if (url.pathname === '/api/health') {
      sendJson(response, 200, { ok: true, provider: 'youtube-music', providerCache: provider.stats(), discoveryGraph: await discoveryGraphStore.stats() });
      return;
    }
    if (url.pathname === '/smoke') {
      response.writeHead(200, { 'content-type': 'text/html; charset=utf-8' });
      response.end(smokeHtml);
      return;
    }
    if (url.pathname === '/api/search') {
      const query = url.searchParams.get('q')?.trim();
      if (!query) return sendJson(response, 400, { error: 'Query is required' });
      const snapshot = await persistDiscoverySnapshot(await provider.getSearch(query));
      const tracks = snapshot.nodes.filter((node) => node.type === 'track' && node.track).map((node) => node.track);
      sendJson(response, 200, {
        tracks,
        discovery: {
          snapshotId: snapshot.snapshotId,
          pageInfo: snapshot.pageInfo,
          persistence: snapshot.persistence,
        },
      });
      return;
    }
    if (request.method === 'GET' && url.pathname === '/api/discovery/suggest') {
      sendJson(response, 200, await provider.getSearchSuggestions(url.searchParams.get('q') || ''));
      return;
    }
    if (request.method === 'GET' && url.pathname === '/api/discovery/search') {
      const snapshot = await provider.getSearch(
        url.searchParams.get('q') || '',
        url.searchParams.get('cursor') || undefined,
        url.searchParams.get('type') || undefined,
      );
      sendJson(response, 200, await persistDiscoverySnapshot(snapshot));
      return;
    }
    if (request.method === 'GET' && url.pathname === '/api/discovery/graph') {
      sendJson(response, 200, await discoveryGraphStore.read());
      return;
    }
    if (request.method === 'GET' && url.pathname === '/api/discovery/explore') {
      sendJson(response, 200, await persistDiscoverySnapshot(await provider.getExplore(url.searchParams.get('cursor') || undefined)));
      return;
    }
    if (request.method === 'GET' && url.pathname === '/api/discovery/home') {
      sendJson(response, 200, await persistDiscoverySnapshot(await provider.getHome(url.searchParams.get('cursor') || undefined)));
      return;
    }
    const discoveryMatch = /^\/api\/discovery\/(artist|album|playlist|related|up-next)\/([^/]+)$/.exec(url.pathname);
    if (request.method === 'GET' && discoveryMatch) {
      const [, type, encodedId] = discoveryMatch;
      const id = decodeURIComponent(encodedId);
      const cursor = url.searchParams.get('cursor') || undefined;
      const snapshot = type === 'artist'
        ? await provider.getArtist(id, cursor)
        : type === 'album'
          ? await provider.getAlbum(id, cursor)
          : type === 'playlist'
            ? await provider.getPlaylist(id, cursor)
            : type === 'related'
              ? await provider.getRelated(id, cursor)
              : await provider.getUpNext(id, cursor);
      sendJson(response, 200, await persistDiscoverySnapshot(snapshot));
      return;
    }
    const lyricsMatch = /^\/api\/tracks\/([^/]+)\/lyrics$/.exec(url.pathname);
    if (request.method === 'GET' && lyricsMatch) {
      const lyrics = await provider.getLyrics(decodeURIComponent(lyricsMatch[1]));
      if (!lyrics) return sendJson(response, 404, { lyrics: null });
      sendJson(response, 200, lyrics);
      return;
    }
    const trackMatch = /^\/api\/tracks\/([^/]+)$/.exec(url.pathname);
    if (request.method === 'GET' && trackMatch) {
      const track = await provider.getTrack(decodeURIComponent(trackMatch[1]));
      if (!track) return sendJson(response, 404, { error: 'Track unavailable' });
      sendJson(response, 200, { track });
      return;
    }
    const mediaMatch = /^\/api\/media\/([^/]+)$/.exec(url.pathname);
    if (request.method === 'GET' && mediaMatch) {
      const videoId = decodeURIComponent(mediaMatch[1]);
      sendJson(response, 200, { ok: true, videoId, provider: 'youtube-music' });
      return;
    }
    sendJson(response, 404, { error: 'Not found' });
  } catch (error) {
    diagnostics('request.failed', { path: url.pathname, code: error.code, error: error.message });
    if (response.headersSent || response.writableEnded || response.destroyed) {
      abortMediaResponse(response);
      return;
    }
    sendJson(response, error.status || 502, { error: error.code || 'PROVIDER_ERROR', message: 'Music service request failed' });
  }
});

// Bind to 0.0.0.0 so Render (and other cloud hosts) can detect the open port
server.listen(port, '0.0.0.0', () => console.log(`Moodify music server listening on http://0.0.0.0:${port}`));
