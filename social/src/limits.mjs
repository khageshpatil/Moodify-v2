/**
 * Abuse limits that need no storage (docs/social-protocol/limits.md).
 *
 * Burst limits live in the Worker's memory, so they are per isolate and
 * best-effort: they stop one client hammering an endpoint, not a distributed
 * attack. Daily caps that must hold use D1 counts instead (api.mjs).
 */

/** Sliding-window counts per key, e.g. "auth:<ip>" → at most `limit` hits per `windowMs`. */
export function createBurstLimiter({ maxKeys = 10_000 } = {}) {
  const hits = new Map();
  return {
    allow(key, limit, windowMs, nowMs = Date.now()) {
      const recent = (hits.get(key) ?? []).filter((t) => t > nowMs - windowMs);
      if (recent.length >= limit) {
        hits.set(key, recent);
        return false;
      }
      recent.push(nowMs);
      hits.delete(key); // re-insert: Map order doubles as "least recently used"
      hits.set(key, recent);
      if (hits.size > maxKeys) hits.delete(hits.keys().next().value);
      return true;
    },
  };
}

/**
 * Per-connection message limits for live sockets (hub, rooms): a size cap and
 * a token bucket. `check` answers "ok", "too_big" or "slow_down".
 */
export function createMessageGate({ ratePerSecond = 10, burst = 30, maxBytes = 16 * 1024 } = {}) {
  const buckets = new WeakMap();
  return {
    check(socket, raw, nowMs = Date.now()) {
      const size = typeof raw === 'string' ? raw.length : raw?.byteLength ?? 0;
      if (size > maxBytes) return 'too_big';
      const key = socket.ws ?? socket;
      const bucket = buckets.get(key) ?? { tokens: burst, at: nowMs };
      bucket.tokens = Math.min(burst, bucket.tokens + ((nowMs - bucket.at) / 1000) * ratePerSecond);
      bucket.at = nowMs;
      buckets.set(key, bucket);
      if (bucket.tokens < 1) return 'slow_down';
      bucket.tokens -= 1;
      return 'ok';
    },
  };
}
