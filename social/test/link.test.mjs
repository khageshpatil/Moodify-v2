import { test } from 'node:test';
import assert from 'node:assert/strict';
import { identityFromSeed, linkCheckWords, newLinkKeyPair, openLinkedSeed, sealSeedForLink } from '../src/client-keys.mjs';
import { base64UrlToBytes, bytesToBase64Url } from '../src/identity.mjs';
import { LIMITS } from '../src/api.mjs';
import { harness, user } from './helpers.mjs';

test('a phone links a new device; the server relays only ciphertext, once', async () => {
  const h = harness();
  const phone = await user(h, 1, 'Asha');
  const browser = await newLinkKeyPair();

  const created = await h.call('POST', '/v1/link', { devicePub: bytesToBase64Url(browser.publicKey) });
  assert.equal(created.status, 201);
  const { linkId } = created.body;
  assert.match(linkId, /^[0-9A-HJKMNP-TV-Z]{8}$/);

  // The phone fetches the browser's key (typed code) and both show the same words.
  const fetched = await h.call('GET', `/v1/link/${linkId.toLowerCase()}`);
  const devicePub = base64UrlToBytes(fetched.body.devicePub);
  assert.deepEqual(await linkCheckWords(devicePub), await linkCheckWords(browser.publicKey));
  assert.equal((await h.call('GET', `/v1/link/${linkId}/result`)).status, 202);

  const sealed = await sealSeedForLink({ seed: phone.seed, devicePub, linkId });
  const sent = await h.call('POST', `/v1/link/${linkId}/seal`, {
    senderPub: bytesToBase64Url(sealed.senderPub), nonce: bytesToBase64Url(sealed.nonce), ciphertext: bytesToBase64Url(sealed.ciphertext),
  }, phone.token);
  assert.equal(sent.status, 200);
  assert.ok(!JSON.stringify([...h.store.links.values()]).includes(bytesToBase64Url(phone.id.signPub)));

  const result = await h.call('GET', `/v1/link/${linkId}/result`);
  assert.equal(result.status, 200);
  const seed = await openLinkedSeed({
    devicePrivate: browser.privateKey, linkId,
    senderPub: base64UrlToBytes(result.body.senderPub), nonce: base64UrlToBytes(result.body.nonce), ciphertext: base64UrlToBytes(result.body.ciphertext),
  });
  assert.equal((await identityFromSeed(seed)).code, phone.code, 'same identity on the new device');
  assert.equal((await h.call('GET', `/v1/link/${linkId}/result`)).status, 404, 'one read only');
});

test('sealing needs a signed-in phone; links expire and are single use', async () => {
  const h = harness();
  const phone = await user(h, 2, 'Ravi');
  const browser = await newLinkKeyPair();
  const { body: { linkId } } = await h.call('POST', '/v1/link', { devicePub: bytesToBase64Url(browser.publicKey) });
  const payload = { senderPub: bytesToBase64Url(browser.publicKey), nonce: 'AAAAAAAAAAAAAAAA', ciphertext: 'A'.repeat(64) };
  assert.equal((await h.call('POST', `/v1/link/${linkId}/seal`, payload)).status, 401);
  assert.equal((await h.call('POST', `/v1/link/${linkId}/seal`, payload, phone.token)).status, 200);
  assert.equal((await h.call('POST', `/v1/link/${linkId}/seal`, payload, phone.token)).status, 409);

  const { body: { linkId: late } } = await h.call('POST', '/v1/link', { devicePub: bytesToBase64Url(browser.publicKey) });
  h.advance(LIMITS.linkSeconds + 1);
  assert.equal((await h.call('GET', `/v1/link/${late}`)).status, 404);
  assert.equal((await h.call('POST', '/v1/link', { devicePub: 'nope' })).status, 400);
});
