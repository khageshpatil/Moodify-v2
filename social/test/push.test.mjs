import { test } from 'node:test';
import assert from 'node:assert/strict';
import { identityFromSeed, seal } from '../src/client-keys.mjs';
import { base64UrlToBytes, bytesToBase64Url } from '../src/identity.mjs';
import { fcmPush } from '../src/push.mjs';
import { harness, seed, user } from './helpers.mjs';

const TOKEN_A = 'fcm-token-for-phone-A:APA91b' + 'x'.repeat(40);
const TOKEN_B = 'fcm-token-for-tablet-B:APA91b' + 'y'.repeat(40);

const befriend = async (h, x, y) => {
  await h.call('POST', '/v1/friends/requests', { code: y.code }, x.token);
  await h.call('POST', `/v1/friends/requests/${x.code}/accept`, undefined, y.token);
};
async function sendText(h, from, to, text) {
  const sealed = await seal({ myBoxPrivate: from.id.boxPrivate, myCode: from.code, theirBoxPub: to.id.boxPub, theirCode: to.code, plaintext: JSON.stringify({ v: 1, type: 'text', mid: crypto.randomUUID(), text }) });
  return h.call('POST', '/v1/messages', { to: to.code, nonce: bytesToBase64Url(sealed.nonce), ciphertext: bytesToBase64Url(sealed.ciphertext) }, from.token);
}
const register = (h, who, device, token) => h.call('PUT', '/v1/push', { device, platform: 'fcm', token }, who.token);

function recorder(answer = () => 'ok') {
  const sent = [];
  const push = async (target, data) => { sent.push({ ...target, data }); return answer(target); };
  return { sent, push };
}

test('a message wakes the friend\'s devices with a ping that carries no content', async () => {
  const pings = recorder();
  const h = harness({ push: pings.push });
  const asha = await user(h, 41, 'Asha');
  const ravi = await user(h, 42, 'Ravi');
  await befriend(h, asha, ravi);
  assert.equal((await register(h, ravi, 'ravi-phone-0001', TOKEN_A)).status, 204);
  assert.equal((await register(h, asha, 'asha-phone-0001', TOKEN_B)).status, 204);
  await sendText(h, asha, ravi, 'secret words');
  assert.deepEqual(pings.sent, [{ platform: 'fcm', token: TOKEN_A, data: { kind: 'message' } }], 'only Ravi, and nothing but "you have mail"');
  assert.ok(!JSON.stringify(pings.sent).includes('secret') && !JSON.stringify(pings.sent).includes(asha.code));
});

test('a device connected live gets the event instead of a ping', async () => {
  const pings = recorder();
  const raviCode = (await identityFromSeed(seed(44))).code;
  const h = harness({ push: pings.push, live: { [raviCode]: ['ravi-phone-0001'] } }); // the phone has the app open
  const asha = await user(h, 43, 'Asha');
  const ravi = await user(h, 44, 'Ravi');
  await befriend(h, asha, ravi);
  await register(h, ravi, 'ravi-phone-0001', TOKEN_A);
  await register(h, ravi, 'ravi-tablet-001', TOKEN_B);
  await sendText(h, asha, ravi, 'hi');
  assert.deepEqual(pings.sent.map((p) => p.token), [TOKEN_B]);
});

test('blocked senders and failed sends do not ping; dead tokens are forgotten', async () => {
  const pings = recorder((t) => (t.token === TOKEN_A ? 'gone' : 'ok'));
  const h = harness({ push: pings.push });
  const asha = await user(h, 45, 'Asha');
  const ravi = await user(h, 46, 'Ravi');
  await befriend(h, asha, ravi);
  await register(h, ravi, 'ravi-phone-0001', TOKEN_A);
  await sendText(h, asha, ravi, 'one');
  assert.equal(pings.sent.length, 1);
  assert.deepEqual(await h.store.listPushTokens(ravi.code), [], 'UNREGISTERED token removed');
  await register(h, ravi, 'ravi-phone-0001', TOKEN_B);
  await h.call('PUT', `/v1/blocks/${asha.code}`, {}, ravi.token);
  await sendText(h, asha, ravi, 'two');
  assert.equal(pings.sent.length, 1, 'blocked: nothing reaches Ravi, not even a ping');
});

