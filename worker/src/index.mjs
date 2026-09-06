/**
 * Moodify Cloudflare Worker — Main Entry Point
 *
 * Replaces server/index.mjs (Node.js HTTP server) with a Cloudflare Worker
 * that uses the standard Fetch API. All routes are identical to the original
 * server so the frontend requires zero changes.
 *
 * Key differences from the Node.js server:
 *  - Uses Request/Response Web API instead of node:http
 *  - No file system access (smoke.html removed, DiscoveryGraphStore is in-memory)
 *  - Environment variables come from `env` parameter, not process.env
 *  - 302 redirect for /api/media/:id (browser fetches audio directly from YouTube CDN)
 */

import { provider } from './provider/innerTubeProvider.mjs';
import { discoveryGraphStore } from './DiscoveryGraphStore.mjs';

// ─── CORS ────────────────────────────────────────────────────────────────────

const CORS_HEADERS = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Methods': 'GET, OPTIONS',
  'Access-Control-Allow-Headers': 'Range, Content-Type, Accept',
  'Access-Control-Expose-Headers': 'Content-Length, Content-Range, Accept-Ranges, Content-Type',
};

// ─── Helpers ─────────────────────────────────────────────────────────────────

const jsonResponse = (status, payload) =>
  new Response(JSON.stringify(payload), {
    status,
    headers: {
      'Content-Type': 'application/json; charset=utf-8',
      'Cache-Control': 'no-store',
      ...CORS_HEADERS,
    },
  });

const persistDiscoverySnapshot = async (snapshot) => {
  try {
    const stats = await discoveryGraphStore.record(snapshot);
    return { ...snapshot, persistence: { stored: true, stats } };
  } catch (error) {
    return { ...snapshot, persistence: { stored: false, error: 'DISCOVERY_STORAGE_FAILED' } };
  }
};

// ─── Worker fetch handler ─────────────────────────────────────────────────────

