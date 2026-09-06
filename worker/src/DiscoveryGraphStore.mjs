/**
 * In-memory DiscoveryGraphStore for Cloudflare Workers.
 *
 * Same API as server/discovery/DiscoveryGraphStore.mjs but without
 * any file system access (Workers have no filesystem). The graph lives
 * in module-level memory — it persists across requests within the same
 * Worker instance and resets on new deployments or cold starts.
 *
 * For persistent graph storage, connect Cloudflare KV as a future enhancement.
 */

const SCHEMA_VERSION = 1;
const DEFAULT_MAX_NODES = 5_000;
const DEFAULT_MAX_EDGES = 15_000;
const DEFAULT_MAX_SNAPSHOTS = 500;

const emptyGraph = () => ({ schemaVersion: SCHEMA_VERSION, nodes: {}, edges: {}, snapshots: [] });

export class DiscoveryGraphStore {
  constructor({
    maxNodes = DEFAULT_MAX_NODES,
    maxEdges = DEFAULT_MAX_EDGES,
    maxSnapshots = DEFAULT_MAX_SNAPSHOTS,
  } = {}) {
    this.maxNodes = maxNodes;
    this.maxEdges = maxEdges;
    this.maxSnapshots = maxSnapshots;
    this.graph = emptyGraph();
  }

  async record(snapshot) {
    const { graph } = this;

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
      nodeKeys: (snapshot.nodes || []).map((n) => n.key),
      edgeKeys: (snapshot.edges || []).map((e) => e.key),
      hasMore: snapshot.pageInfo?.hasMore || false,
    });

    graph.snapshots = graph.snapshots.slice(-this.maxSnapshots);
    graph.nodes = this.bound(graph.nodes, this.maxNodes, 'lastObservedAt');
    graph.edges = this.bound(graph.edges, this.maxEdges, 'lastObservedAt');

    return this.stats();
  }

  async read() {
    return {
      schemaVersion: this.graph.schemaVersion,
      nodes: Object.values(this.graph.nodes),
      edges: Object.values(this.graph.edges),
      snapshots: [...this.graph.snapshots],
    };
  }

  async stats() {
    return {
      nodes: Object.keys(this.graph.nodes).length,
      edges: Object.keys(this.graph.edges).length,
      snapshots: this.graph.snapshots.length,
    };
  }

  bound(records, limit, timestampField) {
    return Object.fromEntries(
      Object.entries(records)
        .sort(([, l], [, r]) => (l[timestampField] || 0) - (r[timestampField] || 0))
        .slice(-limit),
    );
  }
}

export const discoveryGraphStore = new DiscoveryGraphStore();
