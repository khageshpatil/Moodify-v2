/**
 * Library sync between your devices (docs/social-protocol/sync.md). The
 * network, crypto, storage and the library itself are passed in, so the
 * logic is testable and Android (data/social/LibrarySync.kt) can mirror it.
 *
 * One pass: pull what changed since this device's position and apply it,
 * then compare the library with what was last synced and push the
 * differences. The newest change wins per item.
 */

/** `${collection}:${id}` */
export type SyncKey = string;

export interface SyncChange {
  c: string;
  id: string;
  /** null: the item was deleted. */
  data: unknown | null;
  /** When the change was made (ms), for an item's "added at" when the data has none. */
  updatedAt: number;
}

export interface LibraryAdapter {
  /** Collections this device syncs; records of other kinds are left alone. */
  collections: readonly string[];
  /** Collections merged instead of replaced (recent plays). */
  merge?: Partial<Record<string, (local: unknown | undefined, remote: unknown) => unknown>>;
  /** The library now, in the wire format, by key. */
  snapshot(): Map<SyncKey, unknown>;
  apply(changes: SyncChange[]): void | Promise<void>;
}

export interface WireRecord { item: string; seq?: number; updatedAt: number; nonce: string; ciphertext: string }

export interface SyncTransport {
  pull(after: number): Promise<{ records: WireRecord[]; latest: number; more: boolean }>;
  push(records: WireRecord[]): Promise<{ accepted: string[]; latest: number }>;
}

export interface SyncCrypto {
  itemId(c: string, id: string): Promise<string>;
  seal(item: string, plaintext: string): Promise<{ nonce: string; ciphertext: string }>;
  open(item: string, nonce: string, ciphertext: string): Promise<string>;
}

export interface IndexEntry { item: string; updatedAt: number; hash: string; deleted?: boolean }
export interface SyncState { cursor: number; index: Record<SyncKey, IndexEntry> }
export const emptySyncState = (): SyncState => ({ cursor: 0, index: {} });

export const PUSH_BATCH = 100;
const DELETED = 'deleted';

/** JSON with sorted keys, so equal data hashes equally. */
export function canonical(value: unknown): string {
  if (value === null || typeof value !== 'object') return JSON.stringify(value ?? null);
  if (Array.isArray(value)) return `[${value.map(canonical).join(',')}]`;
  const entries = Object.entries(value as Record<string, unknown>).filter(([, v]) => v !== undefined).sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0));
  return `{${entries.map(([k, v]) => `${JSON.stringify(k)}:${canonical(v)}`).join(',')}}`;
}

/** A fast 53-bit hash (cyrb53); only compared on this device. */
export function hashData(value: unknown): string {
  const text = canonical(value);
  let h1 = 0xdeadbeef;
  let h2 = 0x41c6ce57;
  for (let i = 0; i < text.length; i++) {
    const ch = text.charCodeAt(i);
    h1 = Math.imul(h1 ^ ch, 2654435761);
    h2 = Math.imul(h2 ^ ch, 1597334677);
  }
  h1 = Math.imul(h1 ^ (h1 >>> 16), 2246822507) ^ Math.imul(h2 ^ (h2 >>> 13), 3266489909);
  h2 = Math.imul(h2 ^ (h2 >>> 16), 2246822507) ^ Math.imul(h1 ^ (h1 >>> 13), 3266489909);
  return (4294967296 * (2097151 & h2) + (h1 >>> 0)).toString(36);
}

const splitKey = (key: SyncKey) => { const at = key.indexOf(':'); return { c: key.slice(0, at), id: key.slice(at + 1) }; };

interface Plain { v: 1; c: string; id: string; data?: unknown; deleted?: true }

export interface SyncResult { state: SyncState; pulled: number; pushed: number }

/**
 * One full pass. [onState] is called after each step so progress survives a
 * closed tab or app; the returned state is the final one.
 */
