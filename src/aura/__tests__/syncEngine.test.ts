import { describe, expect, it } from 'vitest';
import {
  canonical, emptySyncState, hashData, syncOnce, type LibraryAdapter, type SyncChange, type SyncCrypto, type SyncState, type SyncTransport, type WireRecord,
} from '../social/syncEngine';

/** The server's rules (newest wins per item, seq order), in memory. */
function fakeServer() {
  const rows = new Map<string, WireRecord & { seq: number }>();
  let seq = 0;
  const pushes: number[] = [];
  const transport: SyncTransport = {
    async pull(after) {
      const records = [...rows.values()].filter((r) => r.seq > after).sort((a, b) => a.seq - b.seq);
      return { records, latest: records.length ? records[records.length - 1].seq : after, more: false };
    },
    async push(records) {
      pushes.push(records.length);
      const accepted: string[] = [];
      for (const r of records) {
        const existing = rows.get(r.item);
        if (existing && existing.updatedAt >= r.updatedAt) continue;
        rows.set(r.item, { ...r, seq: ++seq });
        accepted.push(r.item);
      }
      return { accepted, latest: seq };
    },
  };
  return { transport, rows, pushes };
}

/** Readable "encryption" for tests; the real one is AES-GCM (client-keys.mjs). */
const fakeCrypto: SyncCrypto = {
  async itemId(c, id) { return `item:${c}:${id}`; },
  async seal(_item, plaintext) { return { nonce: 'n', ciphertext: plaintext }; },
  async open(_item, _nonce, ciphertext) { return ciphertext; },
};

/** A device's library: a map by key, with optional quirks of how it stores things. */
function device(collections: string[], opts: { dropField?: string; mergeRecent?: boolean } = {}) {
  const library = new Map<string, unknown>();
  const applied: SyncChange[] = [];
  let state: SyncState = emptySyncState();
  const adapter: LibraryAdapter = {
    collections,
    merge: opts.mergeRecent ? {
      recent: (local, remote) => {
        const all = [...((local as { plays?: number[] })?.plays ?? []), ...((remote as { plays: number[] }).plays)];
        return { plays: [...new Set(all)].sort((a, b) => b - a).slice(0, 5) };
      },
    } : undefined,
    snapshot: () => new Map(library),
    apply(changes) {
      for (const ch of changes) {
        applied.push(ch);
        const key = `${ch.c}:${ch.id}`;
        if (ch.data === null) { library.delete(key); continue; }
        if (opts.dropField && ch.data && typeof ch.data === 'object') {
          const { [opts.dropField]: _dropped, ...rest } = ch.data as Record<string, unknown>;
          library.set(key, rest);
        } else library.set(key, ch.data);
      }
    },
  };
  return {
    library, applied,
    get state() { return state; },
    async sync(transport: SyncTransport, now = Date.now) {
      const result = await syncOnce({ adapter, transport, crypto: fakeCrypto, state, now });
      state = result.state;
      return result;
    },
  };
}

const ALL = ['like', 'playlist', 'saved', 'recent'];
let clock = 1_000;
const tick = () => ++clock;

