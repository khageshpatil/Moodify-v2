import { createApi } from '../src/api.mjs';
import { identityFromSeed, sign } from '../src/client-keys.mjs';
import { bytesToBase64Url, signedMessages } from '../src/identity.mjs';

export const SECRET = 'test-secret-that-is-at-least-32-chars!!';

/** In-memory twin of d1Store (same method contract). */
export function memoryStore() {
  const accounts = new Map();
  const challenges = new Map();
  const friendships = new Map(); // "a|b" -> row
  const blocks = new Map(); // "blocker|blocked" -> row
  const ignored = new Map();
  const reports = [];
  const links = new Map();
  let messages = [];
  let nextId = 1;
  const inboxDevices = new Map(); // "code|device" -> { acked, seen }
  const pushTokens = new Map(); // "code|device" -> { code, device, platform, token, updated_at }
  const key = (a, b) => `${a}|${b}`;
  return {
    accounts, friendships, blocks, reports,
    get messages() { return messages; },
    async putChallenge(nonce, expiresAt) { challenges.set(nonce, expiresAt); },
    async takeChallenge(nonce, now) {
      const expiresAt = challenges.get(nonce);
      challenges.delete(nonce);
      return expiresAt !== undefined && expiresAt >= now;
    },
    async getAccount(code) { return accounts.get(code) ?? null; },
    async insertAccount(a) { accounts.set(a.code, { ...a }); },
    async updateDisplayName(code, name) { accounts.get(code).display_name = name; },
    async deleteAccount(code) {
      accounts.delete(code);
      for (const [k, f] of friendships) if (f.a === code || f.b === code) friendships.delete(k);
      for (const [k, b] of blocks) if (b.blocker === code || b.blocked === code) blocks.delete(k);
      messages = messages.filter((m) => m.sender !== code && m.recipient !== code && m.peer !== code);
      for (const k of [...inboxDevices.keys()]) if (k.startsWith(`${code}|`)) inboxDevices.delete(k);
      for (const k of [...pushTokens.keys()]) if (k.startsWith(`${code}|`)) pushTokens.delete(k);
    },
    async listFriendships(code) { return [...friendships.values()].filter((f) => f.a === code || f.b === code); },
    async getFriendship(a, b) { return friendships.get(key(a, b)) ?? null; },
    async insertFriendRequest(f) { friendships.set(key(f.a, f.b), { ...f, status: 'pending', accepted_at: null }); },
    async acceptFriendship(a, b, now) { Object.assign(friendships.get(key(a, b)), { status: 'accepted', accepted_at: now }); },
    async deleteFriendship(a, b) { friendships.delete(key(a, b)); },
    async countFriends(code) { return [...friendships.values()].filter((f) => (f.a === code || f.b === code) && f.status === 'accepted').length; },
    async countRequestsSince(code, since) { return [...friendships.values()].filter((f) => f.requested_by === code && f.created_at >= since).length; },
    async friendCodes(code) {
      return [...friendships.values()].filter((f) => (f.a === code || f.b === code) && f.status === 'accepted').map((f) => (f.a === code ? f.b : f.a));
    },
    async isBlocked(blocker, blocked) { return blocks.has(key(blocker, blocked)); },
    async addBlock(blocker, blocked, now) { blocks.set(key(blocker, blocked), { blocker, blocked, created_at: now }); },
    async removeBlock(blocker, blocked) { blocks.delete(key(blocker, blocked)); },
    async listBlocks(code) { return [...blocks.values()].filter((b) => b.blocker === code); },
    async ignoredUntil(from, to) { return ignored.get(key(from, to)) ?? 0; },
    async setIgnored(from, to, until) { ignored.set(key(from, to), until); },
    async addReport(r) { reports.push(r); },
    async insertMessage(m) { const id = nextId++; messages.push({ peer: null, ...m, id }); return id; },
    async countSentSince(sender, since) { return messages.filter((m) => m.sender === sender && m.recipient !== sender && m.created_at >= since).length; },
    pushTokens,
    async putPushToken(t, maxDevices) {
      for (const [k, v] of pushTokens) if (v.token === t.token && v.code !== t.code) pushTokens.delete(k);
      pushTokens.set(key(t.code, t.device), { ...t });
      const mine = [...pushTokens.values()].filter((v) => v.code === t.code).sort((a, b) => b.updated_at - a.updated_at);
      for (const old of mine.slice(maxDevices)) pushTokens.delete(key(old.code, old.device));
    },
    async listPushTokens(code) { return [...pushTokens.values()].filter((v) => v.code === code).map(({ device, platform, token }) => ({ device, platform, token })); },
    async deletePushToken(code, device) { pushTokens.delete(key(code, device)); },
    async ackInbox(code, device, upTo, now, activeSince, maxDevices) {
      const k = `${code}|${device}`;
      inboxDevices.set(k, { acked: Math.max(inboxDevices.get(k)?.acked ?? 0, upTo), seen: now });
      const mine = [...inboxDevices.entries()].filter(([key]) => key.startsWith(`${code}|`));
      for (const [key, d] of mine) if (d.seen < activeSince) inboxDevices.delete(key);
      const recent = [...inboxDevices.entries()].filter(([key]) => key.startsWith(`${code}|`)).sort((a, b) => b[1].seen - a[1].seen);
      for (const [key] of recent.slice(maxDevices)) inboxDevices.delete(key);
      const left = [...inboxDevices.entries()].filter(([key]) => key.startsWith(`${code}|`)).map(([, d]) => d.acked);
      return left.length ? Math.min(...left) : 0;
    },
    async listMessages(recipient, after, limit) { return messages.filter((m) => m.recipient === recipient && m.id > after).slice(0, limit); },
    async deleteMessagesUpTo(recipient, id) { messages = messages.filter((m) => !(m.recipient === recipient && m.id <= id)); },
    async deleteMessagesBetween(sender, recipient) { messages = messages.filter((m) => !(m.sender === sender && m.recipient === recipient)); },
    async pruneMessages(before) { messages = messages.filter((m) => m.created_at >= before); },
    links,
    async putLink(l) { links.set(l.id, { ...l, sender_pub: null, nonce: null, ciphertext: null }); },
    async getLink(id, now) { const l = links.get(id); return l && l.expires_at >= now ? l : null; },
    async sealLink(id, senderPub, nonce, ciphertext) { Object.assign(links.get(id), { sender_pub: senderPub, nonce, ciphertext }); },
    async deleteLink(id) { links.delete(id); },
  };
}

