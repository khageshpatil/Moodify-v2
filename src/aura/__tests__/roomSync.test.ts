import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { ClockSync, SYNC, followStep, normalizeRoomId, roomPositionMs } from '../social/roomSync';

const room = (over: Partial<{ playing: boolean; anchorPositionMs: number; anchorServerTime: number }> = {}) =>
  ({ playing: true, anchorPositionMs: 30_000, anchorServerTime: 1_000_000, ...over });
const base = { roomTrackId: 'JGwWNGJdvx8', localTrackId: 'JGwWNGJdvx8', busy: false, nudging: false };

describe('listening room sync (rooms.md §3)', () => {
  it('reads the room clock, including a scheduled start', () => {
    expect(roomPositionMs(room(), 1_005_000)).toBe(35_000);
    expect(roomPositionMs(room({ playing: false }), 1_005_000)).toBe(30_000);
    expect(roomPositionMs(room({ anchorPositionMs: 0, anchorServerTime: 1_001_500 }), 1_000_000)).toBe(-1500);
  });

  it('keeps the shortest round trip for the clock offset', () => {
    const clock = new ClockSync();
    expect(clock.onPong(1000, 5_000, 1200)).toBe(true); // rtt 200 → offset 3900
    expect(clock.offsetMs).toBe(3900);
    expect(clock.onPong(2000, 6_010, 2300)).toBe(false); // slower reply ignored
    expect(clock.onPong(3000, 7_020, 3040)).toBe(true); // rtt 40 → offset 4000
    expect(clock.offsetMs).toBe(4000);
  });

  it('loads, holds, seeks, nudges and settles like Android', () => {
    const at = (localPositionMs: number, extra = {}) => followStep({ ...base, localPositionMs, room: room(), serverNow: 1_000_000, ...extra });
    expect(at(0, { localTrackId: 'other' })).toEqual({ kind: 'load' });
    expect(followStep({ ...base, localPositionMs: 0, room: room({ anchorPositionMs: 0, anchorServerTime: 1_001_500 }), serverNow: 1_000_000 })).toEqual({ kind: 'hold' });
    expect(at(10_000)).toEqual({ kind: 'match', play: true, seekToMs: 30_000, rate: 1 });
    expect(at(30_500)).toEqual({ kind: 'match', play: true, rate: 0.97 }); // ahead: slow down
    expect(at(29_900)).toEqual({ kind: 'match', play: true, rate: 1.03 }); // behind: speed up
    expect(at(30_030, { nudging: true })).toEqual({ kind: 'match', play: true, rate: 0.97 }); // keep nudging to ≤20 ms
    expect(at(30_010, { nudging: true })).toEqual({ kind: 'match', play: true, rate: 1 });
    expect(at(30_030)).toEqual({ kind: 'match', play: true, rate: 1 }); // small drift alone: leave it
    expect(at(10_000, { busy: true })).toBeNull();
    expect(followStep({ ...base, localPositionMs: 0, room: room({ playing: false }), serverNow: 1_000_000 })).toEqual({ kind: 'match', play: false, rate: 1 });
  });

  it('waits for the room instead of seeking back when a slow-seeking player is a little ahead', () => {
    const at = (localPositionMs: number) => followStep({ ...base, localPositionMs, room: room(), serverNow: 1_000_000, waitWhenAheadMs: 6000 });
    expect(at(33_000)).toEqual({ kind: 'hold' }); // 3 s ahead: pause, the room catches up
    expect(at(40_000)).toEqual({ kind: 'match', play: true, seekToMs: 30_000, rate: 1 }); // too far ahead: seek
    expect(at(20_000)).toEqual({ kind: 'match', play: true, seekToMs: 30_000, rate: 1 }); // behind: seek
  });

  it('uses the same thresholds as Android', () => {
    const kotlin = readFileSync(resolve(__dirname, '../../../android/app/src/main/java/com/example/myapplication/data/social/Rooms.kt'), 'utf8');
    const constant = (name: string) => Number(kotlin.match(new RegExp(`${name} = (\\d+)L`))?.[1]);
    expect(constant('SEEK_THRESHOLD_MS')).toBe(SYNC.seekThresholdMs);
    expect(constant('NUDGE_START_MS')).toBe(SYNC.nudgeStartMs);
    expect(constant('NUDGE_STOP_MS')).toBe(SYNC.nudgeStopMs);
    expect(kotlin).toContain('0.97f');
    expect(kotlin).toContain('1.03f');
  });

  it('accepts room codes the way people type them', () => {
    expect(normalizeRoomId('k7p2qx')).toBe('K7P2QX');
    expect(normalizeRoomId('K7P 2QX')).toBe('K7P2QX');
    expect(normalizeRoomId('K7P2Q')).toBeNull();
    expect(normalizeRoomId('../x/a')).toBeNull();
    expect(normalizeRoomId('K7P2QU')).toBeNull(); // U isn't Crockford
  });
});
