import assert from 'node:assert/strict';
import { test } from 'node:test';
import { playbackConfig } from '../src/playback.mjs';
import { harness } from './helpers.mjs';

test('no config: the app keeps its own defaults', () => {
  assert.deepEqual(playbackConfig(undefined), {});
  assert.deepEqual(playbackConfig(''), {});
  assert.deepEqual(playbackConfig('not json'), {});
  assert.deepEqual(playbackConfig('[1,2]'), {});
});

test('only well-formed fields are served', () => {
  const config = playbackConfig(JSON.stringify({
    clients: ['ANDROID_VR', 'VISIONOS', 'ANDROID_VR', 'bad name', 42, ''],
    hedgeAfterMs: 1200.4,
    perClientMs: -5,
    budgetMs: 'soon',
    extra: true,
  }));
  assert.deepEqual(config, { clients: ['ANDROID_VR', 'VISIONOS'], hedgeAfterMs: 1200 });
});

test('GET /v1/playback is public and cacheable', async () => {
  const h = harness({ playback: { clients: ['ANDROID_VR'], budgetMs: 12000 } });
  const res = await h.call('GET', '/v1/playback');
  assert.equal(res.status, 200);
  assert.deepEqual(res.body, { clients: ['ANDROID_VR'], budgetMs: 12000 });
});
