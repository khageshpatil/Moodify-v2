import { mkdir, readFile, rename, writeFile } from 'node:fs/promises';
import { dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const SCHEMA_VERSION = 1;
const DEFAULT_MAX_NODES = 5_000;
const DEFAULT_MAX_EDGES = 15_000;
const DEFAULT_MAX_SNAPSHOTS = 500;

const emptyGraph = () => ({ schemaVersion: SCHEMA_VERSION, nodes: {}, edges: {}, snapshots: [] });

export class DiscoveryGraphStore {
  constructor({ filePath = fileURLToPath(new URL('../data/discovery-graph.json', import.meta.url)), maxNodes = DEFAULT_MAX_NODES, maxEdges = DEFAULT_MAX_EDGES, maxSnapshots = DEFAULT_MAX_SNAPSHOTS } = {}) {
    this.filePath = filePath;
    this.maxNodes = maxNodes;
    this.maxEdges = maxEdges;
    this.maxSnapshots = maxSnapshots;
    this.graph = null;
    this.writeChain = Promise.resolve();
  }

  async load() {
    if (this.graph) return this.graph;
    try {
      const parsed = JSON.parse(await readFile(this.filePath, 'utf8'));
      this.graph = parsed?.schemaVersion === SCHEMA_VERSION && parsed.nodes && parsed.edges && parsed.snapshots
        ? parsed
        : emptyGraph();
    } catch { this.graph = emptyGraph(); }
    return this.graph;
  }

  async record(snapshot) {
    this.writeChain = this.writeChain.catch(() => undefined).then(async () => {
      const graph = await this.load();
      for (const node of snapshot.nodes || []) {
        const previous = graph.nodes[node.key];
        graph.nodes[node.key] = {
          ...(previous || {}),
          ...node,
          firstObservedAt: previous?.firstObservedAt || node.observedAt,
          lastObservedAt: node.observedAt,
          observationCount: (previous?.observationCount || 0) + 1,
        };
      }
      for (const edge of snapshot.edges || []) {
        const previous = graph.edges[edge.key];
        graph.edges[edge.key] = {
          ...(previous || {}),
          ...edge,
          firstObservedAt: previous?.firstObservedAt || edge.observedAt,
          lastObservedAt: edge.observedAt,
          observationCount: (previous?.observationCount || 0) + 1,
        };
      }
      graph.snapshots.push({
        snapshotId: snapshot.snapshotId,
        provider: snapshot.provider,
        operation: snapshot.operation,
        surface: snapshot.surface,
        sourceId: snapshot.sourceId,
        observedAt: snapshot.observedAt,
        nodeKeys: (snapshot.nodes || []).map((node) => node.key),
        edgeKeys: (snapshot.edges || []).map((edge) => edge.key),
        hasMore: snapshot.pageInfo?.hasMore || false,
      });
      graph.snapshots = graph.snapshots.slice(-this.maxSnapshots);
      graph.nodes = this.bound(graph.nodes, this.maxNodes, 'lastObservedAt');
      graph.edges = this.bound(graph.edges, this.maxEdges, 'lastObservedAt');
      await this.persist(graph);
    });
    await this.writeChain;
    return this.stats();
  }

  async read() {
    const graph = await this.load();
    return {
      schemaVersion: graph.schemaVersion,
      nodes: Object.values(graph.nodes),
      edges: Object.values(graph.edges),
      snapshots: [...graph.snapshots],
    };
  }

  async stats() {
    const graph = await this.load();
    return { nodes: Object.keys(graph.nodes).length, edges: Object.keys(graph.edges).length, snapshots: graph.snapshots.length };
  }

  bound(records, limit, timestampField) {
    return Object.fromEntries(Object.entries(records).sort(([, left], [, right]) => (left[timestampField] || 0) - (right[timestampField] || 0)).slice(-limit));
  }

  async persist(graph) {
    await mkdir(dirname(this.filePath), { recursive: true });
    const temporaryPath = `${this.filePath}.tmp`;
    await writeFile(temporaryPath, JSON.stringify(graph), 'utf8');
    await rename(temporaryPath, this.filePath);
  }
}

export const discoveryGraphStore = new DiscoveryGraphStore();