export function harness({ push = null, live = {} } = {}) {
  let clock = 1_800_000_000;
  const store = memoryStore();
  const events = [];
  // `live`: code -> device ids connected right now (they get events, not pings).
  const handle = createApi({ store, secret: SECRET, now: () => clock, push, notify: async (code, event) => { events.push({ code, ...event }); return live[code] ?? []; } });
  const call = async (method, path, body, token) => {
    const response = await handle(new Request(`https://social.test${path}`, {
      method,
      headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
      body: body === undefined ? undefined : JSON.stringify(body),
    }));
    const text = await response.text();
    return { status: response.status, body: text ? JSON.parse(text) : null };
  };
  return { store, events, call, advance: (s) => { clock += s; } };
}

export const seed = (n) => Uint8Array.from({ length: 32 }, (_, i) => (i * 7 + n) % 256);

export async function register(h, id, displayName = 'Asha') {
  const { body: { challenge } } = await h.call('POST', '/v1/auth/challenge');
  const signPub = bytesToBase64Url(id.signPub);
  const boxPub = bytesToBase64Url(id.boxPub);
  const signature = bytesToBase64Url(await sign(id.signPrivate, signedMessages.register(challenge, signPub, boxPub, displayName)));
  return h.call('POST', '/v1/accounts', { challenge, signPub, boxPub, displayName, signature });
}

export async function login(h, id) {
  const { body: { challenge } } = await h.call('POST', '/v1/auth/challenge');
  const signature = bytesToBase64Url(await sign(id.signPrivate, signedMessages.login(challenge)));
  return h.call('POST', '/v1/auth/verify', { challenge, signPub: bytesToBase64Url(id.signPub), signature });
}

/** A registered user: identity + session token. */
export async function user(h, n, name) {
  const id = await identityFromSeed(seed(n));
  const { body } = await register(h, id, name);
  return { id, seed: seed(n), code: id.code, token: body.token, name };
}
