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
  const store = new DiscoveryGraphStore({ filePath, maxNodes: 1, maxSnapshots: 2, saveIntervalMs: 0 });

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
  const store = new DiscoveryGraphStore({ filePath, saveIntervalMs: 0 });
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

test('saves lazily: nothing is written per snapshot, flush writes the changes once', async () => {
  const directory = await mkdtemp(path.join(tmpdir(), 'moodify-discovery-lazy-'));
  const filePath = path.join(directory, 'graph.json');
  const store = new DiscoveryGraphStore({ filePath, saveIntervalMs: 60_000 });
  let writes = 0;
  const persist = store.persist.bind(store);
  store.persist = async (graph) => { writes += 1; return persist(graph); };
  for (let i = 1; i <= 20; i += 1) await store.record(snapshot(`s${i}`, i));
  assert.equal(writes, 0, 'no write per request');
  await store.flush();
  assert.equal(writes, 1);
  assert.ok((await readFile(filePath, 'utf8')).includes('youtube-music:track-1'));
  await store.flush();
  assert.equal(writes, 1, 'nothing new to write');
  clearTimeout(store.saveTimer);
});

test('browsers get a compact, recent slice of the graph', async () => {
  const directory = await mkdtemp(path.join(tmpdir(), 'moodify-discovery-compact-'));
  const store = new DiscoveryGraphStore({ filePath: path.join(directory, 'graph.json'), saveIntervalMs: 0 });
  const node = (id, at) => ({ key: `youtube-music:${id}`, type: 'track', provider: 'youtube-music', providerId: id, observedAt: at });
  await store.record({ ...snapshot('a', 1), nodes: [node('old', 1), node('new', 5), node('newer', 6)],
    edges: [{ key: 'e1', fromKey: 'youtube-music:new', toKey: 'youtube-music:newer', observedAt: 6 }, { key: 'e2', fromKey: 'youtube-music:old', toKey: 'youtube-music:new', observedAt: 1 }] });
  const compact = await store.readCompact({ maxNodes: 2, maxEdges: 10 });
  assert.deepEqual(compact.nodes.map((n) => n.providerId), ['newer', 'new']);
  assert.deepEqual(compact.edges.map((e) => e.key), ['e1'], 'edges to dropped nodes are left out');
  assert.equal(compact.snapshots.length, 0);
});