test('registering: validation, one token per device, the token follows the phone, deletion', async () => {
  const h = harness({ push: recorder().push });
  const asha = await user(h, 47, 'Asha');
  const ravi = await user(h, 48, 'Ravi');
  assert.equal((await h.call('PUT', '/v1/push', { device: 'x', platform: 'fcm', token: TOKEN_A }, asha.token)).status, 400);
  assert.equal((await h.call('PUT', '/v1/push', { device: 'asha-phone-0001', platform: 'apns', token: TOKEN_A }, asha.token)).status, 400);
  assert.equal((await h.call('PUT', '/v1/push', { device: 'asha-phone-0001', platform: 'fcm', token: 'short' }, asha.token)).status, 400);
  await register(h, asha, 'asha-phone-0001', TOKEN_A);
  await register(h, asha, 'asha-phone-0001', TOKEN_B);
  assert.deepEqual((await h.store.listPushTokens(asha.code)).map((t) => t.token), [TOKEN_B], 'replaced, not added');
  // Ravi signs in on the phone Asha used: her account must stop waking it.
  await register(h, ravi, 'ravi-phone-0001', TOKEN_B);
  assert.deepEqual(await h.store.listPushTokens(asha.code), []);
  assert.equal((await h.call('DELETE', '/v1/push/ravi-phone-0001', undefined, ravi.token)).status, 204);
  assert.deepEqual(await h.store.listPushTokens(ravi.code), []);
  await register(h, ravi, 'ravi-phone-0001', TOKEN_A);
  await h.call('DELETE', '/v1/me', undefined, ravi.token);
  assert.deepEqual(await h.store.listPushTokens(ravi.code), [], 'deleting the account removes its tokens');
});

test('without a push sender configured, messages still work', async () => {
  const h = harness();
  const asha = await user(h, 49, 'Asha');
  const ravi = await user(h, 50, 'Ravi');
  await befriend(h, asha, ravi);
  await register(h, ravi, 'ravi-phone-0001', TOKEN_A);
  assert.equal((await sendText(h, asha, ravi, 'hi')).status, 201);
});

test('FCM: a signed service-account assertion, then a high-priority data-only message', async () => {
  const keys = await crypto.subtle.generateKey({ name: 'RSASSA-PKCS1-v1_5', modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: 'SHA-256' }, true, ['sign', 'verify']);
  const pkcs8 = Buffer.from(await crypto.subtle.exportKey('pkcs8', keys.privateKey)).toString('base64');
  const account = {
    type: 'service_account', project_id: 'moodify-test', client_email: 'push@moodify-test.iam.gserviceaccount.com',
    private_key: `-----BEGIN PRIVATE KEY-----\n${pkcs8.match(/.{1,64}/g).join('\n')}\n-----END PRIVATE KEY-----\n`, token_uri: 'https://oauth2.example/token',
  };
  const calls = [];
  const fakeFetch = async (url, init) => {
    calls.push({ url, init });
    if (url === account.token_uri) return new Response(JSON.stringify({ access_token: 'ya29.test', expires_in: 3600 }));
    const { message } = JSON.parse(init.body);
    if (message.token === 'gone-token-000000000000') return new Response('{"error":{"status":"NOT_FOUND","details":[{"errorCode":"UNREGISTERED"}]}}', { status: 404 });
    return new Response('{"name":"projects/moodify-test/messages/1"}');
  };
  // Raw JSON and base64 of it both work as the secret.
  const push = fcmPush(Buffer.from(JSON.stringify(account)).toString('base64'), fakeFetch);
  assert.equal(await push({ platform: 'fcm', token: TOKEN_A }, { kind: 'message' }), 'ok');
  assert.equal(await push({ platform: 'fcm', token: 'gone-token-000000000000' }, { kind: 'message' }), 'gone');
  assert.equal(calls.filter((c) => c.url === account.token_uri).length, 1, 'access token reused');

  const assertion = new URLSearchParams(calls[0].init.body).get('assertion');
  const [header, claims, signature] = assertion.split('.');
  const ok = await crypto.subtle.verify('RSASSA-PKCS1-v1_5', keys.publicKey, base64UrlToBytes(signature), new TextEncoder().encode(`${header}.${claims}`));
  assert.ok(ok, 'assertion signed with the service account key');
  const c = JSON.parse(new TextDecoder().decode(base64UrlToBytes(claims)));
  assert.equal(c.iss, account.client_email);
  assert.equal(c.scope, 'https://www.googleapis.com/auth/firebase.messaging');

  const send = calls[1];
  assert.equal(send.url, 'https://fcm.googleapis.com/v1/projects/moodify-test/messages:send');
  assert.equal(send.init.headers.Authorization, 'Bearer ya29.test');
  assert.deepEqual(JSON.parse(send.init.body).message, { token: TOKEN_A, data: { kind: 'message' }, android: { priority: 'HIGH', ttl: '86400s', collapse_key: 'inbox' } });
  assert.equal(fcmPush(JSON.stringify(account), fakeFetch) instanceof Function, true);
});