describe('library sync (sync.md)', () => {
  it('a like on one device reaches the other; an unlike removes it on both', async () => {
    const server = fakeServer();
    const phone = device(ALL);
    const web = device(ALL);
    phone.library.set('like:abc', { track: { id: 'abc', title: 'Chaleya' } });
    await phone.sync(server.transport, tick);
    await web.sync(server.transport, tick);
    expect(web.library.get('like:abc')).toEqual({ track: { id: 'abc', title: 'Chaleya' } });

    web.library.delete('like:abc');
    await web.sync(server.transport, tick);
    await phone.sync(server.transport, tick);
    expect(phone.library.has('like:abc')).toBe(false);
  });

  it('edits made offline on both devices end up on both', async () => {
    const server = fakeServer();
    const phone = device(ALL);
    const web = device(ALL);
    phone.library.set('playlist:p1', { name: 'Road trip', tracks: [] });
    web.library.set('like:xyz', { track: { id: 'xyz' } });
    await phone.sync(server.transport, tick);
    await web.sync(server.transport, tick);
    await phone.sync(server.transport, tick);
    expect([...phone.library.keys()].sort()).toEqual(['like:xyz', 'playlist:p1']);
    expect([...web.library.keys()].sort()).toEqual(['like:xyz', 'playlist:p1']);
  });

  it('the newest edit of the same item wins everywhere', async () => {
    const server = fakeServer();
    const phone = device(ALL);
    const web = device(ALL);
    phone.library.set('playlist:p1', { name: 'v1' });
    await phone.sync(server.transport, tick);
    await web.sync(server.transport, tick);
    phone.library.set('playlist:p1', { name: 'phone rename' }); // earlier
    const earlier = tick();
    web.library.set('playlist:p1', { name: 'web rename' }); // later
    await web.sync(server.transport, tick);
    await phone.sync(server.transport, () => earlier); // pushes an older edit: loses, then pulls the newer
    expect(phone.library.get('playlist:p1')).toEqual({ name: 'web rename' });
    await web.sync(server.transport, tick);
    expect(web.library.get('playlist:p1')).toEqual({ name: 'web rename' });
  });

  it("devices that store items differently don't bounce changes back and forth", async () => {
    const server = fakeServer();
    const phone = device(ALL);
    const web = device(ALL, { dropField: 'pinned' }); // the web doesn't keep "pinned"
    phone.library.set('playlist:p1', { name: 'Gym', pinned: true });
    await phone.sync(server.transport, tick);
    await web.sync(server.transport, tick);
    const before = server.rows.size;
    const pushesBefore = server.pushes.length;
    for (let i = 0; i < 3; i++) { await web.sync(server.transport, tick); await phone.sync(server.transport, tick); }
    expect(server.pushes.length).toBe(pushesBefore);
    expect(server.rows.size).toBe(before);
    expect(phone.library.get('playlist:p1')).toEqual({ name: 'Gym', pinned: true });
  });

  it("a device leaves alone the kinds of items it doesn't show", async () => {
    const server = fakeServer();
    const phone = device(ALL);
    const web = device(['like', 'playlist', 'recent']); // no saved albums on the web (yet)
    phone.library.set('saved:album:MPREb_1', { kind: 'album', title: 'Aashiqui 2' });
    await phone.sync(server.transport, tick);
    await web.sync(server.transport, tick);
    expect(web.library.has('saved:album:MPREb_1')).toBe(false);
    await phone.sync(server.transport, tick);
    expect(phone.library.has('saved:album:MPREb_1')).toBe(true);
  });

  it("a deletion from elsewhere doesn't remove what this device never synced", async () => {
    const server = fakeServer();
    const phone = device(ALL);
    phone.library.set('like:old', { track: { id: 'old' } });
    await phone.sync(server.transport, tick);
    phone.library.delete('like:old');
    await phone.sync(server.transport, tick); // tombstone on the server
    const laptop = device(ALL);
    laptop.library.set('like:old', { track: { id: 'old' } }); // liked here before it ever synced
    await laptop.sync(server.transport, tick);
    expect(laptop.library.has('like:old')).toBe(true);
    await phone.sync(server.transport, tick);
    expect(phone.library.has('like:old')).toBe(true); // the laptop's like is newer
  });

  it('recent plays merge instead of overwriting', async () => {
    const server = fakeServer();
    const phone = device(ALL, { mergeRecent: true });
    const web = device(ALL, { mergeRecent: true });
    phone.library.set('recent:plays', { plays: [5, 3] });
    web.library.set('recent:plays', { plays: [4, 1] });
    await phone.sync(server.transport, tick);
    await web.sync(server.transport, tick);
    await phone.sync(server.transport, tick);
    expect(web.library.get('recent:plays')).toEqual({ plays: [5, 4, 3, 1] });
    expect(phone.library.get('recent:plays')).toEqual({ plays: [5, 4, 3, 1] });
    const pushes = server.pushes.length;
    await web.sync(server.transport, tick);
    await phone.sync(server.transport, tick);
    expect(server.pushes.length).toBe(pushes); // settled
  });

  it('hashes ignore key order and undefined fields', () => {
    expect(canonical({ b: 1, a: [{ d: 2, c: undefined }] })).toBe('{"a":[{"d":2}],"b":1}');
    expect(hashData({ a: 1, b: 2 })).toBe(hashData({ b: 2, a: 1 }));
    expect(hashData({ a: 1 })).not.toBe(hashData({ a: 2 }));
  });
});
