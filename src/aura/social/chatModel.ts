/**
 * Chat between friends (docs/social-protocol/chat.md), the parts that don't
 * touch the network: reading payloads, merging threads, reactions. Mirrors
 * Android's SharePayload and ChatStore so both apps read the same messages.
 */
import type { Track } from '@/data/mockMusic';
import { normalizeRoomId } from './roomSync';
import type { WireSharedTrack } from './wire';

export const MAX_TEXT = 2000;
export const EMOJI_MAX = 16;
export const QUICK_REACTIONS = ['❤️', '😂', '🔥', '👍', '😮', '😢'];
/** Messages kept per conversation in this browser. */
export const KEEP_PER_THREAD = 300;

const MID = /^[A-Za-z0-9_-]{8,64}$/;
const BROWSE_ID = /^[A-Za-z0-9_-]{2,80}$/;

export interface SharedCollection { kind: string; browseId: string; title: string; artworkUrl?: string; params?: string; pageType?: string }

export type ChatKind = 'text' | 'track' | 'collection' | 'room';
export type ChatStatus = 'sending' | 'sent' | 'failed' | 'received';

export interface ChatMessage {
  mid: string;
  /** The friend this conversation is with. */
  peer: string;
  fromMe: boolean;
  kind: ChatKind;
  text?: string;
  track?: Track;
  collection?: SharedCollection;
  roomId?: string;
  /** Seconds. */
  sentAt: number;
  status: ChatStatus;
  /** Moodify code → emoji. */
  reactions: Record<string, string>;
  read: boolean;
  /** Mine only: the exact plaintext, so a retry resends the same message. */
  wire?: string;
}

/** Conversations by friend code, each oldest first. */
export type ChatThreads = Record<string, ChatMessage[]>;

export type ParsedPayload =
  | { type: 'text'; mid: string; text: string }
  | { type: 'reaction'; mid: string; target: string; emoji: string }
  | { type: 'track'; mid: string; track: Track }
  | { type: 'collection'; mid: string; collection: SharedCollection }
  | { type: 'room'; mid: string; roomId: string; track?: Track };

/** A song inside a share or room invite, cleaned like Android's SharePayload. */
export function sharedTrack(t: WireSharedTrack | null | undefined): Track | null {
  if (!t || !/^[A-Za-z0-9_-]{11}$/.test(t.id ?? '')) return null;
  return {
    id: t.id!, title: String(t.title ?? '').slice(0, 200), artist: String(t.artist ?? '').slice(0, 200), album: String(t.album ?? '').slice(0, 200),
    albumArt: /^https:\/\//.test(t.artworkUrl ?? '') ? t.artworkUrl : '', duration: Number(t.durationSeconds) || 0,
    provider: 'youtube-music', providerId: t.id, artistId: typeof t.artistId === 'string' && t.artistId.startsWith('UC') ? t.artistId : undefined,
  };
}

/** One reaction: up to 16 characters, no ASCII letters or digits; "" removes it. */
export const validEmoji = (emoji: string) => emoji.length <= EMOJI_MAX && !/[A-Za-z0-9]/.test(emoji);

export const newMid = () => crypto.randomUUID();

/**
 * Reads a decrypted message. Older messages have no mid and get
 * [fallbackMid] (`srv-{server id}`); unknown types and newer versions are
 * skipped rather than shown as garbage.
 */
export function parsePayload(plain: string, fallbackMid: string): ParsedPayload | null {
  let json: Record<string, unknown>;
  try { json = JSON.parse(plain); } catch { return null; }
  if (!json || typeof json !== 'object' || json.v !== 1) return null;
  const mid = typeof json.mid === 'string' && MID.test(json.mid) ? json.mid : fallbackMid;
  switch (json.type) {
    case 'text': {
      const text = typeof json.text === 'string' ? json.text.slice(0, MAX_TEXT) : '';
      return text.trim() ? { type: 'text', mid, text } : null;
    }
    case 'reaction': {
      const target = typeof json.target === 'string' && MID.test(json.target) ? json.target : null;
      const emoji = typeof json.emoji === 'string' ? json.emoji : null;
      return target && emoji !== null && validEmoji(emoji) ? { type: 'reaction', mid, target, emoji } : null;
    }
    case 'track': {
      const track = sharedTrack(json.track as WireSharedTrack);
      return track ? { type: 'track', mid, track } : null;
    }
    case 'collection': {
      const c = (json.collection ?? {}) as Record<string, unknown>;
      if (typeof c.browseId !== 'string' || !BROWSE_ID.test(c.browseId)) return null;
      return {
        type: 'collection', mid,
        collection: {
          kind: ['album', 'playlist', 'artist'].includes(String(c.kind)) ? String(c.kind) : 'playlist', browseId: c.browseId, title: String(c.title ?? '').slice(0, 200),
          artworkUrl: typeof c.artworkUrl === 'string' && c.artworkUrl.startsWith('https://') ? c.artworkUrl : undefined,
          params: typeof c.params === 'string' ? c.params.slice(0, 400) : undefined,
          pageType: typeof c.pageType === 'string' ? c.pageType.slice(0, 60) : undefined,
        },
      };
    }
    case 'room': {
      const r = (json.room ?? {}) as Record<string, unknown>;
      const roomId = normalizeRoomId(String(r.id ?? ''));
      return roomId ? { type: 'room', mid, roomId, track: sharedTrack(r.track as WireSharedTrack) ?? undefined } : null;
    }
    default:
      return null;
  }
}

