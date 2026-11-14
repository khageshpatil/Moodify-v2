import { test } from 'node:test';
import assert from 'node:assert/strict';
import { LIMITS } from '../src/api.mjs';
import { identityFromSeed, sign } from '../src/client-keys.mjs';
import { createHub } from '../src/hub.mjs';
import { bytesToBase64Url, signedMessages } from '../src/identity.mjs';
import { createBurstLimiter, createMessageGate } from '../src/limits.mjs';
import { createRoom } from '../src/room.mjs';
import { issueChallenge, readChallenge } from '../src/tokens.mjs';
import { SECRET, harness, seed, user } from './helpers.mjs';

const fromIp = (ip) => ({ 'CF-Connecting-IP': ip });

/** Registers seed n from an IP; returns the HTTP status. */
async function signUp(h, n, ip) {
  const id = await identityFromSeed(seed(n));
  const { body: { challenge } } = await h.call('POST', '/v1/auth/challenge', undefined, undefined, fromIp(ip));
  const signPub = bytesToBase64Url(id.signPub);
  const boxPub = bytesToBase64Url(id.boxPub);
  const signature = bytesToBase64Url(await sign(id.signPrivate, signedMessages.register(challenge, signPub, boxPub, 'Tester')));
  return (await h.call('POST', '/v1/accounts', { challenge, signPub, boxPub, displayName: 'Tester', signature }, undefined, fromIp(ip))).status;
}

test('challenges are signed by the server: forged or tampered ones fail, and nothing is stored to hand one out', async () => {
  const h = harness();
  const { body: { challenge } } = await h.call('POST', '/v1/auth/challenge');
  assert.equal(challenge.length, 48);
  assert.ok(await readChallenge(SECRET, challenge, 1_800_000_000));
  const tampered = challenge.slice(0, 10) + (challenge[10] === 'A' ? 'B' : 'A') + challenge.slice(11);
  assert.equal(await readChallenge(SECRET, tampered, 1_800_000_000), null);
  assert.equal(await readChallenge('another-secret-that-is-at-least-32-chars', challenge, 1_800_000_000), null);
  assert.equal(await readChallenge(SECRET, await issueChallenge(SECRET, 100), 200), null, 'expired');
  assert.equal(await readChallenge(SECRET, 'x'.repeat(48), 1), null);
});

test('new codes per network are capped per day; other networks and the next day are fine', async () => {
  const h = harness();
  for (let i = 0; i < LIMITS.signupsPerDay; i += 1) {
    assert.equal(await signUp(h, 100 + i, '203.0.113.7'), 201);
    h.advance(7); // keep under the per-minute sign-in limit (2 calls per sign-up)
  }
  assert.equal(await signUp(h, 200, '203.0.113.7'), 429);
  assert.equal(await signUp(h, 201, '198.51.100.9'), 201, 'another network');
  h.advance(24 * 60 * 60 + 1);
  assert.equal(await signUp(h, 202, '203.0.113.7'), 201, 'a day later');
  const stored = [...h.store.accounts.values()].map((a) => a.signup_ip).filter(Boolean);
  assert.ok(stored.every((tag) => !tag.includes('203')), 'the address itself is never stored');
});

test('sign-in calls are limited per IP per minute', async () => {
  const h = harness();
  for (let i = 0; i < LIMITS.authPerMinute; i += 1) {
    assert.equal((await h.call('POST', '/v1/auth/challenge', undefined, undefined, fromIp('192.0.2.1'))).status, 200);
  }
  assert.equal((await h.call('POST', '/v1/auth/challenge', undefined, undefined, fromIp('192.0.2.1'))).status, 429);
  assert.equal((await h.call('POST', '/v1/auth/challenge', undefined, undefined, fromIp('192.0.2.2'))).status, 200);
  h.advance(61);
  assert.equal((await h.call('POST', '/v1/auth/challenge', undefined, undefined, fromIp('192.0.2.1'))).status, 200);
});

