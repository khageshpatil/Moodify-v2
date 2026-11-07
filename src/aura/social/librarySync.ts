/**
 * Library sync on the web (docs/social-protocol/sync.md): likes, your
 * playlists and recent plays, end-to-end encrypted, between your devices.
 * The logic is in syncEngine.ts; this file connects it to the player's
 * library, the server and the browser.
 */
import { openRecord, sealRecord, syncItemId } from '@social/client-keys.mjs';
import { base64UrlToBytes, bytesToBase64Url } from '@social/identity.mjs';
import { useEffect, useRef, useState } from 'react';
import type { Track } from '@/data/mockMusic';
import type { HistoryItem, Playlist, SyncedLibrary } from '@/hooks/useCanonicalMusicPlayer';
import { usePlayer } from '../PlayerContext';
import { sharedTrack } from './chatModel';
import { SOCIAL_URL, useSocial } from './SocialProvider';
import { emptySyncState, syncOnce, type LibraryAdapter, type SyncChange, type SyncState, type SyncTransport } from './syncEngine';
import type { WireSharedTrack } from './wire';

/** What the web keeps in sync. Saved albums/artists (Android) are left alone until the web shows them. */
export const WEB_COLLECTIONS = ['like', 'playlist', 'recent'] as const;
export const RECENT_KEY = 'recent:plays';
export const RECENT_MAX = 50;

const trackId = (t: Track) => t.providerId || t.id;

/** A song in the shared wire format (the same as shares and chat). */
export const toWireTrack = (t: Track) => ({
  id: trackId(t), title: t.title, artist: t.artist, album: t.album || undefined, artworkUrl: t.albumArt || undefined,
  durationSeconds: t.duration || undefined, artistId: t.artistId || undefined,
});
const fromWireTrack = (w: unknown): Track | null => sharedTrack(w as WireSharedTrack);

interface WirePlay { track: ReturnType<typeof toWireTrack>; playedAt: number }

const playKey = (p: { track: { id: string }; playedAt: number }) => `${p.track.id}@${p.playedAt}`;

/** Union of two lists of plays, newest first, capped. */
export function mergePlays(local: unknown, remote: unknown): { plays: WirePlay[] } {
  const all = [...((local as { plays?: WirePlay[] })?.plays ?? []), ...((remote as { plays?: WirePlay[] })?.plays ?? [])]
    .filter((p) => p?.track?.id && Number.isFinite(p.playedAt));
  const unique = new Map(all.map((p) => [playKey(p), p]));
  return { plays: [...unique.values()].sort((a, b) => b.playedAt - a.playedAt).slice(0, RECENT_MAX) };
}

/** The web library as sync records, by key. */
export function snapshotLibrary(lib: SyncedLibrary): Map<string, unknown> {
  const out = new Map<string, unknown>();
  for (const t of lib.favorites) out.set(`like:${trackId(t)}`, { track: toWireTrack(t) });
  for (const p of lib.playlists) {
    out.set(`playlist:${p.id}`, {
      name: p.name, createdAt: new Date(p.createdAt).getTime() || 0, pinned: p.pinned || undefined, tracks: p.tracks.map(toWireTrack),
    });
  }
  const plays = [...lib.history]
    .sort((a, b) => new Date(b.playedAt).getTime() - new Date(a.playedAt).getTime())
    .slice(0, RECENT_MAX)
    .map((h) => ({ track: toWireTrack(h.track), playedAt: new Date(h.playedAt).getTime() }));
  if (plays.length) out.set(RECENT_KEY, { plays });
  return out;
}

/** Applies synced changes to a copy of the library. */
export function applyToLibrary(lib: SyncedLibrary, changes: SyncChange[]): Partial<SyncedLibrary> {
  let favorites = lib.favorites;
  let playlists = lib.playlists;
  let history = lib.history;
  let recentlyPlayed = lib.recentlyPlayed;
  for (const ch of changes) {
    if (ch.c === 'like') {
      if (ch.data === null) { favorites = favorites.filter((t) => trackId(t) !== ch.id); continue; }
      const track = fromWireTrack((ch.data as { track?: unknown })?.track);
      if (track && !favorites.some((t) => trackId(t) === trackId(track))) favorites = [...favorites, track];
    } else if (ch.c === 'playlist') {
      if (ch.data === null) { playlists = playlists.filter((p) => p.id !== ch.id); continue; }
      const d = ch.data as { name?: unknown; createdAt?: unknown; pinned?: unknown; tracks?: unknown[] };
      const next: Playlist = {
        id: ch.id,
        name: String(d.name ?? 'Playlist').slice(0, 100),
        createdAt: new Date(Number(d.createdAt) || ch.updatedAt),
        tracks: (Array.isArray(d.tracks) ? d.tracks : []).map(fromWireTrack).filter((t): t is Track => !!t),
        ...(d.pinned === true ? { pinned: true } : {}),
      };
      playlists = playlists.some((p) => p.id === ch.id) ? playlists.map((p) => (p.id === ch.id ? next : p)) : [...playlists, next];
    } else if (ch.c === 'recent' && ch.data) {
      const have = new Set(history.map((h) => `${trackId(h.track)}@${new Date(h.playedAt).getTime()}`));
      const added: HistoryItem[] = [];
      for (const p of (ch.data as { plays?: WirePlay[] }).plays ?? []) {
        const track = fromWireTrack(p.track);
        if (!track || have.has(`${trackId(track)}@${p.playedAt}`)) continue;
        added.push({ track, playedAt: new Date(p.playedAt), duration: 0 });
      }
      if (added.length) {
        history = [...history, ...added].sort((a, b) => new Date(a.playedAt).getTime() - new Date(b.playedAt).getTime());
        const newest = [...history].reverse().map((h) => h.track);
        recentlyPlayed = [...newest, ...recentlyPlayed].filter((t, i, all) => all.findIndex((x) => trackId(x) === trackId(t)) === i).slice(0, 20);
      }
    }
  }
  return { favorites, playlists, history, recentlyPlayed };
}