const trackWire = (t: Track) => ({ id: t.providerId || t.id, title: t.title, artist: t.artist, album: t.album, artworkUrl: t.albumArt, durationSeconds: t.duration, ...(t.artistId ? { artistId: t.artistId } : {}) });

/** What goes inside the encryption, matching Android's SharePayload builders. */
export const payloads = {
  text: (text: string, mid = newMid()) => JSON.stringify({ v: 1, type: 'text', mid, text: text.slice(0, MAX_TEXT) }),
  reaction: (target: string, emoji: string, mid = newMid()) => JSON.stringify({ v: 1, type: 'reaction', mid, target, emoji }),
  track: (t: Track, mid = newMid()) => JSON.stringify({ v: 1, type: 'track', mid, track: trackWire(t) }),
  collection: (c: SharedCollection, mid = newMid()) => JSON.stringify({ v: 1, type: 'collection', mid, collection: c }),
  room: (roomId: string, t?: Track | null, mid = newMid()) => JSON.stringify({ v: 1, type: 'room', mid, room: { id: roomId, ...(t ? { track: trackWire(t) } : {}) } }),
};

/** A chat message from a parsed payload (anything but a reaction). */
export function toMessage(p: Exclude<ParsedPayload, { type: 'reaction' }>, peer: string, fromMe: boolean, sentAt: number, status: ChatStatus): ChatMessage {
  const base = { mid: p.mid, peer, fromMe, sentAt, status, reactions: {}, read: fromMe };
  switch (p.type) {
    case 'text': return { ...base, kind: 'text', text: p.text };
    case 'track': return { ...base, kind: 'track', track: p.track };
    case 'collection': return { ...base, kind: 'collection', collection: p.collection };
    case 'room': return { ...base, kind: 'room', roomId: p.roomId, track: p.track };
  }
}

/** Adds a message once (by mid), keeping the thread in time order and capped. */
export function addMessage(threads: ChatThreads, message: ChatMessage): ChatThreads {
  const thread = threads[message.peer] ?? [];
  if (thread.some((m) => m.mid === message.mid)) return threads;
  let at = thread.length;
  while (at > 0 && thread[at - 1].sentAt > message.sentAt) at--;
  const next = [...thread.slice(0, at), message, ...thread.slice(at)];
  return { ...threads, [message.peer]: next.slice(-KEEP_PER_THREAD) };
}

export function updateMessage(threads: ChatThreads, peer: string, mid: string, change: Partial<ChatMessage>): ChatThreads {
  const thread = threads[peer];
  if (!thread?.some((m) => m.mid === mid)) return threads;
  return { ...threads, [peer]: thread.map((m) => (m.mid === mid ? { ...m, ...change } : m)) };
}

/** One emoji per person per message; "" removes theirs. */
export function applyReaction(threads: ChatThreads, peer: string, target: string, who: string, emoji: string): ChatThreads {
  const message = threads[peer]?.find((m) => m.mid === target);
  if (!message) return threads;
  const reactions = { ...message.reactions };
  if (emoji) reactions[who] = emoji; else delete reactions[who];
  return updateMessage(threads, peer, target, { reactions });
}

export function markRead(threads: ChatThreads, peer: string): ChatThreads {
  const thread = threads[peer];
  if (!thread?.some((m) => !m.read)) return threads;
  return { ...threads, [peer]: thread.map((m) => (m.read ? m : { ...m, read: true })) };
}

export interface Conversation { peer: string; last: ChatMessage; unread: number }

/** Newest conversation first. */
export function conversations(threads: ChatThreads): Conversation[] {
  return Object.entries(threads)
    .filter(([, thread]) => thread.length > 0)
    .map(([peer, thread]) => ({ peer, last: thread[thread.length - 1], unread: thread.filter((m) => !m.fromMe && !m.read).length }))
    .sort((a, b) => b.last.sentAt - a.last.sentAt);
}

/** A one-line preview: "hey!", "🎵 Chaleya", "💿 Aashiqui 2", "🎧 Listen together". */
export function preview(m: Pick<ChatMessage, 'kind' | 'text' | 'track' | 'collection'>): string {
  switch (m.kind) {
    case 'text': return m.text ?? '';
    case 'track': return `🎵 ${m.track?.title ?? 'A song'}`;
    case 'collection': return `💿 ${m.collection?.title ?? 'A collection'}`;
    case 'room': return '🎧 Listen together';
  }
}