export default {
  async fetch(request) {
    const url = new URL(request.url);
    const { pathname } = url;

    // Handle CORS preflight
    if (request.method === 'OPTIONS') {
      return new Response(null, { status: 204, headers: CORS_HEADERS });
    }

    try {
      // ── Health check ─────────────────────────────────────────────────────
      if (pathname === '/api/health') {
        return jsonResponse(200, {
          ok: true,
          provider: 'youtube-music',
          providerCache: provider.stats(),
          discoveryGraph: await discoveryGraphStore.stats(),
        });
      }

      // ── Root smoke ───────────────────────────────────────────────────────
      if (pathname === '/') {
        return new Response('Moodify Worker OK — ' + new Date().toISOString(), {
          status: 200,
          headers: { 'Content-Type': 'text/plain', ...CORS_HEADERS },
        });
      }

      // ── /api/search (simple tracks response, used by frontend search) ────
      if (request.method === 'GET' && pathname === '/api/search') {
        const query = url.searchParams.get('q')?.trim();
        if (!query) return jsonResponse(400, { error: 'Query is required' });
        const snapshot = await persistDiscoverySnapshot(await provider.getSearch(query));
        const tracks = snapshot.nodes
          .filter((node) => node.type === 'track' && node.track)
          .map((node) => node.track);
        return jsonResponse(200, {
          tracks,
          discovery: {
            snapshotId: snapshot.snapshotId,
            pageInfo: snapshot.pageInfo,
            persistence: snapshot.persistence,
          },
        });
      }

      // ── /api/discovery/suggest ───────────────────────────────────────────
      if (request.method === 'GET' && pathname === '/api/discovery/suggest') {
        return jsonResponse(200, await provider.getSearchSuggestions(url.searchParams.get('q') || ''));
      }

      // ── /api/discovery/search (full snapshot with cursor) ────────────────
      if (request.method === 'GET' && pathname === '/api/discovery/search') {
        const snapshot = await provider.getSearch(
          url.searchParams.get('q') || '',
          url.searchParams.get('cursor') || undefined,
          url.searchParams.get('type') || undefined,
        );
        return jsonResponse(200, await persistDiscoverySnapshot(snapshot));
      }

      // ── /api/discovery/graph ─────────────────────────────────────────────
      if (request.method === 'GET' && pathname === '/api/discovery/graph') {
        return jsonResponse(200, await discoveryGraphStore.read());
      }

      // ── /api/discovery/explore ───────────────────────────────────────────
      if (request.method === 'GET' && pathname === '/api/discovery/explore') {
        return jsonResponse(200, await persistDiscoverySnapshot(
          await provider.getExplore(url.searchParams.get('cursor') || undefined),
        ));
      }

      // ── /api/discovery/home ──────────────────────────────────────────────
      if (request.method === 'GET' && pathname === '/api/discovery/home') {
        return jsonResponse(200, await persistDiscoverySnapshot(
          await provider.getHome(url.searchParams.get('cursor') || undefined),
        ));
      }

      // ── /api/discovery/:type/:id ─────────────────────────────────────────
      const discoveryMatch = /^\/api\/discovery\/(artist|album|playlist|related|up-next)\/([^/]+)$/.exec(pathname);
      if (request.method === 'GET' && discoveryMatch) {
        const [, type, encodedId] = discoveryMatch;
        const id = decodeURIComponent(encodedId);
        const cursor = url.searchParams.get('cursor') || undefined;
        const snapshot = type === 'artist' ? await provider.getArtist(id, cursor)
          : type === 'album' ? await provider.getAlbum(id, cursor)
          : type === 'playlist' ? await provider.getPlaylist(id, cursor)
          : type === 'related' ? await provider.getRelated(id, cursor)
          : await provider.getUpNext(id, cursor);
        return jsonResponse(200, await persistDiscoverySnapshot(snapshot));
      }

      // ── /api/tracks/:id/lyrics ───────────────────────────────────────────
      const lyricsMatch = /^\/api\/tracks\/([^/]+)\/lyrics$/.exec(pathname);
      if (request.method === 'GET' && lyricsMatch) {
        const lyrics = await provider.getLyrics(decodeURIComponent(lyricsMatch[1]));
        if (!lyrics) return jsonResponse(404, { lyrics: null });
        return jsonResponse(200, lyrics);
      }

      // ── /api/tracks/:id ──────────────────────────────────────────────────
      const trackMatch = /^\/api\/tracks\/([^/]+)$/.exec(pathname);
      if (request.method === 'GET' && trackMatch) {
        const track = await provider.getTrack(decodeURIComponent(trackMatch[1]));
        if (!track) return jsonResponse(404, { error: 'Track unavailable' });
        return jsonResponse(200, { track });
      }

      // ── /api/media/:id ───────────────────────────────────────────────────
      // Resolves the signed YouTube URL server-side (InnerTube works from any IP),
      // then redirects the browser to fetch audio directly from YouTube CDN.
      // The browser's residential IP is never blocked by YouTube — only datacenter IPs are.
      const mediaMatch = /^\/api\/media\/([^/]+)$/.exec(pathname);
      if (request.method === 'GET' && mediaMatch) {
        const source = await provider.resolvePlayback(decodeURIComponent(mediaMatch[1]));
        return new Response(null, {
          status: 302,
          headers: {
            'Location': source.url,
            'Cache-Control': 'no-store',
            ...CORS_HEADERS,
          },
        });
      }

      return jsonResponse(404, { error: 'Not found' });

    } catch (error) {
      console.error(JSON.stringify({
        event: 'request.failed',
        path: pathname,
        code: error.code,
        error: error.message,
        at: new Date().toISOString(),
      }));
      return jsonResponse(error.status || 502, {
        error: error.code || 'PROVIDER_ERROR',
        message: 'Music service request failed',
      });
    }
  },
};