export async function syncOnce(opts: {
  adapter: LibraryAdapter;
  transport: SyncTransport;
  crypto: SyncCrypto;
  state: SyncState;
  now?: () => number;
  onState?: (state: SyncState) => void;
}): Promise<SyncResult> {
  const { adapter, transport, crypto } = opts;
  const now = opts.now ?? Date.now;
  const managed = new Set(adapter.collections);
  const state: SyncState = { cursor: opts.state.cursor, index: { ...opts.state.index } };
  let pulled = 0;

  // ── 1. Pull and apply ──────────────────────────────────────────────────────
  const pullAll = async () => {
    for (let page = 0; page < 200; page++) {
      const { records, latest, more } = await transport.pull(state.cursor);
      const changes: SyncChange[] = [];
      const touched: Array<{ key: SyncKey; remoteHash: string; merged: boolean }> = [];
      const local = adapter.snapshot();
      for (const r of records) {
        let plain: Plain;
        try { plain = JSON.parse(await crypto.open(r.item, r.nonce, r.ciphertext)); } catch { continue; /* not ours, or newer than this app */ }
        if (plain?.v !== 1 || typeof plain.c !== 'string' || typeof plain.id !== 'string' || !managed.has(plain.c)) continue;
        const key = `${plain.c}:${plain.id}`;
        const known = state.index[key];
        if (known && r.updatedAt <= known.updatedAt) continue; // our own echo, or older
        if (plain.deleted) {
          // Something this device has but never synced isn't someone else's to delete.
          if (!known && local.has(key)) { state.index[key] = { item: r.item, updatedAt: r.updatedAt, hash: DELETED, deleted: true }; continue; }
          changes.push({ c: plain.c, id: plain.id, data: null, updatedAt: r.updatedAt });
          state.index[key] = { item: r.item, updatedAt: r.updatedAt, hash: DELETED, deleted: true };
          continue;
        }
        const merge = adapter.merge?.[plain.c];
        const data = merge ? merge(local.get(key), plain.data) : plain.data;
        changes.push({ c: plain.c, id: plain.id, data, updatedAt: r.updatedAt });
        state.index[key] = { item: r.item, updatedAt: r.updatedAt, hash: '' };
        touched.push({ key, remoteHash: hashData(plain.data), merged: !!merge });
        pulled++;
      }
      if (changes.length) await adapter.apply(changes);
      // Record what this library now holds for those items, so a slightly
      // different local representation isn't pushed back as a "change".
      if (touched.length) {
        const after = adapter.snapshot();
        for (const t of touched) {
          const entry = state.index[t.key];
          const mine = after.get(t.key);
          if (mine === undefined) { entry.hash = DELETED; entry.deleted = true; continue; }
          const mineHash = hashData(mine);
          // A merge that added plays of ours: leave it "changed" so it's pushed.
          entry.hash = t.merged && mineHash !== t.remoteHash ? t.remoteHash : mineHash;
        }
      }
      state.cursor = Math.max(state.cursor, latest);
      opts.onState?.(state);
      if (!more) break;
    }
  };
  await pullAll();

  // ── 2. Compare and push ────────────────────────────────────────────────────
  const snapshot = adapter.snapshot();
  const outgoing: Array<{ key: SyncKey; plain: Plain; hash: string; deleted: boolean }> = [];
  for (const [key, data] of snapshot) {
    const { c, id } = splitKey(key);
    if (!managed.has(c)) continue;
    const hash = hashData(data);
    if (state.index[key]?.hash === hash) continue;
    outgoing.push({ key, plain: { v: 1, c, id, data }, hash, deleted: false });
  }
  for (const [key, entry] of Object.entries(state.index)) {
    const { c, id } = splitKey(key);
    if (!managed.has(c) || entry.deleted || snapshot.has(key)) continue;
    outgoing.push({ key, plain: { v: 1, c, id, deleted: true }, hash: DELETED, deleted: true });
  }
  let pushed = 0;
  let rejected = false;
  for (let i = 0; i < outgoing.length; i += PUSH_BATCH) {
    const batch = outgoing.slice(i, i + PUSH_BATCH);
    const records: WireRecord[] = [];
    const meta = new Map<string, (typeof batch)[number] & { updatedAt: number }>();
    for (const o of batch) {
      const { c, id } = splitKey(o.key);
      const item = state.index[o.key]?.item ?? await crypto.itemId(c, id);
      const updatedAt = Math.max(now(), (state.index[o.key]?.updatedAt ?? 0) + 1);
      const sealed = await crypto.seal(item, JSON.stringify(o.plain));
      records.push({ item, updatedAt, ...sealed });
      meta.set(item, { ...o, updatedAt });
    }
    const { accepted } = await transport.push(records);
    for (const item of accepted) {
      const o = meta.get(item);
      if (!o) continue;
      state.index[o.key] = { item, updatedAt: o.updatedAt, hash: o.hash, ...(o.deleted ? { deleted: true } : {}) };
      pushed++;
    }
    if (accepted.length < records.length) rejected = true;
    opts.onState?.(state);
  }
  // Something newer was already there: fetch it.
  if (rejected) await pullAll();
  return { state, pulled, pushed };
}
