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
  async fetch(request, env, ctx) {
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

      // ── /api/diagnose/:id ───────────────────────────────────────────────
      const diagnoseMatch = /^\/api\/diagnose\/([^/]+)$/.exec(pathname);
      if (request.method === 'GET' && diagnoseMatch) {
        const report = await provider.diagnosePlayback(decodeURIComponent(diagnoseMatch[1]));
        return jsonResponse(200, report);
      }

      // ── /api/media/:id ───────────────────────────────────────────────────
      // Under the official YouTube IFrame Player architecture, the backend does
      // NOT proxy, decipher, or redirect YouTube media streams. It returns the
      // videoId metadata for the frontend YouTube IFrame player.
      const mediaMatch = /^\/api\/media\/([^/]+)$/.exec(pathname);
      if (request.method === 'GET' && mediaMatch) {
        const videoId = decodeURIComponent(mediaMatch[1]);
        return jsonResponse(200, { ok: true, videoId, provider: 'youtube-music' });
      }

      // ── /api/stream/:id ──────────────────────────────────────────────────
      // Returns a direct audio stream URL for native clients (Android ExoPlayer).
      // Uses the ANDROID InnerTube client which returns pre-deciphered stream URLs.
      const streamMatch = /^\/api\/stream\/([^/]+)$/.exec(pathname);
      if (request.method === 'GET' && streamMatch) {
        const videoId = decodeURIComponent(streamMatch[1]);
        if (!videoId || !/^[A-Za-z0-9_-]{11}$/.test(videoId)) {
          return jsonResponse(400, { error: 'Invalid video ID' });
        }
        try {
          const info = await provider.getTrackInfo(videoId);
          if (info.playability_status?.status && info.playability_status.status !== 'OK') {
            return jsonResponse(403, {
              error: 'PLAYBACK_RESTRICTED',
              reason: info.playability_status.reason || 'This video cannot be played',
            });
          }
          // Prefer audio-only adaptive formats (itag 140 = m4a 128kbps, 251 = opus 160kbps)
          const formats = info.streaming_data?.adaptive_formats || info.streaming_data?.formats || [];
          const audioFormats = formats.filter((f) => f.mime_type?.startsWith('audio/') && f.url);
          // Sort: prefer higher bitrate, prefer mp4/m4a for Android compatibility
          const sorted = [...audioFormats].sort((a, b) => {
            const aPreferred = a.mime_type?.includes('mp4') || a.mime_type?.includes('m4a') ? 1 : 0;
            const bPreferred = b.mime_type?.includes('mp4') || b.mime_type?.includes('m4a') ? 1 : 0;
            if (aPreferred !== bPreferred) return bPreferred - aPreferred;
            return (b.bitrate || 0) - (a.bitrate || 0);
          });
          const best = sorted[0];
          if (!best?.url) {
            return jsonResponse(502, { error: 'NO_STREAM_URL', message: 'No audio stream URL available for this track' });
          }
          return jsonResponse(200, {
            ok: true,
            videoId,
            url: best.url,
            mimeType: best.mime_type,
            bitrate: best.bitrate,
            durationMs: info.basic_info?.duration ? info.basic_info.duration * 1000 : undefined,
          });
        } catch (streamError) {
          return jsonResponse(502, {
            error: 'STREAM_RESOLUTION_FAILED',
            message: streamError?.message || 'Could not resolve audio stream',
          });
        }
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
        details: error.message || String(error),
        stack: error.stack || null,
      });
    }
  },
};