const stateKey = (code: string) => `aura.sync.${code}`;
const loadState = (code: string): SyncState => {
  try { const raw = localStorage.getItem(stateKey(code)); return raw ? JSON.parse(raw) as SyncState : emptySyncState(); } catch { return emptySyncState(); }
};
const saveState = (code: string, state: SyncState) => { try { localStorage.setItem(stateKey(code), JSON.stringify(state)); } catch { /* full: next run starts over safely */ } };

export interface SyncStatus { syncing: boolean; lastSyncedAt: number | null; error: string | null }

/**
 * Keeps this browser's library in sync while you're signed in: on start,
 * when another device changed something, shortly after you change your
 * library, and when the tab comes back.
 */
export function useLibrarySync(): SyncStatus {
  const social = useSocial();
  const player = usePlayer();
  const [status, setStatus] = useState<SyncStatus>({ syncing: false, lastSyncedAt: null, error: null });
  const library = useRef<SyncedLibrary>(player);
  library.current = { favorites: player.favorites, playlists: player.playlists, history: player.history, recentlyPlayed: player.recentlyPlayed };
  const updateLibrary = useRef(player.updateLibrary);
  updateLibrary.current = player.updateLibrary;
  const run = useRef<{ running: boolean; again: boolean }>({ running: false, again: false });
  const socialRef = useRef(social);
  socialRef.current = social;
  const code = social.registered ? social.code : null;

  const syncNow = useRef(async () => {});
  syncNow.current = async () => {
    const s = socialRef.current;
    if (!code || !SOCIAL_URL || s.code !== code) return;
    if (run.current.running) { run.current.again = true; return; }
    run.current.running = true;
    setStatus((st) => ({ ...st, syncing: true }));
    try {
      do {
        run.current.again = false;
        const keys = await s.syncKeys();
        if (!keys) return;
        const token = await s.getToken();
        const call = async <T,>(method: string, path: string, body?: unknown): Promise<T> => {
          const res = await fetch(`${SOCIAL_URL}${path}`, { method, headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` }, body: body === undefined ? undefined : JSON.stringify(body) });
          if (!res.ok) throw new Error(res.status === 413 ? 'Your synced library is full.' : `Sync failed (${res.status})`);
          return res.json() as Promise<T>;
        };
        const transport: SyncTransport = {
          pull: (after) => call('GET', `/v1/sync?after=${after}`),
          push: (records) => call('PUT', '/v1/sync', { records }),
        };
        const adapter: LibraryAdapter = {
          collections: WEB_COLLECTIONS,
          merge: { recent: mergePlays },
          snapshot: () => snapshotLibrary(library.current),
          apply(changes) {
            const change = (lib: SyncedLibrary) => applyToLibrary(lib, changes);
            library.current = { ...library.current, ...change(library.current) }; // snapshots right after see it
            updateLibrary.current(change);
          },
        };
        const result = await syncOnce({
          adapter, transport, state: loadState(code), onState: (st) => saveState(code, st),
          crypto: {
            itemId: (c, id) => syncItemId(keys.id, c, id),
            async seal(item, plaintext) {
              const sealed = await sealRecord({ encKey: keys.enc, code, item, plaintext });
              return { nonce: bytesToBase64Url(sealed.nonce), ciphertext: bytesToBase64Url(sealed.ciphertext) };
            },
            open: (item, nonce, ciphertext) => openRecord({ encKey: keys.enc, code, item, nonce: base64UrlToBytes(nonce), ciphertext: base64UrlToBytes(ciphertext) }),
          },
        });
        saveState(code, result.state);
        if (import.meta.env.DEV && (result.pulled || result.pushed)) console.debug(`[sync] pulled ${result.pulled}, pushed ${result.pushed}`);
      } while (run.current.again);
      setStatus({ syncing: false, lastSyncedAt: Date.now(), error: null });
    } catch (err) {
      setStatus((st) => ({ ...st, syncing: false, error: err instanceof Error ? err.message : 'Sync failed' }));
    } finally {
      run.current.running = false;
    }
  };

  // On sign-in, when another device changed something, and when the tab comes back.
  useEffect(() => {
    if (!code) return;
    void syncNow.current();
    const onVisible = () => { if (!document.hidden) void syncNow.current(); };
    document.addEventListener('visibilitychange', onVisible);
    const every = setInterval(() => void syncNow.current(), 5 * 60_000);
    return () => { document.removeEventListener('visibilitychange', onVisible); clearInterval(every); };
  }, [code]);
  useEffect(() => { if (code && social.syncSignal) void syncNow.current(); }, [code, social.syncSignal]);

  // Shortly after you change your library.
  const fingerprint = `${player.favorites.length}|${player.playlists.map((p) => `${p.id}:${p.name}:${p.tracks.length}`).join(',')}|${player.history.length}`;
  const first = useRef(true);
  useEffect(() => {
    if (first.current) { first.current = false; return; }
    if (!code) return;
    const t = setTimeout(() => void syncNow.current(), 2000);
    return () => clearTimeout(t);
  }, [fingerprint, code]);

  return status;
}
