/**
 * Listen together: how a device follows the room clock
 * (docs/social-protocol/rooms.md §3). Pure, so web and Android behaviour can
 * be checked against the same numbers. Android: data/social/Rooms.kt.
 */

export const SYNC = {
  seekThresholdMs: 1000,
  nudgeStartMs: 40,
  nudgeStopMs: 20,
  nudgeRate: 0.03,
} as const;

export interface RoomClock {
  playing: boolean;
  anchorPositionMs: number;
  anchorServerTime: number;
}

/** Where the room's song is at [serverNow]; negative = a scheduled start still to come. */
export function roomPositionMs(room: RoomClock, serverNow: number): number {
  return room.playing ? room.anchorPositionMs + (serverNow - room.anchorServerTime) : room.anchorPositionMs;
}

/** Keeps the shortest-round-trip ping reply: offset = serverTime − (t0 + rtt/2). */
export class ClockSync {
  offsetMs = 0;
  private bestRttMs = Number.POSITIVE_INFINITY;

  reset() { this.bestRttMs = Number.POSITIVE_INFINITY; }

  onPong(t0: number, serverTime: number, now: number): boolean {
    const rtt = now - t0;
    if (!(t0 > 0) || rtt < 0 || rtt >= this.bestRttMs) return false;
    this.bestRttMs = rtt;
    this.offsetMs = serverTime - (t0 + rtt / 2);
    return true;
  }

  serverNow(now: number) { return now + this.offsetMs; }
}

export type FollowStep =
  | { kind: 'load' }
  | { kind: 'hold' } // a scheduled start: stay paused until it comes
  | { kind: 'match'; play: boolean; seekToMs?: number; rate: number };

/**
 * What to do this second so the local player matches the room.
 * [nudging] is whether the player is currently sped up / slowed down.
 */
export function followStep(args: {
  roomTrackId: string;
  localTrackId: string | null;
  localPositionMs: number;
  busy: boolean;
  nudging: boolean;
  room: RoomClock;
  serverNow: number;
  durationMs?: number;
  /**
   * When this far ahead (or less), pause until the room catches up instead of
   * seeking back. For players whose seeks are slow (the web reopens the stream).
   */
  waitWhenAheadMs?: number;
}): FollowStep | null {
  const { roomTrackId, localTrackId, localPositionMs, busy, nudging, room, serverNow, durationMs, waitWhenAheadMs = 0 } = args;
  if (localTrackId !== roomTrackId) return { kind: 'load' };
  const expected = roomPositionMs(room, serverNow);
  if (expected < 0) return busy ? null : { kind: 'hold' };
  if (busy) return null;
  if (!room.playing) return { kind: 'match', play: false, rate: 1 };
  const drift = localPositionMs - expected;
  const size = Math.abs(drift);
  if (drift > SYNC.seekThresholdMs && drift <= waitWhenAheadMs) return { kind: 'hold' };
  if (size > SYNC.seekThresholdMs) {
    const to = durationMs ? Math.min(expected, durationMs) : expected;
    return { kind: 'match', play: true, seekToMs: to, rate: 1 };
  }
  if (size > SYNC.nudgeStartMs) return { kind: 'match', play: true, rate: drift > 0 ? 1 - SYNC.nudgeRate : 1 + SYNC.nudgeRate };
  if (nudging && size > SYNC.nudgeStopMs) return { kind: 'match', play: true, rate: drift > 0 ? 1 - SYNC.nudgeRate : 1 + SYNC.nudgeRate };
  return { kind: 'match', play: true, rate: 1 };
}

const ROOM_ID = /^[0-9A-HJKMNP-TV-Z]{6}$/;
/** Room codes are 6 Crockford characters; accepts lower case, spaces and dashes. */
export function normalizeRoomId(raw: string): string | null {
  const id = raw.toUpperCase().replace(/[\s-]/g, '');
  return ROOM_ID.test(id) ? id : null;
}
