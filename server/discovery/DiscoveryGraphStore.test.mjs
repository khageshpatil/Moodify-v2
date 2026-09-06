import assert from 'node:assert/strict';
import { mkdtemp, readFile } from 'node:fs/promises';
import path from 'node:path';
import { tmpdir } from 'node:os';
import test from 'node:test';
import { DiscoveryGraphStore } from './DiscoveryGraphStore.mjs';

const snapshot = (id, observedAt) => ({
  snapshotId: id,
  provider: 'youtube-music',
  operation: 'test',
  surface: 'playlist',
  sourceId: 'playlist-1',
  observedAt,
  nodes: [{ key: 'youtube-music:track-1', type: 'track', provider: 'youtube-music', providerId: 'track-1', observedAt, track: { id: 'track-1', title: 'Track', artist: 'Artist' } }],
  edges: [],
  pageInfo: { hasMore: false },
});

test('persists bounded dated graph snapshots and deduplicates provider nodes', async () => {
  const directory = await mkdtemp(path.join(tmpdir(), 'moodify-discovery-'));
  const filePath = path.join(directory, 'graph.json');
  const store = new DiscoveryGraphStore({ filePath, maxNodes: 1, maxSnapshots: 2 });

  await store.record(snapshot('first', 1));
  await store.record(snapshot('second', 2));
  const graph = await store.read();

  assert.equal(graph.nodes.length, 1);
  assert.equal(graph.nodes[0].observationCount, 2);
  assert.equal(graph.nodes[0].firstObservedAt, 1);
  assert.equal(graph.nodes[0].lastObservedAt, 2);
  assert.equal(graph.snapshots.length, 2);
  assert.ok((await readFile(filePath, 'utf8')).includes('youtube-music:track-1'));
});

test('continues accepting snapshots after a transient persistence failure', async () => {
  const directory = await mkdtemp(path.join(tmpdir(), 'moodify-discovery-recovery-'));
  const filePath = path.join(directory, 'graph.json');
  const store = new DiscoveryGraphStore({ filePath });
  const persist = store.persist.bind(store);
  let attempts = 0;
  store.persist = async (graph) => {
    attempts += 1;
    if (attempts === 1) throw new Error('temporary disk failure');
    return persist(graph);
  };

  await assert.rejects(store.record(snapshot('failed-write', 1)), /temporary disk failure/);
  await store.record(snapshot('recovered-write', 2));
  assert.equal((await store.read()).snapshots.length, 2);
});
