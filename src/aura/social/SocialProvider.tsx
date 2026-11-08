import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  identityFromSeed, linkCheckWords, syncKeysFromSeed, newLinkKeyPair, open, openLinkedSeed, openOwn, seal, seedToWords, sign, wordsToSeed, type Identity,
} from '@social/client-keys.mjs';
import { base64UrlToBytes, bytesToBase64Url, normalizeCode, signedMessages } from '@social/identity.mjs';
import type { Track } from '@/data/mockMusic';
import { getCanonicalPlaybackEngine } from '@/playback/PlaybackEngine';
import { usePlayer } from '../PlayerContext';
import { clearIdentity, loadIdentity, saveIdentity, updateIdentity } from './identityStore';
import { ClockSync } from './roomSync';
import {
  addMessage, applyReaction, conversations as listConversations, markRead as markThreadRead, newMid, parsePayload, payloads, preview,
  toMessage, updateMessage, type ChatMessage, type ChatThreads, type Conversation, type SharedCollection,
} from './chatModel';
import type { WireFriendPresence, WireInboxMessage } from './wire';

/** moodify-social server; local dev server by default. */
export const SOCIAL_URL = (import.meta.env.VITE_SOCIAL_URL || (import.meta.env.DEV ? 'http://127.0.0.1:8790' : '')).replace(/\/$/, '');

/** A profile picture without a photo: an emoji on a colour, or a song whose cover is the picture. */
export type ProfileAvatar =
  | { kind: 'emoji'; emoji: string; color: number }
  | { kind: 'song'; id: string; title: string; artist: string; artworkUrl: string };
export interface SocialUser { code: string; displayName: string; boxPub: string; since?: number; note?: string | null; avatar?: ProfileAvatar | null }
/** [positionMs] was the position at server time [at] (ms; 0 from older servers). */
export interface Presence { online: boolean; track?: Track; playing: boolean; lastSeenAt?: number | null; positionMs: number; at: number }
export interface SharedItem { id: number; fromCode: string; fromName: string; sentAt: number; track?: Track; collection?: SharedCollection; roomId?: string }
interface Lists { friends: SocialUser[]; incoming: SocialUser[]; outgoing: SocialUser[] }

interface SocialContextValue {
  ready: boolean;
  serverConfigured: boolean;
  code: string | null;
  displayName: string;
  /** Your picture, as the server last confirmed it. */
  avatar: ProfileAvatar | null;
  registered: boolean;
  backedUp: boolean;
  connected: boolean;
  lists: Lists;
  presence: Record<string, Presence>;
  shared: SharedItem[];
  create(displayName: string): Promise<string[]>;
  restore(words: string, displayName?: string): Promise<void>;
  recoveryWords(): Promise<string[]>;
  confirmBackedUp(): Promise<void>;
  rename(name: string): Promise<void>;
  /** Sets your picture; null removes it. */
  setAvatar(avatar: ProfileAvatar | null): Promise<void>;
  deleteAccount(): Promise<void>;
  addFriend(code: string, note?: string): Promise<string>;
  answer(code: string, accept: boolean): Promise<void>;
  remove(code: string): Promise<void>;
  block(code: string): Promise<void>;
  sendTrack(to: SocialUser, track: Track): Promise<void>;
  dismissShared(id: number): void;
  startLink(): Promise<LinkSession>;
  /** A session token for the live connections (listening rooms). */
  getToken(): Promise<string>;
  /** Opens a listening room with you as host; returns its code. */
  createRoom(): Promise<string>;
  sendRoomInvite(to: SocialUser, roomId: string, track?: Track | null): Promise<void>;
  /** Chat (chat.md): conversations by friend code, oldest message first. */
  threads: ChatThreads;
  conversations: Conversation[];
  unreadTotal: number;
  sendText(to: SocialUser, text: string): Promise<void>;
  /** One of your playlists: its name and songs. */
  sendPlaylist(to: SocialUser, name: string, tracks: Track[]): Promise<void>;
  /** Your reaction on a message; the same emoji again removes it. */
  react(peer: string, mid: string, emoji: string): Promise<void>;
  retry(peer: string, mid: string): Promise<void>;
  /** The conversation on screen: it stays read and isn't notified. */
  setActiveThread(code: string | null): void;
  notifications: NotificationPermission | 'unsupported';
  enableNotifications(): Promise<void>;
  /** Server time (ms) by the clock measured over the live connection. */
  serverNow(): number;
  /** Library sync (sync.md): this identity's keys, or null without one. */
  syncKeys(): Promise<{ enc: Uint8Array; id: Uint8Array } | null>;
  /** Goes up when another of your devices changed the synced library. */
  syncSignal: number;
}