test('browser link codes are limited per IP per hour', async () => {
  const h = harness();
  const devicePub = bytesToBase64Url(new Uint8Array(32).fill(7));
  for (let i = 0; i < LIMITS.linksPerHour; i += 1) {
    assert.equal((await h.call('POST', '/v1/link', { devicePub }, undefined, fromIp('192.0.2.5'))).status, 201);
  }
  assert.equal((await h.call('POST', '/v1/link', { devicePub }, undefined, fromIp('192.0.2.5'))).status, 429);
});

test('code lookups and reports are limited per account', async () => {
  const h = harness();
  const asha = await user(h, 61, 'Asha');
  const ravi = await user(h, 62, 'Ravi');
  for (let i = 0; i < LIMITS.lookupsPerMinute; i += 1) assert.equal((await h.call('GET', `/v1/users/${ravi.code}`, undefined, asha.token)).status, 200);
  assert.equal((await h.call('GET', `/v1/users/${ravi.code}`, undefined, asha.token)).status, 429);
  assert.equal((await h.call('GET', `/v1/users/${asha.code}`, undefined, ravi.token)).status, 200, 'per account');
  for (let i = 0; i < LIMITS.reportsPerDay; i += 1) assert.equal((await h.call('POST', '/v1/reports', { code: ravi.code, reason: 'spam' }, asha.token)).status, 201);
  assert.equal((await h.call('POST', '/v1/reports', { code: ravi.code, reason: 'spam' }, asha.token)).status, 429);
});

test('burst limiter windows slide and forget old keys', () => {
  const limiter = createBurstLimiter({ maxKeys: 2 });
  assert.ok(limiter.allow('a', 2, 1000, 0));
  assert.ok(limiter.allow('a', 2, 1000, 10));
  assert.equal(limiter.allow('a', 2, 1000, 20), false);
  assert.ok(limiter.allow('a', 2, 1000, 1001), 'the first hit left the window');
  limiter.allow('b', 1, 1000, 0);
  limiter.allow('c', 1, 1000, 0); // evicts the least recently used key ("a")
  assert.ok(limiter.allow('a', 1, 1000, 1002));
});

test('message gate: size cap, burst, then a steady rate', () => {
  const gate = createMessageGate({ ratePerSecond: 2, burst: 3, maxBytes: 10 });
  const socket = {};
  assert.equal(gate.check(socket, 'x'.repeat(11), 0), 'too_big');
  assert.deepEqual([1, 2, 3, 4].map(() => gate.check(socket, 'hi', 0)), ['ok', 'ok', 'ok', 'slow_down']);
  assert.equal(gate.check(socket, 'hi', 500), 'ok', 'half a second refills one');
  assert.equal(gate.check({}, 'hi', 0), 'ok', 'each socket has its own bucket');
});

test('a flooding socket is slowed in rooms (chat especially) and hubs', async () => {
  const open = [];
  const room = createRoom({ roomId: 'ABC123', sockets: () => open, now: () => 50_000 });
  const mk = () => { const s = { inbox: [], state: {}, send(d) { this.inbox.push(JSON.parse(d)); } }; open.push(s); return s; };
  const host = mk();
  room.join(host, { code: 'MOOD-AAAA-AAAA', displayName: 'Asha' });
  const guest = mk();
  room.join(guest, { code: 'MOOD-BBBB-BBBB', displayName: 'Ravi' });
  for (let i = 0; i < 10; i += 1) room.message(guest, JSON.stringify({ v: 1, type: 'room.chat', body: { text: `hi ${i}` } }));
  assert.equal(host.inbox.filter((m) => m.type === 'room.chat').length, 5, 'chat burst of 5');
  room.message(guest, 'x'.repeat(20_000));
  assert.equal(guest.inbox.at(-1).body.code, 'too_big');

  const socket = { inbox: [], state: {}, send(d) { this.inbox.push(JSON.parse(d)); } };
  const hub = createHub({ code: 'MOOD-AAAA-AAAA', sockets: () => [socket], friendCodes: async () => [], sendToHub: async () => {}, askHub: async () => null, now: () => 1000 });
  for (let i = 0; i < 25; i += 1) await hub.onMessage(socket, JSON.stringify({ v: 1, type: 'ping', body: { t: i } }));
  assert.equal(socket.inbox.filter((m) => m.type === 'pong').length, 20);
  assert.equal(socket.inbox.filter((m) => m.body?.code === 'slow_down').length, 5);
});
