/** Shapes of what moodify-social sends (docs/social-protocol/). Fields are optional: never trust the wire. */

export interface WireTrack {
  trackId: string;
  title?: string;
  artist?: string;
  artworkUrl?: string;
  durationMs?: number;
}

export interface WireMember { code: string; displayName: string; role: 'host' | 'dj' | 'listener' }

/** Every body the room sends, flattened (rooms.md §4). */
export interface WireRoomBody {
  t0?: number;
  serverTime?: number;
  you?: string;
  role?: WireMember['role'];
  members?: WireMember[];
  hostCode?: string | null;
  track?: WireTrack | null;
  playing?: boolean;
  anchorPositionMs?: number;
  anchorServerTime?: number;
  queue?: WireTrack[];
  from?: string;
  displayName?: string;
  text?: string;
}

/** A friend's entry in `hello` and `presence.friend` (friends.md). */
export interface WireFriendPresence {
  code: string;
  online?: boolean;
  lastSeenAt?: number | null;
  presence?: (WireTrack & { playing?: boolean; positionMs?: number }) | null;
}

/** One inbox entry (chat.md §2); `to` is set on your own copies. */
export interface WireInboxMessage { id: number; from: string; to?: string; nonce: string; ciphertext: string; sentAt: number }

/** A song inside an encrypted share or room invite. */
export interface WireSharedTrack {
  id?: string;
  title?: unknown;
  artist?: unknown;
  album?: unknown;
  artworkUrl?: string;
  durationSeconds?: unknown;
  artistId?: unknown;
}
