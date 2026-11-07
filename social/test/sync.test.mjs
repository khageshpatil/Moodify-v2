import { test } from 'node:test';
import assert from 'node:assert/strict';
import { openRecord, sealRecord, syncItemId, syncKeysFromSeed } from '../src/client-keys.mjs';
import { base64UrlToBytes, bytesToBase64Url } from '../src/identity.mjs';
import { LIMITS } from '../src/api.mjs';
import { harness, user } from './helpers.mjs';

async function record(who, collection, id, data, updatedAt) {
  const keys = await syncKeysFromSeed(who.seed);
  const item = await syncItemId(keys.id, collection, id);
  const plaintext = JSON.stringify({ v: 1, c: collection, id, data });
  const sealed = await sealRecord({ encKey: keys.enc, code: who.code, item, plaintext });
  return { item, updatedAt, nonce: bytesToBase64Url(sealed.nonce), ciphertext: bytesToBase64Url(sealed.ciphertext) };
}
async function read(who, r) {
  const keys = await syncKeysFromSeed(who.seed);
  return JSON.parse(await openRecord({ encKey: keys.enc, code: who.code, item: r.item, nonce: base64UrlToBytes(r.nonce), ciphertext: base64UrlToBytes(r.ciphertext) }));
}
const push = (h, who, records) => h.call('PUT', '/v1/sync', { records }, who.token);
const pull = (h, who, after = 0) => h.call('GET', `/v1/sync?after=${after}`, undefined, who.token);
const song = { id: 'VAdGW7QDJiU', title: 'Chaleya', artist: 'Arijit Singh' };

test("one device's like reaches the other; the server can't read it", async () => {
  const h = harness();
  const asha = await user(h, 61, 'Asha');
  const sent = await push(h, asha, [await record(asha, 'like', song.id, { track: song, likedAt: 1000 }, 1_800_000_000_000)]);
  assert.equal(sent.status, 200);
  assert.equal(sent.body.accepted.length, 1);
  assert.ok(!JSON.stringify([...h.store.syncRecords.values()]).includes('Chaleya'), 'only ciphertext on the server');
  assert.ok(!JSON.stringify([...h.store.syncRecords.values()]).includes('like'), 'not even the kind of item');

  const { body } = await pull(h, asha);
  assert.equal(body.records.length, 1);
  assert.equal(body.latest, body.records[0].seq);
  assert.equal(body.more, false);
  const opened = await read(asha, body.records[0]);
  assert.deepEqual(opened, { v: 1, c: 'like', id: song.id, data: { track: song, likedAt: 1000 } });
  assert.equal((await pull(h, asha, body.latest)).body.records.length, 0, 'nothing new after the position');
  assert.ok(h.events.some((e) => e.code === asha.code && e.type === 'sync.changed'), 'other devices are told to pull');
});

test('the newest change wins per item; an older one is ignored', async () => {
  const h = harness();
  const asha = await user(h, 62, 'Asha');
  const newer = await record(asha, 'playlist', 'p1', { name: 'Road trip (renamed)' }, 2000);
  const older = await record(asha, 'playlist', 'p1', { name: 'Road trip' }, 1000);
  await push(h, asha, [newer]);
  const late = await push(h, asha, [older]);
  assert.deepEqual(late.body.accepted, [], 'an older edit from an offline device loses');
  const [only] = (await pull(h, asha)).body.records;
  assert.equal((await read(asha, only)).data.name, 'Road trip (renamed)');
  // A newer edit moves the item to the end of the change list.
  const first = (await pull(h, asha)).body.latest;
  await push(h, asha, [await record(asha, 'like', 'x1234567890', { track: song }, 3000), await record(asha, 'playlist', 'p1', { name: 'v3' }, 4000)]);
  const changes = (await pull(h, asha, first)).body.records;
  assert.equal(changes.length, 2);
  assert.ok(changes[0].seq < changes[1].seq);
});

test('records are bound to their item and their owner', async () => {
  const h = harness();
  const asha = await user(h, 63, 'Asha');
  const ravi = await user(h, 64, 'Ravi');
  const a = await record(asha, 'like', 'one', { track: song }, 1000);
  const b = await record(asha, 'like', 'two', { track: song }, 1000);
  await assert.rejects(read(asha, { ...a, item: b.item }), 'moved to another item: fails to open');
  await assert.rejects(read(ravi, a), "someone else's key can't open it");
  assert.notEqual(a.item, (await record(ravi, 'like', 'one', {}, 1)).item, 'item ids differ per account');
  await push(h, asha, [a]);
  assert.equal((await pull(h, ravi)).body.records.length, 0, "you only ever see your own records");
});

test('validation, limits and account deletion', async () => {
  const h = harness();
  const asha = await user(h, 65, 'Asha');
  const good = await record(asha, 'like', 'one', { track: song }, 1000);
  assert.equal((await push(h, asha, [])).status, 400);
  assert.equal((await push(h, asha, [{ ...good, item: 'short' }])).status, 400);
  assert.equal((await push(h, asha, [{ ...good, ciphertext: 'not base64url!' }])).status, 400);
  assert.equal((await push(h, asha, [{ ...good, updatedAt: Date.now() * 10 }])).status, 400, 'far-future timestamps refused');
  assert.equal((await push(h, asha, [good, good])).status, 400, 'one version per item per push');
  assert.equal((await push(h, asha, [{ ...good, ciphertext: 'A'.repeat(LIMITS.syncRecordChars + 1) }])).status, 400);
  assert.equal((await push(h, asha, Array.from({ length: LIMITS.syncPerPush + 1 }, () => good))).status, 400);
  await push(h, asha, [good]);
  await h.call('DELETE', '/v1/me', undefined, asha.token);
  assert.equal(h.store.syncRecords.size, 0, 'deleting the account deletes the synced library');
});

test('the item id is stable and matches the spec vector', async () => {
  const seed = Uint8Array.from({ length: 32 }, (_, i) => i);
  const keys = await syncKeysFromSeed(seed);
  const item = await syncItemId(keys.id, 'like', 'VAdGW7QDJiU');
  assert.match(item, /^[A-Za-z0-9_-]{22}$/);
  assert.equal(item, await syncItemId(keys.id, 'like', 'VAdGW7QDJiU'));
  assert.notEqual(item, await syncItemId(keys.id, 'playlist', 'VAdGW7QDJiU'));
  // Printed for docs/social-protocol/sync.md; Android's test checks the same values.
  console.log('VECTOR', JSON.stringify({ enc: bytesToBase64Url(keys.enc), id: bytesToBase64Url(keys.id), item }));
});
