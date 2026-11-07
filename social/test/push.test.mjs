import { test } from 'node:test';
import assert from 'node:assert/strict';
import { identityFromSeed, seal } from '../src/client-keys.mjs';
import { base64UrlToBytes, bytesToBase64Url } from '../src/identity.mjs';
import { combinePush, fcmPush, webPush } from '../src/push.mjs';
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

const P256DH = 'B' + 'A'.repeat(86); // 87 base64url characters = 65 bytes
const AUTH = 'C'.repeat(22);
const subscription = (endpoint = 'https://fcm.googleapis.com/fcm/send/abc123') => ({ endpoint, keys: { p256dh: P256DH, auth: AUTH } });

test('browsers register a push subscription; the public key is served', async () => {
  const h = harness({ push: recorder().push, vapidPublicKey: 'BPublicKeyForTests' });
  const asha = await user(h, 51, 'Asha');
  assert.equal((await h.call('GET', '/v1/push/vapid')).body.publicKey, 'BPublicKeyForTests');
  assert.equal((await harness().call('GET', '/v1/push/vapid')).status, 404, 'not set up: browsers skip notifications');
  const put = (sub) => h.call('PUT', '/v1/push', { device: 'web-asha-laptop', platform: 'webpush', subscription: sub }, asha.token);
  assert.equal((await put(subscription('http://push.example/x'))).status, 400, 'https only');
  assert.equal((await put({ ...subscription(), keys: { p256dh: 'short', auth: AUTH } })).status, 400);
  assert.equal((await put({ ...subscription(), extra: 'dropped' })).status, 204);
  const [stored] = await h.store.listPushTokens(asha.code);
  assert.equal(stored.platform, 'webpush');
  assert.deepEqual(JSON.parse(stored.token), subscription(), 'only endpoint and keys are kept');
});

test('Web Push: an empty ping signed with VAPID; 410 means the subscription is gone', async () => {
  const pair = await crypto.subtle.generateKey({ name: 'ECDSA', namedCurve: 'P-256' }, true, ['sign', 'verify']);
  const privateKey = (await crypto.subtle.exportKey('jwk', pair.privateKey)).d;
  const publicKey = bytesToBase64Url(new Uint8Array(await crypto.subtle.exportKey('raw', pair.publicKey)));
  const calls = [];
  const fakeFetch = async (url, init) => {
    calls.push({ url, init });
    return new Response(null, { status: url.endsWith('/expired') ? 410 : 201 });
  };
  const push = webPush({ privateKey, publicKey, subject: 'https://moodify.test' }, fakeFetch);
  const target = (endpoint) => ({ platform: 'webpush', token: JSON.stringify(subscription(endpoint)) });
  assert.equal(await push(target('https://fcm.googleapis.com/fcm/send/abc123'), { kind: 'message' }), 'ok');
  assert.equal(await push(target('https://updates.push.services.mozilla.com/wpush/v2/expired'), { kind: 'message' }), 'gone');
  assert.equal(await push({ platform: 'fcm', token: TOKEN_A }, {}), 'error');

  const { init } = calls[0];
  assert.equal(init.body, undefined, 'no payload: nothing for the push service to read');
  assert.equal(init.headers.Urgency, 'high');
  assert.equal(init.headers.Topic, 'inbox');
  assert.equal(init.headers.TTL, '86400');
  const [, jwt, k] = init.headers.Authorization.match(/^vapid t=([^,]+), k=(.+)$/);
  assert.equal(k, publicKey);
  const [header, claims, signature] = jwt.split('.');
  const verifyKey = await crypto.subtle.importKey('raw', base64UrlToBytes(publicKey), { name: 'ECDSA', namedCurve: 'P-256' }, false, ['verify']);
  assert.ok(await crypto.subtle.verify({ name: 'ECDSA', hash: 'SHA-256' }, verifyKey, base64UrlToBytes(signature), new TextEncoder().encode(`${header}.${claims}`)));
  const c = JSON.parse(new TextDecoder().decode(base64UrlToBytes(claims)));
  assert.equal(c.aud, 'https://fcm.googleapis.com');
  assert.equal(c.sub, 'https://moodify.test');
  // A second audience gets its own token; the first is reused.
  assert.notEqual(calls[1].init.headers.Authorization, init.headers.Authorization);
  await push(target('https://fcm.googleapis.com/fcm/send/other'), {});
  assert.equal(calls[2].init.headers.Authorization, init.headers.Authorization);
});

test('pings go to the sender for each platform', async () => {
  const fcm = recorder();
  const web = recorder();
  const push = combinePush({ fcm: fcm.push, webpush: web.push });
  await push({ platform: 'fcm', token: TOKEN_A }, { kind: 'message' });
  await push({ platform: 'webpush', token: '{}' }, { kind: 'message' });
  assert.equal(fcm.sent.length, 1);
  assert.equal(web.sent.length, 1);
  assert.equal(await combinePush({ fcm: fcm.push, webpush: null })({ platform: 'webpush', token: '{}' }, {}), 'error');
  assert.equal(combinePush({ fcm: null, webpush: null }), undefined);
});
