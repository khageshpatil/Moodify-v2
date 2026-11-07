import { test } from 'node:test';
import assert from 'node:assert/strict';
import { open, openOwn, seal } from '../src/client-keys.mjs';
import { base64UrlToBytes, bytesToBase64Url } from '../src/identity.mjs';
import { LIMITS } from '../src/api.mjs';
import { harness, login, user } from './helpers.mjs';

const befriend = async (h, x, y) => {
  await h.call('POST', '/v1/friends/requests', { code: y.code }, x.token);
  await h.call('POST', `/v1/friends/requests/${x.code}/accept`, undefined, y.token);
};

async function sendText(h, from, to, text) {
  const plaintext = JSON.stringify({ v: 1, type: 'text', mid: crypto.randomUUID(), text });
  const sealed = await seal({ myBoxPrivate: from.id.boxPrivate, myCode: from.code, theirBoxPub: to.id.boxPub, theirCode: to.code, plaintext });
  return h.call('POST', '/v1/messages', { to: to.code, nonce: bytesToBase64Url(sealed.nonce), ciphertext: bytesToBase64Url(sealed.ciphertext) }, from.token);
}
const inbox = async (h, who, after = 0) => (await h.call('GET', `/v1/messages?after=${after}`, undefined, who.token)).body.messages;
const ack = (h, who, upTo, device) => h.call('POST', '/v1/messages/ack', { upTo, device }, who.token);

test('your other devices get a copy of what you sent, and can read it', async () => {
  const h = harness();
  const asha = await user(h, 31, 'Asha');
  const ravi = await user(h, 32, 'Ravi');
  await befriend(h, asha, ravi);
  const sent = await sendText(h, asha, ravi, 'have you heard this one?');
  assert.equal(sent.status, 201);

  const mine = await inbox(h, asha);
  assert.equal(mine.length, 1);
  assert.equal(mine[0].id, sent.body.copyId);
  assert.equal(mine[0].from, asha.code);
  assert.equal(mine[0].to, ravi.code, 'a copy says who it went to');
  const plain = JSON.parse(await openOwn({
    myBoxPrivate: asha.id.boxPrivate, myCode: asha.code, theirBoxPub: ravi.id.boxPub, theirCode: ravi.code,
    nonce: base64UrlToBytes(mine[0].nonce), ciphertext: base64UrlToBytes(mine[0].ciphertext),
  }));
  assert.equal(plain.text, 'have you heard this one?');

  // The recipient's copy has no `to` and opens the usual way.
  const theirs = await inbox(h, ravi);
  assert.equal(theirs.length, 1);
  assert.equal(theirs[0].to, undefined);
  assert.equal(JSON.parse(await open({
    myBoxPrivate: ravi.id.boxPrivate, myCode: ravi.code, theirBoxPub: asha.id.boxPub, theirCode: asha.code,
    nonce: base64UrlToBytes(theirs[0].nonce), ciphertext: base64UrlToBytes(theirs[0].ciphertext),
  })).text, 'have you heard this one?');
});

test('each device reads the inbox from its own position; a message goes once every device has it', async () => {
  const h = harness();
  const asha = await user(h, 33, 'Asha');
  const ravi = await user(h, 34, 'Ravi');
  await befriend(h, asha, ravi);
  await sendText(h, asha, ravi, 'one');
  const [first] = await inbox(h, ravi);

  // A device counts once it has confirmed something (the browser read up to an earlier id).
  await ack(h, ravi, 1, 'ravi-browser-01');
  await ack(h, ravi, first.id, 'ravi-phone-0001');
  assert.equal((await inbox(h, ravi)).length, 1, 'kept: the browser has not confirmed it yet');
  await sendText(h, asha, ravi, 'two');
  const both = await inbox(h, ravi);
  assert.equal(both.length, 2);
  await ack(h, ravi, both[1].id, 'ravi-browser-01');
  assert.deepEqual((await inbox(h, ravi)).map((m) => m.id), [both[1].id], 'first is gone; second waits for the phone');
  await ack(h, ravi, both[1].id, 'ravi-phone-0001');
  assert.equal((await inbox(h, ravi)).length, 0);
});

test('a new device checks in with 0 and is kept for from then on', async () => {
  const h = harness();
  const asha = await user(h, 37, 'Asha');
  const ravi = await user(h, 38, 'Ravi');
  await befriend(h, asha, ravi);
  await ack(h, ravi, 0, 'ravi-newlaptop'); // linked, inbox still empty
  await sendText(h, asha, ravi, 'hello new laptop');
  const [m] = await inbox(h, ravi);
  await ack(h, ravi, m.id, 'ravi-phone-0001');
  assert.equal((await inbox(h, ravi)).length, 1, 'kept for the laptop, which has not read it');
  await ack(h, ravi, m.id, 'ravi-newlaptop');
  assert.equal((await inbox(h, ravi)).length, 0);
});

test('a device that went quiet for 30 days stops holding messages back', async () => {
  const h = harness();
  const asha = await user(h, 35, 'Asha');
  const ravi = await user(h, 36, 'Ravi');
  await befriend(h, asha, ravi);
  await sendText(h, asha, ravi, 'old');
  const [m] = await inbox(h, ravi);
  await ack(h, ravi, 1, 'old-tablet-001'); // an old tablet confirmed less
  await ack(h, ravi, m.id, 'ravi-phone-0001');
  assert.equal((await inbox(h, ravi)).length, 1, 'the tablet still needs it');

  h.advance(LIMITS.messageRetentionSeconds + 60);
  const fresh = async (who) => ({ ...who, token: (await login(h, who.id)).body.token });
  const ashaNow = await fresh(asha);
  const raviNow = await fresh(ravi);
  await sendText(h, ashaNow, raviNow, 'new');
  const [n] = await inbox(h, raviNow);
  await ack(h, raviNow, n.id, 'ravi-phone-0001');
  assert.equal((await inbox(h, raviNow)).length, 0, 'the silent tablet no longer counts');
});

test('sending is rate limited per sender', async () => {
  const h = harness();
  const asha = await user(h, 37, 'Asha');
  const ravi = await user(h, 38, 'Ravi');
  await befriend(h, asha, ravi);
  const body = { to: ravi.code, nonce: 'AAAAAAAAAAAAAAAA', ciphertext: 'c2VjcmV0' };
  for (let i = 0; i < LIMITS.messagesPerHour; i++) await h.call('POST', '/v1/messages', body, asha.token);
  assert.equal((await h.call('POST', '/v1/messages', body, asha.token)).status, 429);
  h.advance(3601);
  const again = (await login(h, asha.id)).body.token;
  assert.equal((await h.call('POST', '/v1/messages', body, again)).status, 201, 'an hour later it works again');
});

test('a bad device id falls back to the shared legacy position', async () => {
  const h = harness();
  const asha = await user(h, 39, 'Asha');
  const ravi = await user(h, 40, 'Ravi');
  await befriend(h, asha, ravi);
  await sendText(h, asha, ravi, 'hi');
  const [m] = await inbox(h, ravi);
  assert.equal((await ack(h, ravi, m.id, '../../etc')).status, 204);
  assert.equal((await inbox(h, ravi)).length, 0, 'the only device (legacy) confirmed it');
});