export interface LinkSession {
  linkId: string;
  qrText: string;
  words: [string, string];
  /** Resolves when the phone has sent the identity (or rejects on expiry/cancel). */
  done: Promise<void>;
  cancel(): void;
}

const SocialContext = createContext<SocialContextValue | null>(null);
export const useSocial = () => {
  const value = useContext(SocialContext);
  if (!value) throw new Error('useSocial must be used inside <SocialProvider>');
  return value;
};

class SocialError extends Error {
  constructor(message: string, public status = 0, public errorCode = '') { super(message); }
}

async function api<T = unknown>(method: string, path: string, body?: unknown, token?: string): Promise<{ status: number; data: T }> {
  if (!SOCIAL_URL) throw new SocialError("Moodify's social features aren't live yet.");
  let response: Response;
  try {
    response = await fetch(`${SOCIAL_URL}${path}`, {
      method,
      headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch {
    throw new SocialError("Couldn't reach Moodify. Check your connection.");
  }
  const text = await response.text();
  const data = text ? JSON.parse(text) : null;
  if (!response.ok) throw new SocialError(data?.error?.message || `Request failed (${response.status})`, response.status, data?.error?.code);
  return { status: response.status, data };
}

const INBOX_KEY = 'aura.social.inbox';
const DEVICE_KEY = 'aura.social.device';
const chatKey = (code: string) => `aura.social.chat.${code}`;
const cursorKey = (code: string) => `aura.social.cursor.${code}`;
const readJson = <T,>(key: string, fallback: T): T => { try { const raw = localStorage.getItem(key); return raw ? JSON.parse(raw) as T : fallback; } catch { return fallback; } };
const writeJson = (key: string, value: unknown) => { try { localStorage.setItem(key, JSON.stringify(value)); } catch { /* full or blocked: keep it in memory */ } };
const loadInbox = (): SharedItem[] => readJson(INBOX_KEY, []);
/** A random id for this browser's inbox position (chat.md §2). */
function deviceId(): string {
  let id = '';
  try { id = localStorage.getItem(DEVICE_KEY) ?? ''; } catch { /* blocked */ }
  if (!/^[A-Za-z0-9_-]{8,64}$/.test(id)) {
    id = `web-${bytesToBase64Url(crypto.getRandomValues(new Uint8Array(12)))}`;
    try { localStorage.setItem(DEVICE_KEY, id); } catch { /* per session then */ }
  }
  return id;
}
/** Threads saved for [code]; sends a reload interrupted show as not sent. */
const loadThreads = (code: string): ChatThreads => Object.fromEntries(Object.entries(readJson<ChatThreads>(chatKey(code), {}))
  .map(([peer, thread]) => [peer, thread.map((m) => (m.status === 'sending' ? { ...m, status: 'failed' as const } : m))]));
const notificationState = (): NotificationPermission | 'unsupported' => (typeof Notification === 'undefined' ? 'unsupported' : Notification.permission);

const PUSH_SW = '/moodify-sw.js';
const pushKey = (code: string) => `aura.social.push.${code}`;
const PUSH_REFRESH_MS = 7 * 24 * 3600 * 1000;
const pushSupported = () => typeof navigator !== 'undefined' && 'serviceWorker' in navigator && typeof window !== 'undefined' && 'PushManager' in window;

/**
 * Browser notifications while Moodify is closed (chat.md §3): subscribes this
 * browser and tells the server. The pings carry no data. False when the
 * browser or the server doesn't support it.
 */
async function subscribePush(sessionToken: string): Promise<boolean> {
  if (!pushSupported()) return false;
  let publicKey: string;
  try { publicKey = (await api<{ publicKey: string }>('GET', '/v1/push/vapid')).data.publicKey; } catch { return false; }
  await navigator.serviceWorker.register(PUSH_SW);
  const registration = await navigator.serviceWorker.ready;
  let subscription = await registration.pushManager.getSubscription();
  const current = subscription?.options.applicationServerKey;
  if (subscription && current && bytesToBase64Url(new Uint8Array(current)) !== publicKey) {
    await subscription.unsubscribe(); // the server's key changed: start over
    subscription = null;
  }
  subscription ??= await registration.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: base64UrlToBytes(publicKey) as BufferSource });
  await api('PUT', '/v1/push', { device: deviceId(), platform: 'webpush', subscription: subscription.toJSON() }, sessionToken);
  return true;
}

async function unsubscribePush() {
  if (!pushSupported()) return;
  try { await (await (await navigator.serviceWorker.getRegistration(PUSH_SW))?.pushManager.getSubscription())?.unsubscribe(); } catch { /* already gone */ }
}

export function SocialProvider({ children }: { children: ReactNode }) {
  const player = usePlayer();
  const navigate = useNavigate();
  const [ready, setReady] = useState(false);
  const [seed, setSeed] = useState<Uint8Array | null>(null);
  const [identity, setIdentity] = useState<Identity | null>(null);
  const [meta, setMeta] = useState({ displayName: '', registered: false, backedUp: false, avatar: null as ProfileAvatar | null });
  const [lists, setLists] = useState<Lists>({ friends: [], incoming: [], outgoing: [] });
  const [presence, setPresence] = useState<Record<string, Presence>>({});
  const [shared, setShared] = useState<SharedItem[]>(loadInbox);
  const [connected, setConnected] = useState(false);
  const session = useRef<{ token: string; expiresAt: number } | null>(null);
  const socket = useRef<WebSocket | null>(null);
  const listsRef = useRef(lists);
  listsRef.current = lists;
  const [threads, setThreads] = useState<ChatThreads>({});
  const threadsRef = useRef<ChatThreads>({});
  const cursor = useRef(0);
  const activeThread = useRef<string | null>(null);
  const fetching = useRef<{ again: boolean } | null>(null);
  const checkedIn = useRef<string | null>(null);
  const hubClock = useRef(new ClockSync());
  const [notifications, setNotifications] = useState(notificationState);
  const [syncSignal, setSyncSignal] = useState(0);
  const myCode = identity?.code ?? null;

  /** Every chat change goes through here, so the ref, the screen and storage agree. */
  const mutate = useCallback((change: (t: ChatThreads) => ChatThreads) => {
    const next = change(threadsRef.current);
    if (next === threadsRef.current) return;
    threadsRef.current = next;
    setThreads(next);
    if (myCode) writeJson(chatKey(myCode), next);
  }, [myCode]);

  // Each identity has its own conversations and inbox position.
  useEffect(() => {
    const loaded = myCode ? loadThreads(myCode) : {};
    threadsRef.current = loaded;
    setThreads(loaded);
    cursor.current = myCode ? readJson<number>(cursorKey(myCode), 0) : 0;
  }, [myCode]);

  useEffect(() => { localStorage.setItem(INBOX_KEY, JSON.stringify(shared.slice(0, 100))); }, [shared]);

  // Load the stored identity once.
  useEffect(() => {
    void loadIdentity().then(async (stored) => {
      if (stored) {
        setSeed(stored.seed);
        setIdentity(await identityFromSeed(stored.seed));
        setMeta({ displayName: stored.displayName, registered: stored.registered, backedUp: stored.backedUp, avatar: null });
      }
      setReady(true);
    });
  }, []);

  const signIn = useCallback(async (id: Identity, displayName: string): Promise<string> => {
    const now = Date.now() / 1000;
    if (session.current && session.current.expiresAt > now + 60) return session.current.token;
    const signPub = bytesToBase64Url(id.signPub);
    const boxPub = bytesToBase64Url(id.boxPub);
    const challenge = async () => (await api<{ challenge: string }>('POST', '/v1/auth/challenge', {})).data.challenge;
    type SignedIn = { token: string; expiresAt: number; account: { displayName: string; avatar?: ProfileAvatar | null } };
    let result: SignedIn;
    try {
      const c = await challenge();
      result = (await api<SignedIn>('POST', '/v1/auth/verify', { challenge: c, signPub, signature: bytesToBase64Url(await sign(id.signPrivate, signedMessages.login(c))) })).data;
    } catch (err) {
      if (!(err instanceof SocialError) || err.status !== 404) throw err;
      const c = await challenge();
      const signature = bytesToBase64Url(await sign(id.signPrivate, signedMessages.register(c, signPub, boxPub, displayName)));
      result = (await api<SignedIn>('POST', '/v1/accounts', { challenge: c, signPub, boxPub, displayName, signature })).data;
    }
    session.current = { token: result.token, expiresAt: result.expiresAt };
    await updateIdentity({ registered: true, displayName: result.account.displayName });
    setMeta((m) => ({ ...m, registered: true, displayName: result.account.displayName, avatar: result.account.avatar ?? null }));
    return result.token;
  }, []);

  const token = useCallback(async () => {
    if (!identity) throw new SocialError('Create your Moodify code first.');
    return signIn(identity, meta.displayName || 'Moodify listener');
  }, [identity, meta.displayName, signIn]);

  const refresh = useCallback(async () => {
    if (!identity || !SOCIAL_URL) return;
    const t = await token();
    setLists((await api<Lists>('GET', '/v1/friends', undefined, t)).data);
  }, [identity, token]);

  const notify = useCallback((from: SocialUser, message: ChatMessage) => {
    if (notificationState() !== 'granted') return;
    if (!document.hidden && activeThread.current === from.code) return;
    try {
      const n = new Notification(from.displayName, { body: preview(message), tag: `moodify-chat-${from.code}` });
      n.onclick = () => { window.focus(); navigate(`/chat/${from.code}`); n.close(); };
    } catch { /* some browsers only allow notifications from a service worker */ }
  }, [navigate]);

  /** Reads the inbox from this browser's position, 100 at a time (chat.md §2). */
  const fetchInbox = useCallback(async () => {
    if (!identity) return;
    if (fetching.current) { fetching.current.again = true; return; }
    const run = { again: true };
    fetching.current = run;
    try {
      while (run.again) {
        run.again = false;
        const t = await token();
        for (let page = 0; page < 20; page++) {
          const { data } = await api<{ messages: WireInboxMessage[] }>('GET', `/v1/messages?after=${cursor.current}`, undefined, t);
          if (!data.messages.length) {
            // Once per session: make sure the server keeps this browser's messages for it (chat.md §2).
            if (checkedIn.current !== identity.code) {
              await api('POST', '/v1/messages/ack', { upTo: cursor.current, device: deviceId() }, t);
              checkedIn.current = identity.code;
            }
            break;
          }
          checkedIn.current = identity.code;
          let friends = listsRef.current.friends;
          if (data.messages.some((m) => !friends.some((f) => f.code === (m.to ?? m.from)))) {
            const fresh = (await api<Lists>('GET', '/v1/friends', undefined, t)).data;
            setLists(fresh);
            friends = fresh.friends;
          }
          const opened: SharedItem[] = [];
          for (const m of data.messages) {
            const own = !!m.to;
            const friend = friends.find((f) => f.code === (own ? m.to : m.from));
            if (!friend) continue;
            let plain: string;
            try {
              const args = { myBoxPrivate: identity.boxPrivate, myCode: identity.code, theirBoxPub: base64UrlToBytes(friend.boxPub), theirCode: friend.code, nonce: base64UrlToBytes(m.nonce), ciphertext: base64UrlToBytes(m.ciphertext) };
              plain = own ? await openOwn(args) : await open(args);
            } catch { continue; /* not for us, or altered */ }
            const parsed = parsePayload(plain, `srv-${m.id}`);
            if (!parsed) continue;
            if (parsed.type === 'reaction') {
              mutate((th) => applyReaction(th, friend.code, parsed.target, own ? identity.code : friend.code, parsed.emoji));
              continue;
            }
            const message = toMessage(parsed, friend.code, own, m.sentAt, own ? 'sent' : 'received');
            if (!own && activeThread.current === friend.code && !document.hidden) message.read = true;
            const isNew = !threadsRef.current[friend.code]?.some((x) => x.mid === message.mid);
            mutate((th) => addMessage(th, message));
            if (own || !isNew) continue;
            notify(friend, message);
            const card = { id: m.id, fromCode: friend.code, fromName: friend.displayName, sentAt: m.sentAt };
            if (message.kind === 'track') opened.push({ ...card, track: message.track });
            if (message.kind === 'room') opened.push({ ...card, roomId: message.roomId, track: message.track });
            if (message.kind === 'collection') opened.push({ ...card, collection: message.collection });
          }
          if (opened.length) setShared((current) => [...opened.reverse(), ...current].filter((item, i, all) => all.findIndex((x) => x.id === item.id) === i).slice(0, 100));
          const upTo = Math.max(cursor.current, ...data.messages.map((m) => m.id));
          cursor.current = upTo;
          writeJson(cursorKey(identity.code), upTo);
          await api('POST', '/v1/messages/ack', { upTo, device: deviceId() }, t);
          if (data.messages.length < 100) break;
        }
      }
    } finally {
      fetching.current = null;
    }
  }, [identity, token, mutate, notify]);

  /** Seals and posts one message; [record] is the chat message to mark sent or failed. */
  const deliver = useCallback(async (to: SocialUser, wire: string, record?: string) => {
    if (!identity) throw new SocialError('Create your Moodify code first.');
    try {
      const sealed = await seal({ myBoxPrivate: identity.boxPrivate, myCode: identity.code, theirBoxPub: base64UrlToBytes(to.boxPub), theirCode: to.code, plaintext: wire });
      await api('POST', '/v1/messages', { to: to.code, nonce: bytesToBase64Url(sealed.nonce), ciphertext: bytesToBase64Url(sealed.ciphertext) }, await token());
      if (record) mutate((th) => updateMessage(th, to.code, record, { status: 'sent' }));
    } catch (err) {
      if (record) mutate((th) => updateMessage(th, to.code, record, { status: 'failed' }));
      if (err instanceof SocialError && err.errorCode === 'slow_down') throw new SocialError("You're sending a lot. Try again in a little while.");
      throw err;
    }
  }, [identity, token, mutate]);

  /** Shows a message of mine right away, then sends it. */
  const sendRecorded = useCallback(async (to: SocialUser, wire: string) => {
    const parsed = parsePayload(wire, newMid());
    if (!parsed || parsed.type === 'reaction') throw new SocialError("That can't be sent.");
    mutate((th) => addMessage(th, { ...toMessage(parsed, to.code, true, Date.now() / 1000, 'sending'), wire }));
    await deliver(to, wire, parsed.mid);
  }, [deliver, mutate]);

  const friendOrThrow = (code: string) => {
    const friend = listsRef.current.friends.find((f) => f.code === code);
    if (!friend) throw new SocialError("You're no longer friends.");
    return friend;
  };

  // Browser notifications: keep this browser subscribed (refreshed weekly) once you allowed them.
  useEffect(() => {
    if (!identity || !SOCIAL_URL || notificationState() !== 'granted') return;
    const last = readJson<number>(pushKey(identity.code), 0);
    if (Date.now() - last < PUSH_REFRESH_MS) return;
    void token().then(subscribePush).then((ok) => { if (ok) writeJson(pushKey(identity.code), Date.now()); }).catch(() => {});
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [identity, notifications]);

  // A tapped notification in a tab that was already open.
  useEffect(() => {
    if (!pushSupported()) return;
    const onMessage = (event: MessageEvent) => { if (event.data?.type === 'moodify-open' && typeof event.data.url === 'string' && event.data.url.startsWith('/')) navigate(event.data.url); };
    navigator.serviceWorker.addEventListener('message', onMessage);
    return () => navigator.serviceWorker.removeEventListener('message', onMessage);
  }, [navigate]);

  // Live connection while an identity exists and the server is configured.
  useEffect(() => {
    if (!identity || !SOCIAL_URL) return;
    let closed = false;
    let backoff = 2000;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const connect = async () => {
      try {
        const t = await token();
        await refresh();
        await fetchInbox();
        const ws = new WebSocket(`${SOCIAL_URL.replace(/^http/, 'ws')}/v1/live?token=${encodeURIComponent(t)}&device=${deviceId()}`);
        socket.current = ws;
        ws.onopen = () => {
          backoff = 2000; setConnected(true);
          hubClock.current.reset();
          for (let i = 0; i < 5; i++) setTimeout(() => { if (ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify({ v: 1, type: 'ping', body: { t: Date.now() } })); }, i * 200);
        };
        ws.onmessage = (event) => {
          const message = JSON.parse(event.data);
          const body = message.body ?? {};
          if (message.type === 'pong') { hubClock.current.onPong(Number(body.t), Number(message.ts), Date.now()); return; }
          const toPresence = (p: WireFriendPresence): Presence => ({
            online: !!p.online, playing: !!p.presence?.playing, lastSeenAt: p.lastSeenAt,
            positionMs: Number(p.presence?.positionMs) || 0, at: Number(p.presence?.at) || 0,
            track: p.presence ? { id: p.presence.trackId, title: p.presence.title, artist: p.presence.artist, album: '', albumArt: p.presence.artworkUrl, duration: Math.round((p.presence.durationMs ?? 0) / 1000), provider: 'youtube-music', providerId: p.presence.trackId } : undefined,
          });
          if (message.type === 'hello') setPresence(Object.fromEntries((body.friends ?? []).map((f: WireFriendPresence) => [f.code, toPresence(f)])));
          if (message.type === 'presence.friend') setPresence((p) => ({ ...p, [body.code]: toPresence(body) }));
          if (message.type?.startsWith('friend.')) void refresh();
          if (message.type === 'msg.new') void fetchInbox();
          if (message.type === 'sync.changed') setSyncSignal((n) => n + 1);
        };
        ws.onclose = () => {
          setConnected(false);
          socket.current = null;
          if (!closed) { timer = setTimeout(connect, backoff); backoff = Math.min(backoff * 2, 60000); }
        };
      } catch {
        if (!closed) { timer = setTimeout(connect, backoff); backoff = Math.min(backoff * 2, 60000); }
      }
    };
    void connect();
    return () => { closed = true; clearTimeout(timer); socket.current?.close(); socket.current = null; setConnected(false); };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [identity]);

  // Publish "listening now" when the song or play/pause changes, at once after a
  // seek (friends following you jump too), and every 15 s while playing.
  const track = player.currentTrack;
  const playerRef = useRef(player);
  playerRef.current = player;
  const publishPresence = useCallback((seek = false) => {
    const ws = socket.current;
    if (!ws || ws.readyState !== WebSocket.OPEN) return;
    const p = playerRef.current;
    const t = p.currentTrack;
    const id = t?.providerId || t?.id;
    const audio = getCanonicalPlaybackEngine().getAudioElement();
    const body = t && id ? {
      trackId: id, title: t.title, artist: t.artist, artworkUrl: t.albumArt, durationMs: Math.round((t.duration || p.duration || 0) * 1000),
      positionMs: Math.round(audio.currentTime * 1000), playing: p.isPlaying, ...(seek ? { seek: true } : {}),
    } : { cleared: true };
    ws.send(JSON.stringify({ v: 1, type: 'presence.update', body }));
  }, []);
  useEffect(() => { publishPresence(); }, [track?.id, player.isPlaying, connected, publishPresence]);
  useEffect(() => {
    const audio = getCanonicalPlaybackEngine().getAudioElement();
    const onSeeked = () => publishPresence(true);
    audio.addEventListener('seeked', onSeeked);
    const tick = setInterval(() => { if (playerRef.current.isPlaying) publishPresence(); }, 15_000);
    return () => { audio.removeEventListener('seeked', onSeeked); clearInterval(tick); };
  }, [publishPresence]);

  const adopt = useCallback(async (newSeed: Uint8Array, displayName: string, backedUp: boolean) => {
    const id = await identityFromSeed(newSeed);
    await saveIdentity({ seed: newSeed, displayName, registered: false, backedUp });
    session.current = null;
    setSeed(newSeed);
    setMeta({ displayName, registered: false, backedUp, avatar: null });
    setIdentity(id);
    if (SOCIAL_URL) {
      try { await signIn(id, displayName); } catch { /* stays local until reachable */ }
    }
    return id;
  }, [signIn]);

  const value = useMemo<SocialContextValue>(() => ({
    ready,
    serverConfigured: !!SOCIAL_URL,
    code: identity?.code ?? null,
    displayName: meta.displayName,
    avatar: meta.avatar,
    registered: meta.registered,
    backedUp: meta.backedUp,
    connected,
    lists,
    presence,
    shared,
    async create(displayName) {
      const fresh = crypto.getRandomValues(new Uint8Array(32));
      await adopt(fresh, displayName.trim(), false);
      return seedToWords(fresh);
    },
    async restore(words, displayName) {
      const restored = await wordsToSeed(words);
      await adopt(restored, displayName?.trim() || 'Moodify listener', true);
    },
    async recoveryWords() { return seed ? seedToWords(seed) : []; },
    async confirmBackedUp() { await updateIdentity({ backedUp: true }); setMeta((m) => ({ ...m, backedUp: true })); },
    async rename(name) {
      if (meta.registered) await api('PATCH', '/v1/me', { displayName: name }, await token());
      await updateIdentity({ displayName: name });
      setMeta((m) => ({ ...m, displayName: name }));
    },
    async setAvatar(avatar) {
      const { data } = await api<{ account: { avatar: ProfileAvatar | null } }>('PATCH', '/v1/me', { avatar }, await token());
      setMeta((m) => ({ ...m, avatar: data.account.avatar ?? null }));
    },
    async deleteAccount() {
      if (meta.registered) await api('DELETE', '/v1/me', undefined, await token());
      if (identity) { try { localStorage.removeItem(chatKey(identity.code)); localStorage.removeItem(cursorKey(identity.code)); localStorage.removeItem(pushKey(identity.code)); } catch { /* blocked */ } }
      await unsubscribePush();
      await clearIdentity();
      session.current = null;
      setIdentity(null); setSeed(null); setLists({ friends: [], incoming: [], outgoing: [] }); setPresence({}); setShared([]);
      setMeta({ displayName: '', registered: false, backedUp: false, avatar: null });
    },
    async addFriend(raw, note) {
      const code = normalizeCode(raw);
      if (!code) return "That doesn't look like a Moodify code.";
      if (code === identity?.code) return "That's your own code.";
      const { data } = await api<{ status: string; user: { displayName: string } }>('POST', '/v1/friends/requests', { code, note: note || undefined }, await token());
      await refresh();
      return data.status === 'friends' ? `You and ${data.user.displayName} are now friends.` : `Request sent to ${data.user.displayName}.`;
    },
    async answer(code, accept) { await api('POST', `/v1/friends/requests/${code}/${accept ? 'accept' : 'ignore'}`, {}, await token()); await refresh(); },
    async remove(code) { await api('DELETE', `/v1/friends/${code}`, undefined, await token()); await refresh(); },
    async block(code) {
      await api('PUT', `/v1/blocks/${code}`, {}, await token());
      setShared((s) => s.filter((i) => i.fromCode !== code));
      mutate((th) => Object.fromEntries(Object.entries(th).filter(([peer]) => peer !== code)));
      await refresh();
    },
    async sendTrack(to, t) { await sendRecorded(to, payloads.track(t)); },
    dismissShared(id) { setShared((s) => s.filter((item) => item.id !== id)); },
    getToken: token,
    async createRoom() {
      const { data } = await api<{ roomId: string }>('POST', '/v1/rooms', {}, await token());
      return data.roomId;
    },
    async sendRoomInvite(to, roomId, t) { await sendRecorded(to, payloads.room(roomId, t)); },
    threads,
    conversations: listConversations(threads),
    unreadTotal: Object.values(threads).reduce((n, thread) => n + thread.filter((m) => !m.fromMe && !m.read).length, 0),
    async sendPlaylist(to, name, tracks) { await sendRecorded(to, payloads.playlist(name, tracks)); },
    async sendText(to, text) {
      if (!text.trim()) return;
      await sendRecorded(to, payloads.text(text));
    },
    async react(peer, mid, emoji) {
      if (!identity) return;
      const friend = friendOrThrow(peer);
      const before = threadsRef.current[peer]?.find((m) => m.mid === mid)?.reactions[identity.code] ?? '';
      const next = before === emoji ? '' : emoji;
      mutate((th) => applyReaction(th, peer, mid, identity.code, next));
      try { await deliver(friend, payloads.reaction(mid, next)); } catch (err) { mutate((th) => applyReaction(th, peer, mid, identity.code, before)); throw err; }
    },
    async retry(peer, mid) {
      const message = threadsRef.current[peer]?.find((m) => m.mid === mid && m.fromMe);
      if (!message?.wire) return;
      const friend = friendOrThrow(peer);
      mutate((th) => updateMessage(th, peer, mid, { status: 'sending' }));
      await deliver(friend, message.wire, mid);
    },
    setActiveThread(code) {
      activeThread.current = code;
      if (code) mutate((th) => markThreadRead(th, code));
    },
    notifications,
    serverNow: () => hubClock.current.serverNow(Date.now()),
    async syncKeys() { return seed ? syncKeysFromSeed(seed) : null; },
    syncSignal,
    async enableNotifications() {
      if (typeof Notification === 'undefined') return;
      setNotifications(await Notification.requestPermission());
    },
    async startLink() {
      const keys = await newLinkKeyPair();
      const devicePub = bytesToBase64Url(keys.publicKey);
      const { data } = await api<{ linkId: string; expiresIn: number }>('POST', '/v1/link', { devicePub });
      const words = await linkCheckWords(keys.publicKey);
      let cancelled = false;
      const done = (async () => {
        const deadline = Date.now() + data.expiresIn * 1000;
        while (!cancelled && Date.now() < deadline) {
          await new Promise((r) => setTimeout(r, 2000));
          const result = await api<{ senderPub: string; nonce: string; ciphertext: string; status?: string }>('GET', `/v1/link/${data.linkId}/result`);
          if (result.status === 202) continue;
          const linkedSeed = await openLinkedSeed({
            devicePrivate: keys.privateKey, linkId: data.linkId,
            senderPub: base64UrlToBytes(result.data.senderPub), nonce: base64UrlToBytes(result.data.nonce), ciphertext: base64UrlToBytes(result.data.ciphertext),
          });
          await adopt(linkedSeed, 'Moodify listener', true);
          return;
        }
        throw new SocialError(cancelled ? 'Cancelled.' : 'The link code expired. Start again.');
      })();
      return { linkId: data.linkId, qrText: `moodify://link/${data.linkId}?k=${devicePub}`, words, done, cancel: () => { cancelled = true; } };
    },
  }), [ready, identity, meta, connected, lists, presence, shared, seed, adopt, token, refresh, threads, notifications, mutate, deliver, sendRecorded, syncSignal]);

  return <SocialContext.Provider value={value}>{children}</SocialContext.Provider>;
}
