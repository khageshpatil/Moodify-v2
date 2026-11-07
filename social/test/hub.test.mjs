import { test } from 'node:test';
import assert from 'node:assert/strict';
import { cleanPresence, createHub } from '../src/hub.mjs';

/** A little network of hubs talking to each other in memory. */
function network(friendsOf) {
  const hubs = new Map();
  const sockets = new Map(); // code -> [{send, state, inbox}]
  let clock = 1_000_000;
  const hubOf = (code) => {
    if (!hubs.has(code)) {
      sockets.set(code, []);
      hubs.set(code, createHub({
        code,
        sockets: () => sockets.get(code),
        friendCodes: async () => friendsOf[code] ?? [],
        sendToHub: async (to, event) => hubOf(to).onEvent(event),
        askHub: async (to, from) => hubOf(to).presenceFor(from),
        now: () => clock,
      }));
    }
    return hubs.get(code);
  };
  const connect = async (code) => {
    const socket = { inbox: [], state: {}, send(data) { this.inbox.push(JSON.parse(data)); } };
    hubOf(code);
    sockets.get(code).push(socket);
    await hubOf(code).onConnect(socket);
    return socket;
  };
  const disconnect = async (code, socket) => {
    sockets.set(code, sockets.get(code).filter((s) => s !== socket));
    await hubOf(code).onClose(socket);
  };
  return { hubOf, connect, disconnect, tick: (ms) => { clock += ms; } };
}

const song = { trackId: 'JGwWNGJdvx8', title: 'Shape of You', artist: 'Ed Sheeran', artworkUrl: 'https://i.ytimg.com/x.jpg', durationMs: 263000, positionMs: 1000, playing: true };
const update = (body) => JSON.stringify({ v: 1, type: 'presence.update', body });
const of = (socket, type) => socket.inbox.filter((m) => m.type === type);

test('friends see what you play, live; strangers do not', async () => {
  const net = network({ A: ['B'], B: ['A'], C: [] });
  const b = await net.connect('B');
  const c = await net.connect('C');
  const a = await net.connect('A');
  await net.hubOf('A').onMessage(a, update(song));

  const seen = of(b, 'presence.friend').at(-1).body;
  assert.equal(seen.code, 'A');
  assert.equal(seen.presence.title, 'Shape of You');
  assert.equal(of(c, 'presence.friend').length, 0);
});

test('hello lists what online friends are playing right now', async () => {
  const net = network({ A: ['B'], B: ['A'] });
  const a = await net.connect('A');
  await net.hubOf('A').onMessage(a, update(song));
  const b = await net.connect('B');
  const hello = of(b, 'hello')[0].body;
  assert.equal(hello.friends[0].code, 'A');
  assert.equal(hello.friends[0].presence.trackId, 'JGwWNGJdvx8');
});

test('position ticks are rate-limited but song changes go out at once', async () => {
  const net = network({ A: ['B'], B: ['A'] });
  const b = await net.connect('B');
  const a = await net.connect('A');
  const before = of(b, 'presence.friend').length;
  await net.hubOf('A').onMessage(a, update(song));
  await net.hubOf('A').onMessage(a, update({ ...song, positionMs: 2000 }));
  await net.hubOf('A').onMessage(a, update({ ...song, positionMs: 3000 }));
  assert.equal(of(b, 'presence.friend').length, before + 1, 'same song within a second: one update');
  await net.hubOf('A').onMessage(a, update({ ...song, playing: false }));
  assert.equal(of(b, 'presence.friend').length, before + 2, 'pause goes out immediately');
  net.tick(1500);
  await net.hubOf('A').onMessage(a, update({ ...song, playing: false, positionMs: 9000 }));
  assert.equal(of(b, 'presence.friend').length, before + 3);
});

test('turning sharing off makes you look offline', async () => {
  const net = network({ A: ['B'], B: ['A'] });
  const b = await net.connect('B');
  const a = await net.connect('A');
  await net.hubOf('A').onMessage(a, update(song));
  await net.hubOf('A').onMessage(a, JSON.stringify({ type: 'settings.update', body: { sharing: false } }));
  const last = of(b, 'presence.friend').at(-1).body;
  assert.equal(last.online, false);
  assert.equal(last.presence, null);
  assert.equal(await net.hubOf('A').presenceFor('C'), null, 'non-friends never get presence');
});

test('going offline leaves "last played", paused', async () => {
  const net = network({ A: ['B'], B: ['A'] });
  const b = await net.connect('B');
  const a = await net.connect('A');
  await net.hubOf('A').onMessage(a, update(song));
  await net.disconnect('A', a);
  const last = of(b, 'presence.friend').at(-1).body;
  assert.equal(last.online, false);
  assert.equal(last.presence.trackId, song.trackId);
  assert.equal(last.presence.playing, false);
  assert.ok(last.lastSeenAt);
});

test('events reach every device; ping is answered; junk is reported', async () => {
  const net = network({ A: [] });
  const phone = await net.connect('A');
  const laptop = await net.connect('A');
  await net.hubOf('A').onEvent({ type: 'msg.new', body: { from: 'B', id: 4 } });
  assert.equal(of(phone, 'msg.new').length, 1);
  assert.equal(of(laptop, 'msg.new').length, 1);
  await net.hubOf('A').onMessage(phone, JSON.stringify({ type: 'ping', body: { t: 7 } }));
  assert.equal(of(phone, 'pong')[0].body.t, 7);
  await net.hubOf('A').onMessage(phone, 'not json');
  assert.equal(of(phone, 'error')[0].body.code, 'bad_json');
});

test('presence is sanitised before friends see it', () => {
  assert.equal(cleanPresence({ trackId: '../../etc' }), null);
  const clean = cleanPresence({ ...song, artworkUrl: 'javascript:alert(1)', extra: 'nope', positionMs: -5 });
  assert.equal(clean.artworkUrl, '');
  assert.equal(clean.positionMs, 0);
  assert.equal('extra' in clean, false);
});
