import { test } from 'node:test';
import assert from 'node:assert/strict';
import { cleanAvatar } from '../src/api.mjs';
import { harness, user } from './helpers.mjs';

const befriend = async (h, x, y) => {
  await h.call('POST', '/v1/friends/requests', { code: y.code }, x.token);
  await h.call('POST', `/v1/friends/requests/${x.code}/accept`, undefined, y.token);
};
const song = { kind: 'song', id: 'VAdGW7QDJiU', title: 'Chaleya', artist: 'Arijit Singh', artworkUrl: 'https://i.ytimg.com/vi/VAdGW7QDJiU/hqdefault.jpg' };

test('an emoji or a song as your picture; friends see it and are told', async () => {
  const h = harness();
  const asha = await user(h, 71, 'Asha');
  const ravi = await user(h, 72, 'Ravi');
  await befriend(h, asha, ravi);
  const set = await h.call('PATCH', '/v1/me', { avatar: { kind: 'emoji', emoji: '🎧', color: 3 } }, asha.token);
  assert.equal(set.status, 200);
  assert.deepEqual(set.body.account.avatar, { kind: 'emoji', emoji: '🎧', color: 3 });
  assert.equal(set.body.account.displayName, 'Asha', 'the name is unchanged');
  assert.ok(h.events.some((e) => e.code === ravi.code && e.type === 'friend.updated' && e.body.code === asha.code));
  const seen = (await h.call('GET', '/v1/friends', undefined, ravi.token)).body.friends.find((f) => f.code === asha.code);
  assert.deepEqual(seen.avatar, { kind: 'emoji', emoji: '🎧', color: 3 });

  await h.call('PATCH', '/v1/me', { avatar: song }, asha.token);
  const again = (await h.call('GET', '/v1/friends', undefined, ravi.token)).body.friends.find((f) => f.code === asha.code);
  assert.equal(again.avatar.kind, 'song');
  assert.equal(again.avatar.title, 'Chaleya');

  await h.call('PATCH', '/v1/me', { avatar: null }, asha.token);
  assert.equal((await h.call('GET', '/v1/me', undefined, asha.token)).body.account.avatar, null, 'null removes it');
});

test('only clean avatars are kept', async () => {
  const h = harness();
  const asha = await user(h, 73, 'Asha');
  for (const bad of [
    { kind: 'emoji', emoji: 'hi', color: 1 },
    { kind: 'emoji', emoji: '🎧', color: 12 },
    { kind: 'emoji', emoji: '<img>', color: 1 },
    { kind: 'song', id: 'short', artworkUrl: 'https://x/y.jpg' },
    { kind: 'song', id: 'VAdGW7QDJiU', artworkUrl: 'http://insecure/y.jpg' },
    { kind: 'photo', url: 'https://x/y.jpg' },
    'just text',
  ]) {
    assert.equal((await h.call('PATCH', '/v1/me', { avatar: bad }, asha.token)).status, 400, JSON.stringify(bad));
  }
  assert.equal((await h.call('PATCH', '/v1/me', {}, asha.token)).status, 400);
  assert.equal(JSON.parse(cleanAvatar({ ...song, title: 'x'.repeat(300), extra: 'dropped' })).title.length, 100);
  assert.equal(JSON.parse(cleanAvatar({ ...song, extra: 'dropped' })).extra, undefined);
});
