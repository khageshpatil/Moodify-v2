import { test } from 'node:test';
import assert from 'node:assert/strict';
import { identityFromSeed, sign } from '../src/client-keys.mjs';
import { bytesToBase64Url, signedMessages } from '../src/identity.mjs';
import { issueToken, readToken } from '../src/tokens.mjs';
import { SECRET, harness, login, register, seed } from './helpers.mjs';

test('register, read and rename your profile', async () => {
  const h = harness();
  const id = await identityFromSeed(seed(1));
  const created = await register(h, id, '  Asha  ');
  assert.equal(created.status, 201);
  assert.equal(created.body.account.code, id.code);
  assert.equal(created.body.account.displayName, 'Asha');

  const me = await h.call('GET', '/v1/me', undefined, created.body.token);
  assert.equal(me.body.account.code, id.code);

  const renamed = await h.call('PATCH', '/v1/me', { displayName: 'Asha K' }, created.body.token);
  assert.equal(renamed.body.account.displayName, 'Asha K');
  assert.equal((await h.call('PATCH', '/v1/me', { displayName: 'A' }, created.body.token)).status, 400);
});

test('a recovered identity signs in on a new device', async () => {
  const h = harness();
  const id = await identityFromSeed(seed(2));
  await register(h, id);
  const again = await identityFromSeed(seed(2)); // same 24 words, new phone
  const signedIn = await login(h, again);
  assert.equal(signedIn.status, 200);
  assert.equal(signedIn.body.account.code, id.code);
  // Registering again with the same key is harmless (keeps the account).
  assert.equal((await register(h, again, 'Other')).status, 200);
});

test('challenges are single-use and expire', async () => {
  const h = harness();
  const id = await identityFromSeed(seed(3));
  await register(h, id);
  const { body: { challenge } } = await h.call('POST', '/v1/auth/challenge');
  const signature = bytesToBase64Url(await sign(id.signPrivate, signedMessages.login(challenge)));
  const body = { challenge, signPub: bytesToBase64Url(id.signPub), signature };
  assert.equal((await h.call('POST', '/v1/auth/verify', body)).status, 200);
  assert.equal((await h.call('POST', '/v1/auth/verify', body)).status, 401, 'replay must fail');

  const { body: { challenge: late } } = await h.call('POST', '/v1/auth/challenge');
  h.advance(6 * 60);
  const lateSig = bytesToBase64Url(await sign(id.signPrivate, signedMessages.login(late)));
  assert.equal((await h.call('POST', '/v1/auth/verify', { challenge: late, signPub: body.signPub, signature: lateSig })).status, 401);
});

test('signatures must come from the key being registered', async () => {
  const h = harness();
  const id = await identityFromSeed(seed(4));
  const impostor = await identityFromSeed(seed(5));
  const { body: { challenge } } = await h.call('POST', '/v1/auth/challenge');
  const signPub = bytesToBase64Url(id.signPub);
  const boxPub = bytesToBase64Url(id.boxPub);
  const signature = bytesToBase64Url(await sign(impostor.signPrivate, signedMessages.register(challenge, signPub, boxPub, 'Asha')));
  const result = await h.call('POST', '/v1/accounts', { challenge, signPub, boxPub, displayName: 'Asha', signature });
  assert.equal(result.status, 401);
  assert.equal(h.store.accounts.size, 0);
});

test('signing in without an account asks the app to register', async () => {
  const h = harness();
  assert.equal((await login(h, await identityFromSeed(seed(6)))).status, 404);
});

test('friends can be looked up by code, only when signed in', async () => {
  const h = harness();
  const asha = await identityFromSeed(seed(7));
  const ravi = await identityFromSeed(seed(8));
  const { body: { token } } = await register(h, asha, 'Asha');
  await register(h, ravi, 'Ravi');
  const typed = ravi.code.toLowerCase().replace(/-/g, ' ');
  const found = await h.call('GET', `/v1/users/${encodeURIComponent(typed)}`, undefined, token);
  assert.equal(found.status, 200);
  assert.deepEqual(found.body.user, { code: ravi.code, displayName: 'Ravi', boxPub: bytesToBase64Url(ravi.boxPub) });
  assert.equal((await h.call('GET', `/v1/users/${ravi.code}`)).status, 401);
  assert.equal((await h.call('GET', '/v1/users/MOOD-0000-0000', undefined, token)).status, 404);
});

test('deleting your account removes it and ends your sessions', async () => {
  const h = harness();
  const id = await identityFromSeed(seed(9));
  const { body: { token } } = await register(h, id);
  assert.equal((await h.call('DELETE', '/v1/me', undefined, token)).status, 204);
  assert.equal(h.store.accounts.size, 0);
  assert.equal((await h.call('GET', '/v1/me', undefined, token)).status, 401);
});

test('session tokens expire and cannot be forged', async () => {
  const { token } = await issueToken(SECRET, 'MOOD-67AF-HHK3', 1_000);
  assert.equal(await readToken(SECRET, token, 1_001), 'MOOD-67AF-HHK3');
  assert.equal(await readToken(SECRET, token, 1_000 + 24 * 3600 + 1), null);
  assert.equal(await readToken('another-secret-that-is-32-characters', token, 1_001), null);
  const forged = token.replace('MOOD-67AF-HHK3', 'MOOD-0000-0000');
  assert.equal(await readToken(SECRET, forged, 1_001), null);
});
