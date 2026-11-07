import { test } from 'node:test';
import assert from 'node:assert/strict';
import { identityFromSeed, open, seal } from '../src/client-keys.mjs';
import { base64UrlToBytes, bytesToBase64Url } from '../src/identity.mjs';
import { LIMITS } from '../src/api.mjs';
import { harness, login, user } from './helpers.mjs';

const befriend = async (h, x, y) => {
  await h.call('POST', '/v1/friends/requests', { code: y.code }, x.token);
  await h.call('POST', `/v1/friends/requests/${x.code}/accept`, undefined, y.token);
};

test('a request, once accepted, makes two people friends', async () => {
  const h = harness();
  const asha = await user(h, 1, 'Asha');
  const ravi = await user(h, 2, 'Ravi');

  const sent = await h.call('POST', '/v1/friends/requests', { code: ravi.code.toLowerCase(), note: 'from college' }, asha.token);
  assert.equal(sent.status, 201);
  assert.deepEqual(h.events.at(-1), { code: ravi.code, type: 'friend.request', body: { code: asha.code, displayName: 'Asha', note: 'from college' } });

  const ravisView = (await h.call('GET', '/v1/friends', undefined, ravi.token)).body;
  assert.equal(ravisView.incoming[0].code, asha.code);
  assert.equal(ravisView.incoming[0].note, 'from college');
  assert.equal((await h.call('GET', '/v1/friends', undefined, asha.token)).body.outgoing[0].code, ravi.code);

  assert.equal((await h.call('POST', `/v1/friends/requests/${asha.code}/accept`, undefined, ravi.token)).status, 200);
  assert.equal(h.events.at(-1).type, 'friend.accepted');
  const ashasFriends = (await h.call('GET', '/v1/friends', undefined, asha.token)).body;
  assert.deepEqual(ashasFriends.friends.map((f) => f.code), [ravi.code]);
  assert.equal(ashasFriends.friends[0].boxPub, bytesToBase64Url(ravi.id.boxPub));
});

test('asking someone who already asked you makes you friends straight away', async () => {
  const h = harness();
  const asha = await user(h, 3, 'Asha');
  const ravi = await user(h, 4, 'Ravi');
  await h.call('POST', '/v1/friends/requests', { code: ravi.code }, asha.token);
  const back = await h.call('POST', '/v1/friends/requests', { code: asha.code }, ravi.token);
  assert.equal(back.body.status, 'friends');
});

test('ignoring is silent and stops repeat requests for a week', async () => {
  const h = harness();
  const asha = await user(h, 5, 'Asha');
  const ravi = await user(h, 6, 'Ravi');
  await h.call('POST', '/v1/friends/requests', { code: ravi.code }, asha.token);
  const eventsBefore = h.events.length;
  assert.equal((await h.call('POST', `/v1/friends/requests/${asha.code}/ignore`, undefined, ravi.token)).body.status, 'ignored');
  assert.equal(h.events.length, eventsBefore, 'Asha is not told');

  const again = await h.call('POST', '/v1/friends/requests', { code: ravi.code }, asha.token);
  assert.equal(again.status, 202, 'looks sent');
  assert.equal((await h.call('GET', '/v1/friends', undefined, ravi.token)).body.incoming.length, 0, 'but Ravi never sees it');

  h.advance(LIMITS.ignoreSeconds + 1);
  const fresh = (await login(h, asha.id)).body.token; // a week later, a new session
  assert.equal((await h.call('POST', '/v1/friends/requests', { code: ravi.code }, fresh)).status, 201);
});

