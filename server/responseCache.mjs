/**
 * In-memory cache of catalogue answers (Home, search, artist / album pages,
 * lyrics). Render's free machine is slow and far from most listeners, so a
 * repeat request should never go back to YouTube Music. Bounded by total size.
 */

/** Seconds each kind of answer stays fresh. Unlisted paths aren't cached. */
const RULES = [
  [/^\/api\/discovery\/(home|explore|charts)$/, 10 * 60],
  [/^\/api\/discovery\/(search|suggest)$/, 30 * 60],
  [/^\/api\/search$/, 30 * 60],
  [/^\/api\/discovery\/(related|up-next)\/[^/]+$/, 30 * 60],
  [/^\/api\/discovery\/(artist|album|playlist|browse)\/[^/]+$/, 60 * 60],
  [/^\/api\/tracks\/[^/]+\/lyrics$/, 24 * 60 * 60],
];

export function ttlFor(pathname) {
  for (const [pattern, seconds] of RULES) if (pattern.test(pathname)) return seconds;
  return 0;
}

export function createResponseCache({ maxBytes = 48 * 1024 * 1024, now = () => Date.now() } = {}) {
  const entries = new Map(); // key -> { body, expiresAt }; Map order = least recently used first
  let bytes = 0;
  let hits = 0;
  let misses = 0;
  const drop = (key) => {
    const entry = entries.get(key);
    if (!entry) return;
    bytes -= entry.body.length;
    entries.delete(key);
  };
  return {
    get(key) {
      const entry = entries.get(key);
      if (!entry || entry.expiresAt <= now()) {
        if (entry) drop(key);
        misses += 1;
        return null;
      }
      entries.delete(key);
      entries.set(key, entry);
      hits += 1;
      return entry.body;
    },
    set(key, body, ttlSeconds) {
      if (!ttlSeconds || body.length > maxBytes / 8) return;
      drop(key);
      entries.set(key, { body, expiresAt: now() + ttlSeconds * 1000 });
      bytes += body.length;
      while (bytes > maxBytes && entries.size) drop(entries.keys().next().value);
    },
    stats: () => ({ entries: entries.size, bytes, hits, misses }),
  };
}
