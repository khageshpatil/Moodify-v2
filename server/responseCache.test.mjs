import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createResponseCache, ttlFor } from './responseCache.mjs';

test('catalogue answers are cached for a while; streams and unknown paths never', () => {
  assert.equal(ttlFor('/api/discovery/home'), 600);
  assert.equal(ttlFor('/api/discovery/search'), 1800);
  assert.equal(ttlFor('/api/discovery/artist/UC123'), 3600);
  assert.equal(ttlFor('/api/tracks/abc/lyrics'), 86400);
  assert.equal(ttlFor('/api/media/abc'), 0);
  assert.equal(ttlFor('/api/health'), 0);
});

test('entries expire, and the least recently used go first when full', () => {
  let clock = 0;
  const cache = createResponseCache({ maxBytes: 80, now: () => clock });
  cache.set('a', 'x'.repeat(10), 60);
  assert.equal(cache.get('a'), 'x'.repeat(10));
  clock += 61_000;
  assert.equal(cache.get('a'), null, 'expired');
  cache.set('b', 'b'.repeat(10), 60);
  cache.set('c', 'c'.repeat(10), 60);
  cache.get('b'); // b is now the most recently used
  for (let i = 0; i < 7; i += 1) cache.set(`k${i}`, 'k'.repeat(10), 60);
  assert.equal(cache.get('c'), null, 'c was evicted first');
  cache.set('huge', 'h'.repeat(20), 60);
  assert.equal(cache.get('huge'), null, 'too big to keep');
});
