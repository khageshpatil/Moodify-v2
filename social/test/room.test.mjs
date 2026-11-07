import { test } from 'node:test';
import assert from 'node:assert/strict';
import { ROOM_LIMITS, createRoom } from '../src/room.mjs';

function setup() {
  let clock = 10_000;
  let open = [];
  let persisted = null;
  const room = createRoom({ roomId: 'ABC123', sockets: () => open, now: () => clock, onChange: (s) => { persisted = s; } });
  const connect = (code, name) => {
    const socket = { inbox: [], state: {}, send(d) { this.inbox.push(JSON.parse(d)); } };
    open.push(socket);
    room.join(socket, { code, displayName: name });
    return socket;
  };
  const leave = (socket) => { open = open.filter((s) => s !== socket); room.leave(socket.state.code); };
  const send = (socket, type, body) => room.message(socket, JSON.stringify({ v: 1, type, body }));
  const last = (socket, type) => socket.inbox.filter((m) => m.type === type).at(-1)?.body;
  return { room, connect, leave, send, last, tick: (ms) => { clock += ms; }, now: () => clock, persisted: () => persisted };
}

const song = (id, durationMs = 200_000) => ({ trackId: id, title: `Song ${id}`, artist: 'Artist', artworkUrl: 'https://i.ytimg.com/x.jpg', durationMs });

test('the first person is host; others join as listeners', () => {
  const r = setup();
  const host = r.connect('MOOD-AAAA-AAAA', 'Asha');
  const guest = r.connect('MOOD-BBBB-BBBB', 'Ravi');
  assert.equal(r.last(host, 'room.welcome').role, 'host');
  assert.equal(r.last(guest, 'room.welcome').role, 'listener');
  assert.deepEqual(r.last(guest, 'room.state').members.map((m) => [m.displayName, m.role]), [['Asha', 'host'], ['Ravi', 'listener']]);
});

test('songs start for everyone at the same scheduled moment', () => {
  const r = setup();
  const host = r.connect('MOOD-AAAA-AAAA', 'Asha');
  const guest = r.connect('MOOD-BBBB-BBBB', 'Ravi');
  r.send(host, 'room.control', { action: 'setTrack', track: song('JGwWNGJdvx8') });
  const scheduled = r.last(guest, 'track.scheduled');
  assert.equal(scheduled.track.trackId, 'JGwWNGJdvx8');
  assert.equal(scheduled.startAtServerTime, r.now() + ROOM_LIMITS.startDelayMs);
  // A second and a half after the start, everyone should be 1.5 s in.
  r.tick(ROOM_LIMITS.startDelayMs + 1500);
  assert.equal(r.room.snapshot().positionMs, 1500);
});

test('pause, seek and play move the shared clock', () => {
  const r = setup();
  const host = r.connect('MOOD-AAAA-AAAA', 'Asha');
  r.send(host, 'room.control', { action: 'setTrack', track: song('JGwWNGJdvx8') });
  r.tick(ROOM_LIMITS.startDelayMs + 10_000);
  r.send(host, 'room.control', { action: 'pause' });
  r.tick(5_000);
  assert.equal(r.room.snapshot().positionMs, 10_000, 'paused: the clock stops');
  r.send(host, 'room.control', { action: 'seek', positionMs: 60_000 });
  r.send(host, 'room.control', { action: 'play' });
  r.tick(2_000);
  assert.equal(r.room.snapshot().positionMs, 62_000);
});

test('listeners cannot control playback, but their queue picks become suggestions', () => {
  const r = setup();
  const host = r.connect('MOOD-AAAA-AAAA', 'Asha');
  const guest = r.connect('MOOD-BBBB-BBBB', 'Ravi');
  r.send(guest, 'room.control', { action: 'setTrack', track: song('JGwWNGJdvx8') });
  assert.equal(r.last(guest, 'error').code, 'not_allowed');
  assert.equal(r.room.snapshot().track, null);
  r.send(guest, 'room.control', { action: 'queueAdd', track: song('VAdGW7QDJiU') });
  assert.equal(r.room.snapshot().queue.length, 0);
  assert.equal(r.last(host, 'room.suggestion').track.trackId, 'VAdGW7QDJiU');
});