test('a block ends the friendship, hides requests and drops messages', async () => {
  const h = harness();
  const asha = await user(h, 7, 'Asha');
  const ravi = await user(h, 8, 'Ravi');
  await befriend(h, asha, ravi);
  await h.call('POST', '/v1/messages', { to: ravi.code, nonce: 'AAAAAAAAAAAAAAAA', ciphertext: 'abc' }, asha.token);

  assert.equal((await h.call('PUT', `/v1/blocks/${asha.code}`, undefined, ravi.token)).status, 204);
  assert.equal((await h.call('GET', '/v1/friends', undefined, ravi.token)).body.friends.length, 0);
  assert.equal(h.store.messages.filter((m) => m.recipient === ravi.code).length, 0, 'pending messages from the blocked person are dropped');

  const request = await h.call('POST', '/v1/friends/requests', { code: ravi.code }, asha.token);
  assert.equal(request.status, 202);
  assert.equal((await h.call('GET', '/v1/friends', undefined, ravi.token)).body.incoming.length, 0);
  assert.deepEqual((await h.call('GET', '/v1/blocks', undefined, ravi.token)).body.blocked.map((b) => b.code), [asha.code]);

  await h.call('DELETE', `/v1/blocks/${asha.code}`, undefined, ravi.token);
  assert.equal((await h.call('GET', '/v1/blocks', undefined, ravi.token)).body.blocked.length, 0);
});

test('friend requests are limited per day', async () => {
  const h = harness();
  const asha = await user(h, 9, 'Asha');
  for (let i = 0; i < LIMITS.requestsPerDay; i++) {
    const other = await user(h, 100 + i, `User ${i}`);
    assert.equal((await h.call('POST', '/v1/friends/requests', { code: other.code }, asha.token)).status, 201);
  }
  const extra = await user(h, 99, 'One more');
  assert.equal((await h.call('POST', '/v1/friends/requests', { code: extra.code }, asha.token)).status, 429);
});

test('requests to yourself, unknown or malformed codes are refused', async () => {
  const h = harness();
  const asha = await user(h, 10, 'Asha');
  assert.equal((await h.call('POST', '/v1/friends/requests', { code: asha.code }, asha.token)).status, 400);
  assert.equal((await h.call('POST', '/v1/friends/requests', { code: 'MOOD-0000-0000' }, asha.token)).status, 404);
  assert.equal((await h.call('POST', '/v1/friends/requests', { code: 'hello' }, asha.token)).status, 400);
  assert.equal((await h.call('GET', '/v1/friends')).status, 401);
});

test('friends exchange end-to-end encrypted songs; the server sees only ciphertext', async () => {
  const h = harness();
  const asha = await user(h, 11, 'Asha');
  const ravi = await user(h, 12, 'Ravi');
  await befriend(h, asha, ravi);

  const song = JSON.stringify({ v: 1, type: 'track', track: { id: 'JGwWNGJdvx8', title: 'Shape of You', artist: 'Ed Sheeran' } });
  const sealed = await seal({ myBoxPrivate: asha.id.boxPrivate, myCode: asha.code, theirBoxPub: ravi.id.boxPub, theirCode: ravi.code, plaintext: song });
  const sent = await h.call('POST', '/v1/messages', { to: ravi.code, nonce: bytesToBase64Url(sealed.nonce), ciphertext: bytesToBase64Url(sealed.ciphertext) }, asha.token);
  assert.equal(sent.status, 201);
  assert.deepEqual(h.events.at(-1), { code: ravi.code, type: 'msg.new', body: { from: asha.code, id: sent.body.id } });
  assert.deepEqual(h.events.at(-2), { code: asha.code, type: 'msg.new', body: { from: asha.code, id: sent.body.copyId, own: true } });
  assert.ok(!JSON.stringify(h.store.messages).includes('Shape of You'), 'stored as ciphertext only');

  const inbox = (await h.call('GET', '/v1/messages?after=0', undefined, ravi.token)).body.messages;
  assert.equal(inbox.length, 1);
  const plain = await open({
    myBoxPrivate: ravi.id.boxPrivate, myCode: ravi.code, theirBoxPub: asha.id.boxPub, theirCode: asha.code,
    nonce: base64UrlToBytes(inbox[0].nonce), ciphertext: base64UrlToBytes(inbox[0].ciphertext),
  });
  assert.equal(plain, song);

  // Someone else can't read it, even with the ciphertext.
  const stranger = await identityFromSeed(new Uint8Array(32).fill(9));
  await assert.rejects(open({
    myBoxPrivate: stranger.boxPrivate, myCode: stranger.code, theirBoxPub: asha.id.boxPub, theirCode: asha.code,
    nonce: base64UrlToBytes(inbox[0].nonce), ciphertext: base64UrlToBytes(inbox[0].ciphertext),
  }));

  assert.equal((await h.call('POST', '/v1/messages/ack', { upTo: inbox[0].id }, ravi.token)).status, 204);
  assert.equal((await h.call('GET', '/v1/messages?after=0', undefined, ravi.token)).body.messages.length, 0);
});

