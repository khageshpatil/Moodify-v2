import { mkdir, readFile, rename, writeFile } from 'node:fs/promises';
import { dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const SCHEMA_VERSION = 1;
const DEFAULT_MAX_NODES = 5_000;
const DEFAULT_MAX_EDGES = 15_000;
const DEFAULT_MAX_SNAPSHOTS = 500;
/** How often the graph is written to disk (it lives in memory; writing 18 MB per request was the bottleneck). */
const DEFAULT_SAVE_INTERVAL_MS = 5 * 60_000;

const emptyGraph = () => ({ schemaVersion: SCHEMA_VERSION, nodes: {}, edges: {}, snapshots: [] });

export class DiscoveryGraphStore {
  /**
   * [saveIntervalMs]: 0 writes after every snapshot (tests); otherwise changes are written at most
   * this often, and on [flush] (shutdown). Records over the limits are trimmed in batches, not on
   * every snapshot, so a request never sorts the whole graph.
   */
  constructor({ filePath = fileURLToPath(new URL('../data/discovery-graph.json', import.meta.url)), maxNodes = DEFAULT_MAX_NODES, maxEdges = DEFAULT_MAX_EDGES, maxSnapshots = DEFAULT_MAX_SNAPSHOTS, saveIntervalMs = DEFAULT_SAVE_INTERVAL_MS } = {}) {
    this.filePath = filePath;
    this.maxNodes = maxNodes;
    this.maxEdges = maxEdges;
    this.maxSnapshots = maxSnapshots;
    this.saveIntervalMs = saveIntervalMs;
    this.graph = null;
    this.writeChain = Promise.resolve();
    this.dirty = false;
    this.saveTimer = null;
    this.compact = null;
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
      if (graph.snapshots.length > this.maxSnapshots) graph.snapshots = graph.snapshots.slice(-this.maxSnapshots);
      // Trim with 10% slack when saving lazily, so the sort runs once per few hundred records.
      const slack = this.saveIntervalMs > 0 ? 1.1 : 1;
      if (Object.keys(graph.nodes).length > this.maxNodes * slack) graph.nodes = this.bound(graph.nodes, this.maxNodes, 'lastObservedAt');
      if (Object.keys(graph.edges).length > this.maxEdges * slack) graph.edges = this.bound(graph.edges, this.maxEdges, 'lastObservedAt');
      this.compact = null;
      if (this.saveIntervalMs > 0) {
        this.dirty = true;
        this.scheduleSave();
      } else {
        await this.persist(graph);
      }
    });
    await this.writeChain;
    return this.stats();
  }

  scheduleSave() {
    if (this.saveTimer) return;
    this.saveTimer = setTimeout(() => {
      this.saveTimer = null;
      void this.flush().catch(() => { /* retried at the next change */ });
    }, this.saveIntervalMs);
    this.saveTimer.unref?.();
  }

  /** Writes pending changes now (called on a timer and when the server shuts down). */
  async flush() {
    if (!this.dirty || !this.graph) return;
    this.dirty = false;
    try {
      await this.persist(this.graph);
    } catch (error) {
      this.dirty = true;
      throw error;
    }
  }

  /**
   * What browsers get: the most recently seen part of the graph, small enough to download
   * (the full graph is ~18 MB). Edges whose ends both stay in the set, no snapshot history.
   */
  async readCompact({ maxNodes = 1200, maxEdges = 3000 } = {}) {
    if (this.compact) return this.compact;
    const graph = await this.load();
    const recent = (records, limit) => Object.values(records).sort((a, b) => (b.lastObservedAt || 0) - (a.lastObservedAt || 0)).slice(0, limit);
    const nodes = recent(graph.nodes, maxNodes);
    const keep = new Set(nodes.map((node) => node.key));
    const edges = recent(graph.edges, maxEdges * 4).filter((edge) => keep.has(edge.fromKey) && keep.has(edge.toKey)).slice(0, maxEdges);
    this.compact = { schemaVersion: graph.schemaVersion, nodes, edges, snapshots: [] };
    return this.compact;
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