test('a DJ can queue; the queue advances when a song ends', () => {
  const r = setup();
  const host = r.connect('MOOD-AAAA-AAAA', 'Asha');
  const guest = r.connect('MOOD-BBBB-BBBB', 'Ravi');
  r.send(host, 'room.role', { code: 'MOOD-BBBB-BBBB', role: 'dj' });
  assert.equal(r.last(guest, 'room.state').members[1].role, 'dj');
  r.send(host, 'room.control', { action: 'setTrack', track: song('JGwWNGJdvx8', 10_000) });
  r.send(guest, 'room.control', { action: 'queueAdd', track: song('VAdGW7QDJiU') });
  assert.equal(r.room.endsAt(), r.now() + ROOM_LIMITS.startDelayMs + 10_000);
  r.tick(ROOM_LIMITS.startDelayMs + 10_001);
  r.room.tick();
  assert.equal(r.room.snapshot().track.trackId, 'VAdGW7QDJiU');
  assert.equal(r.room.snapshot().queue.length, 0);
});

test('when the host leaves, the longest-present DJ takes over', () => {
  const r = setup();
  const host = r.connect('MOOD-AAAA-AAAA', 'Asha');
  r.tick(10);
  r.connect('MOOD-BBBB-BBBB', 'Ravi');
  r.tick(10);
  const dj = r.connect('MOOD-CCCC-CCCC', 'Meera');
  r.send(host, 'room.role', { code: 'MOOD-CCCC-CCCC', role: 'dj' });
  r.leave(host);
  assert.equal(r.room.snapshot().hostCode, 'MOOD-CCCC-CCCC');
  assert.equal(r.last(dj, 'room.state').members.find((m) => m.code === 'MOOD-CCCC-CCCC').role, 'host');
});

test('clock sync, chat, limits and persistence', () => {
  const r = setup();
  const host = r.connect('MOOD-AAAA-AAAA', 'Asha');
  r.send(host, 'ping', { t0: 42 });
  assert.deepEqual(r.last(host, 'pong'), { t0: 42, serverTime: r.now() });
  r.send(host, 'room.chat', { text: `  hello   ${'x'.repeat(400)}` });
  assert.equal(r.last(host, 'room.chat').text.length, ROOM_LIMITS.chatLength);
  r.send(host, 'room.control', { action: 'setTrack', track: { trackId: '../../x' } });
  assert.equal(r.last(host, 'error').code, 'bad_track');
  r.send(host, 'room.control', { action: 'setTrack', track: song('JGwWNGJdvx8') });
  assert.equal(r.persisted().track.trackId, 'JGwWNGJdvx8', 'state is handed to storage on every change');
  for (let i = 0; i < ROOM_LIMITS.members - 1; i++) r.connect(`MOOD-${String(i).padStart(4, '0')}-ZZZZ`, `Guest ${i}`);
  const socket = { inbox: [], state: {}, send(d) { this.inbox.push(JSON.parse(d)); } };
  assert.deepEqual(r.room.join(socket, { code: 'MOOD-FULL-FULL', displayName: 'Late' }), { ok: false, reason: 'room_full' });
});

test("the host's own player anchors the clock: no start delay, exact resume points", () => {
  const r = setup();
  const host = r.connect('MOOD-AAAA-AAAA', 'Asha');
  r.send(host, 'room.control', { action: 'setTrack', track: song('JGwWNGJdvx8'), positionMs: 1200 });
  assert.equal(r.room.snapshot().positionMs, 1200, 'starts where the host already is');
  r.tick(3000);
  r.send(host, 'room.control', { action: 'pause', positionMs: 4150 });
  r.tick(9000);
  assert.equal(r.room.snapshot().positionMs, 4150);
  r.send(host, 'room.control', { action: 'play', positionMs: 4150 });
  r.tick(1000);
  assert.equal(r.room.snapshot().positionMs, 5150);
});