test('only friends can message, plaintext is refused, old messages expire', async () => {
  const h = harness();
  const asha = await user(h, 13, 'Asha');
  const ravi = await user(h, 14, 'Ravi');
  const body = { to: ravi.code, nonce: 'AAAAAAAAAAAAAAAA', ciphertext: 'c2VjcmV0' };
  assert.equal((await h.call('POST', '/v1/messages', body, asha.token)).status, 403);
  await befriend(h, asha, ravi);
  assert.equal((await h.call('POST', '/v1/messages', { ...body, ciphertext: 'hello there!' }, asha.token)).status, 400);
  assert.equal((await h.call('POST', '/v1/messages', body, asha.token)).status, 201);
  h.advance(LIMITS.messageRetentionSeconds + 10);
  const ashaNow = (await login(h, asha.id)).body.token;
  const raviNow = (await login(h, ravi.id)).body.token;
  await h.call('POST', '/v1/messages', body, ashaNow); // sending prunes expired ones
  assert.equal((await h.call('GET', '/v1/messages', undefined, raviNow)).body.messages.length, 1);
});

test('reports are recorded with the evidence the reporter chose', async () => {
  const h = harness();
  const asha = await user(h, 15, 'Asha');
  const ravi = await user(h, 16, 'Ravi');
  const result = await h.call('POST', '/v1/reports', { code: ravi.code, reason: 'spam', evidence: 'buy followers' }, asha.token);
  assert.equal(result.status, 201);
  assert.deepEqual(h.store.reports[0].reported, ravi.code);
  assert.equal((await h.call('POST', '/v1/reports', { code: ravi.code }, asha.token)).status, 400);
});

test('deleting an account removes its friendships and messages and tells friends', async () => {
  const h = harness();
  const asha = await user(h, 17, 'Asha');
  const ravi = await user(h, 18, 'Ravi');
  await befriend(h, asha, ravi);
  await h.call('POST', '/v1/messages', { to: ravi.code, nonce: 'AAAAAAAAAAAAAAAA', ciphertext: 'abc' }, asha.token);
  await h.call('DELETE', '/v1/me', undefined, asha.token);
  assert.equal(h.store.friendships.size, 0);
  assert.equal(h.store.messages.length, 0);
  assert.deepEqual(h.events.at(-1), { code: ravi.code, type: 'friend.removed', body: { code: asha.code } });
});

test('message encryption matches the published vector', async () => {
  const a = await identityFromSeed(new Uint8Array(32));
  const b = await identityFromSeed(Uint8Array.from({ length: 32 }, (_, i) => i));
  const plaintext = '{"v":1,"type":"track","track":{"id":"JGwWNGJdvx8","title":"Shape of You","artist":"Ed Sheeran"}}';
  const sealed = await seal({
    myBoxPrivate: a.boxPrivate, myCode: a.code, theirBoxPub: b.boxPub, theirCode: b.code, plaintext,
    nonce: Uint8Array.from({ length: 12 }, (_, i) => i + 1),
  });
  assert.equal(bytesToBase64Url(sealed.ciphertext),
    'zwI4gG8-xcvFfq7dUM7Q8QtLynjZWv8pzPUZMiePwuUZmftS6BxqaecYXeen0Ym26zBZtQzMyBIydVUifi8octyXbEr5nu-uBO2Q7XPj-GOJcKrimfoq8JJjUtIneLyW6wOKm9bEq5Ebi3FoeG_vFA');
});
