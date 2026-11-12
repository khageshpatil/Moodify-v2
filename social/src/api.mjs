/**
 * HTTP API. Storage goes through `store` and live events through `notify`,
 * so tests can run the real handlers against in-memory fakes.
 * Protocol: docs/social-protocol/identity.md, docs/social-protocol/friends.md
 */
import {
  base64UrlToBytes, bytesToBase64Url, cleanDisplayName, codeForSigningKey, normalizeCode, signedMessages, verifySignature,
} from './identity.mjs';
import { createBurstLimiter } from './limits.mjs';
import { ipTag, issueChallenge, issueToken, readChallenge, readToken } from './tokens.mjs';

const CHALLENGE_SECONDS = 5 * 60;
const DAY = 24 * 60 * 60;
export const LIMITS = {
  friends: 500,
  requestsPerDay: 30,
  ignoreSeconds: 7 * DAY,
  noteLength: 60,
  /** Ciphertext characters per message: a shared playlist of up to 300 songs fits. */
  messageBytes: 96 * 1024,
  messageRetentionSeconds: 30 * DAY,
  messagesPerPage: 100,
  linkSeconds: 5 * 60,
  /** Devices per account that keep their own inbox position (oldest dropped). */
  inboxDevices: 10,
  /** Sends per sender per hour (all friends together). */
  messagesPerHour: 600,
  /** Devices per account that can get wake-up pings (oldest dropped). */
  pushDevices: 10,
  /** Library sync (sync.md): records per push, per page, per account; ciphertext size. */
  syncPerPush: 200,
  syncPerPage: 500,
  syncRecords: 20_000,
  syncBytes: 32 * 1024 * 1024,
  syncRecordChars: 512 * 1024,
  /** Abuse limits (limits.md). Per IP: sign-in calls a minute, new accounts a day, browser links an hour. */
  authPerMinute: 30,
  signupsPerDay: 20,
  linksPerHour: 20,
  /** Per account: code lookups a minute, reports a day. */
  lookupsPerMinute: 60,
  reportsPerDay: 20,
};
const SYNC_ITEM = /^[A-Za-z0-9_-]{22}$/;
const PUSH_TOKEN = /^[A-Za-z0-9:_-]{20,4096}$/;
/** Colours an emoji avatar can sit on (the same 12 in both apps). */
export const AVATAR_COLORS = 12;

/**
 * A profile picture without a photo: {kind: "emoji", emoji, color} or
 * {kind: "song", id, title, artist, artworkUrl}. Returns the cleaned JSON
 * text, or undefined when it isn't valid.
 */
export function cleanAvatar(input) {
  if (!input || typeof input !== 'object') return undefined;
  const text = (v, max) => (typeof v === 'string' ? v.trim().slice(0, max) : '');
  if (input.kind === 'emoji') {
    const emoji = text(input.emoji, 16);
    if (!emoji || /[A-Za-z0-9<>]/.test(emoji)) return undefined;
    if (!Number.isInteger(input.color) || input.color < 0 || input.color >= AVATAR_COLORS) return undefined;
    return JSON.stringify({ kind: 'emoji', emoji, color: input.color });
  }
  if (input.kind === 'song') {
    if (typeof input.id !== 'string' || !/^[A-Za-z0-9_-]{11}$/.test(input.id)) return undefined;
    const artworkUrl = text(input.artworkUrl, 400);
    if (!artworkUrl.startsWith('https://')) return undefined;
    return JSON.stringify({ kind: 'song', id: input.id, title: text(input.title, 100), artist: text(input.artist, 100), artworkUrl });
  }
  return undefined;
}
const avatarOf = (account) => { try { return account.avatar ? JSON.parse(account.avatar) : null; } catch { return null; } };
const B64URL = /^[A-Za-z0-9_-]+$/;

/** A browser's push subscription, cleaned; stored as JSON in the token column. */
function webPushSubscription(sub) {
  const endpoint = typeof sub?.endpoint === 'string' && sub.endpoint.length <= 1024 ? sub.endpoint : null;
  let url = null;
  try { url = endpoint ? new URL(endpoint) : null; } catch { url = null; }
  if (!url || url.protocol !== 'https:') return null;
  const p256dh = sub?.keys?.p256dh;
  const auth = sub?.keys?.auth;
  if (typeof p256dh !== 'string' || !B64URL.test(p256dh) || p256dh.length !== 87) return null; // 65 bytes
  if (typeof auth !== 'string' || !B64URL.test(auth) || auth.length !== 22) return null; // 16 bytes
  return JSON.stringify({ endpoint, keys: { p256dh, auth } });
}
const DEVICE_ID = /^[A-Za-z0-9_-]{8,64}$/;

const CROCKFORD = '0123456789ABCDEFGHJKMNPQRSTVWXYZ';
const linkCode = () => Array.from(crypto.getRandomValues(new Uint8Array(8)), (b) => CROCKFORD[b % 32]).join('');
const LINK_ID = /^[0-9A-HJKMNP-TV-Z]{8}$/;

const json = (status, body) => new Response(JSON.stringify(body), {
  status,
  headers: { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' },
});
const error = (status, code, message) => json(status, { error: { code, message } });

async function readJson(request) {
  try {
    return await request.json();
  } catch {
    return null;
  }
}

function keyBytes(value) {
  try {
    const bytes = base64UrlToBytes(value);
    return bytes.length === 32 ? bytes : null;
  } catch {
    return null;
  }
}

/** Friendships are stored once per pair, ordered. */
export const pair = (x, y) => (x < y ? [x, y] : [y, x]);

/**
 * `notify(code, event)` returns the device ids connected live (or nothing).
 * `push(target, data)` sends a content-free wake-up ping ("ok" | "gone" | "error");
 * `defer(promise)` lets it finish after the reply (Workers: ctx.waitUntil).
 */
export function createApi({
  store, secret, notify = async () => {}, push = null, defer = (work) => work, now = () => Math.floor(Date.now() / 1000),
  vapidPublicKey = null, burst = createBurstLimiter(),
}) {
  /** The caller's IP (Cloudflare sets it); null in tests and local tools, which skips IP limits. */
  const clientIp = (request) => request.headers.get('CF-Connecting-IP');
  const slowDown = (message = 'Too many requests. Wait a minute and try again.') => error(429, 'slow_down', message);
  /** Wakes [code]'s devices that aren't connected live, so they fetch the inbox (chat.md §3). */
  async function wake(code, liveDevices) {
    if (!push) return;
    const live = new Set(Array.isArray(liveDevices) ? liveDevices : []);
    const targets = (await store.listPushTokens(code)).filter((t) => !live.has(t.device));
    if (!targets.length) return;
    await defer(Promise.all(targets.map(async (t) => {
      if ((await push({ platform: t.platform, token: t.token }, { kind: 'message' })) === 'gone') await store.deletePushToken(code, t.device);
    })).catch(() => {}));
  }

  async function authed(request) {
    const header = request.headers.get('Authorization') || '';
    const token = header.startsWith('Bearer ') ? header.slice(7) : null;
    const code = await readToken(secret, token, now());
    if (!code) return null;
    return store.getAccount(code);
  }

  const profile = (account) => ({
    code: account.code,
    displayName: account.display_name,
    boxPub: account.box_pub,
    createdAt: account.created_at,
    avatar: avatarOf(account),
  });
  const publicUser = (account) => ({ code: account.code, displayName: account.display_name, boxPub: account.box_pub, avatar: avatarOf(account) });

  /** A challenge we issued, unexpired, and not used before (recorded now, so it can't be used twice). */
  async function takeChallenge(challenge) {
    const expiresAt = await readChallenge(secret, challenge, now());
    if (!expiresAt) return false;
    return store.useChallenge(challenge, expiresAt, now());
  }

  // ── Identity (M1) ──────────────────────────────────────────────────────────

  async function register(request) {
    const body = await readJson(request);
    const signPub = keyBytes(body?.signPub);
    const boxPub = keyBytes(body?.boxPub);
    const displayName = cleanDisplayName(body?.displayName);
    if (!signPub || !boxPub) return error(400, 'bad_keys', 'signPub and boxPub must be 32-byte base64url keys.');
    if (!displayName) return error(400, 'bad_name', 'Display names are 2–24 characters.');
    let signature;
    try { signature = base64UrlToBytes(body?.signature ?? ''); } catch { signature = null; }
    const message = signedMessages.register(body?.challenge, body.signPub, body.boxPub, body.displayName);
    if (!signature || !(await verifySignature(signPub, message, signature))) {
      return error(401, 'bad_signature', 'The registration signature does not match the key.');
    }
    if (!(await takeChallenge(body.challenge))) return error(401, 'bad_challenge', 'Challenge expired or already used.');
    const code = await codeForSigningKey(signPub);
    const existing = await store.getAccount(code);
    if (existing && existing.sign_pub !== body.signPub) {
      return error(409, 'code_taken', 'This code belongs to another key. Generate a new identity.');
    }
    if (!existing) {
      const ip = clientIp(request);
      const tag = ip ? await ipTag(secret, ip) : null;
      if (tag && (await store.countSignupsSince(tag, now() - DAY)) >= LIMITS.signupsPerDay) {
        return slowDown('Too many new codes from this network today. Try again tomorrow.');
      }
      await store.insertAccount({ code, sign_pub: body.signPub, box_pub: body.boxPub, display_name: displayName, created_at: now(), signup_ip: tag }, now() - DAY);
    }
    const session = await issueToken(secret, code, now());
    return json(existing ? 200 : 201, { account: profile(await store.getAccount(code)), ...session });
  }

  async function login(request) {
    const body = await readJson(request);
    const signPub = keyBytes(body?.signPub);
    if (!signPub) return error(400, 'bad_keys', 'signPub must be a 32-byte base64url key.');
    let signature;
    try { signature = base64UrlToBytes(body?.signature ?? ''); } catch { signature = null; }
    if (!signature || !(await verifySignature(signPub, signedMessages.login(body?.challenge), signature))) {
      return error(401, 'bad_signature', 'Signature does not match.');
    }
    if (!(await takeChallenge(body.challenge))) return error(401, 'bad_challenge', 'Challenge expired or already used.');
    const code = await codeForSigningKey(signPub);
    const account = await store.getAccount(code);
    if (!account || account.sign_pub !== body.signPub) return error(404, 'no_account', 'No account for this key. Register first.');
    return json(200, { account: profile(account), ...(await issueToken(secret, code, now())) });
  }

  // ── Friends (M2) ───────────────────────────────────────────────────────────

  async function listFriends(me) {
    const rows = await store.listFriendships(me.code);
    const blocked = new Set((await store.listBlocks(me.code)).map((b) => b.blocked));
    const out = { friends: [], incoming: [], outgoing: [] };
    for (const row of rows) {
      const other = row.a === me.code ? row.b : row.a;
      if (blocked.has(other)) continue;
      const account = await store.getAccount(other);
      if (!account) continue;
      const entry = { ...publicUser(account), since: row.accepted_at ?? row.created_at };
      if (row.status === 'accepted') out.friends.push(entry);
      else if (row.requested_by === me.code) out.outgoing.push({ ...entry, note: row.note ?? null });
      else out.incoming.push({ ...entry, note: row.note ?? null });
    }
    out.friends.sort((x, y) => x.displayName.localeCompare(y.displayName));
    return json(200, out);
  }

  async function requestFriend(me, request) {
    const body = await readJson(request);
    const code = normalizeCode(body?.code ?? '');
    if (!code) return error(400, 'bad_code', "That doesn't look like a Moodify code.");
    if (code === me.code) return error(400, 'self', "That's your own code.");
    const note = typeof body?.note === 'string' ? body.note.replace(/\s+/g, ' ').trim().slice(0, LIMITS.noteLength) || null : null;
    const target = await store.getAccount(code);
    if (!target) return error(404, 'not_found', 'No one has that code.');
    const user = publicUser(target);
    const [a, b] = pair(me.code, code);
    const existing = await store.getFriendship(a, b);
    if (existing?.status === 'accepted') return json(200, { status: 'friends', user });
    // They already asked you: accepting is the friendly thing to do.
    if (existing && existing.requested_by === code) {
      await store.acceptFriendship(a, b, now());
      await notify(code, { type: 'friend.accepted', body: { code: me.code, displayName: me.display_name } });
      await notify(me.code, { type: 'friend.accepted', body: { code, displayName: target.display_name } });
      return json(200, { status: 'friends', user });
    }
    if (existing) return json(200, { status: 'pending', user });
    if ((await store.countFriends(me.code)) >= LIMITS.friends) return error(429, 'too_many_friends', `You can have up to ${LIMITS.friends} friends.`);
    if ((await store.countRequestsSince(me.code, now() - DAY)) >= LIMITS.requestsPerDay) {
      return error(429, 'slow_down', 'Too many friend requests today. Try again tomorrow.');
    }
    // Blocked, or ignored in the last week: looks sent, but nothing is delivered.
    if ((await store.isBlocked(code, me.code)) || (await store.ignoredUntil(me.code, code)) > now()) {
      return json(202, { status: 'pending', user });
    }
    await store.insertFriendRequest({ a, b, requested_by: me.code, note, created_at: now() });
    await notify(code, { type: 'friend.request', body: { code: me.code, displayName: me.display_name, note } });
    return json(201, { status: 'pending', user });
  }

  async function answerRequest(me, code, accept) {
    const [a, b] = pair(me.code, code);
    const existing = await store.getFriendship(a, b);
    if (!existing || existing.status !== 'pending' || existing.requested_by !== code) {
      return error(404, 'no_request', 'There is no request from that person.');
    }
    if (accept) {
      if ((await store.countFriends(me.code)) >= LIMITS.friends) return error(429, 'too_many_friends', `You can have up to ${LIMITS.friends} friends.`);
      await store.acceptFriendship(a, b, now());
      // Both sides' hubs refresh their friend lists (and your other devices update).
      await notify(me.code, { type: 'friend.accepted', body: { code, displayName: (await store.getAccount(code))?.display_name ?? '' } });
      await notify(code, { type: 'friend.accepted', body: { code: me.code, displayName: me.display_name } });
      return json(200, { status: 'friends' });
    }
    // Ignoring sends nothing back and stops repeat requests for a week.
    await store.deleteFriendship(a, b);
    await store.setIgnored(code, me.code, now() + LIMITS.ignoreSeconds);
    return json(200, { status: 'ignored' });
  }

  async function removeFriend(me, code) {
    const [a, b] = pair(me.code, code);
    const existing = await store.getFriendship(a, b);
    if (!existing) return new Response(null, { status: 204 });
    await store.deleteFriendship(a, b);
    if (existing.status === 'accepted') {
      await notify(me.code, { type: 'friend.removed', body: { code } });
      await notify(code, { type: 'friend.removed', body: { code: me.code } });
    }
    return new Response(null, { status: 204 });
  }

  async function block(me, code) {
    if (code === me.code) return error(400, 'self', "You can't block yourself.");
    await store.addBlock(me.code, code, now());
    const [a, b] = pair(me.code, code);
    await store.deleteFriendship(a, b);
    await store.deleteMessagesBetween(code, me.code);
    // Only your own hub hears about it: the blocked person is never told.
    await notify(me.code, { type: 'friend.removed', body: { code } });
    return new Response(null, { status: 204 });
  }

  async function report(me, request) {
    const body = await readJson(request);
    const code = normalizeCode(body?.code ?? '');
    const reason = typeof body?.reason === 'string' ? body.reason.trim().slice(0, 500) : '';
    if (!code || !reason) return error(400, 'bad_report', 'A report needs a code and a reason.');
    const evidence = typeof body?.evidence === 'string' ? body.evidence.slice(0, 8000) : null;
    if ((await store.countReportsSince(me.code, now() - DAY)) >= LIMITS.reportsPerDay) return slowDown('Too many reports today. Try again tomorrow.');
    await store.addReport({ reporter: me.code, reported: code, reason, evidence, created_at: now() });
    return json(201, { status: 'received' });
  }

  // ── Encrypted inbox (M2 shares, M5 chat) ───────────────────────────────────

  async function sendMessage(me, request) {
    const body = await readJson(request);
    const to = normalizeCode(body?.to ?? '');
    if (!to) return error(400, 'bad_code', 'Unknown recipient.');
    const nonce = typeof body?.nonce === 'string' && /^[A-Za-z0-9_-]{16}$/.test(body.nonce) ? body.nonce : null;
    const ciphertext = typeof body?.ciphertext === 'string' && /^[A-Za-z0-9_-]+$/.test(body.ciphertext) ? body.ciphertext : null;
    if (!nonce || !ciphertext) return error(400, 'bad_message', 'Messages must be encrypted (nonce + ciphertext, base64url).');
    if (ciphertext.length > LIMITS.messageBytes) return error(413, 'too_big', 'That message is too large.');
    const [a, b] = pair(me.code, to);
    const sentAt = now();
    // The checks don't depend on each other: ask together (one round trip, not three).
    const [friendship, sentLastHour, blocked] = await Promise.all([
      store.getFriendship(a, b), store.countSentSince(me.code, sentAt - 3600), store.isBlocked(to, me.code),
    ]);
    if (friendship?.status !== 'accepted') return error(403, 'not_friends', 'You can only send to friends.');
    if (sentLastHour >= LIMITS.messagesPerHour) {
      return error(429, 'slow_down', "You're sending a lot. Try again in a little while.");
    }
    // Old messages are swept after the reply (an index range: cheap, and free when there's nothing to delete).
    defer(store.pruneMessages(sentAt - LIMITS.messageRetentionSeconds).catch(() => {}));
    // A copy for your own other devices (same ciphertext: the pair key works both ways), and the
    // message itself, written together. A block hides delivery: the sender sees success, nothing reaches them.
    const [copyId, id] = await store.insertMessages(
      { sender: me.code, recipient: me.code, peer: to, nonce, ciphertext, created_at: sentAt },
      blocked ? null : { sender: me.code, recipient: to, peer: null, nonce, ciphertext, created_at: sentAt },
    );
    // Telling devices (and waking phones) happens after the reply: the sender needn't wait for it.
    defer(Promise.all([
      notify(me.code, { type: 'msg.new', body: { from: me.code, id: copyId, own: true } }),
      blocked ? null : notify(to, { type: 'msg.new', body: { from: me.code, id } }).then((live) => wake(to, live)),
    ]).catch(() => {}));
    return json(201, { id: blocked ? null : id, copyId, sentAt });
  }

  async function listMessages(me, url) {
    const after = Math.max(0, Number(url.searchParams.get('after')) || 0);
    const rows = await store.listMessages(me.code, after, LIMITS.messagesPerPage);
    return json(200, {
      messages: rows.map((m) => ({
        id: m.id, from: m.sender, nonce: m.nonce, ciphertext: m.ciphertext, sentAt: m.created_at,
        // Your own message, copied for your other devices: who it went to.
        ...(m.peer ? { to: m.peer } : {}),
      })),
    });
  }

  // ── Library sync (sync.md) ─────────────────────────────────────────────────

  async function pushSync(me, request) {
    const body = await readJson(request);
    const records = Array.isArray(body?.records) ? body.records : null;
    if (!records || records.length === 0 || records.length > LIMITS.syncPerPush) {
      return error(400, 'bad_sync', `Send 1 to ${LIMITS.syncPerPush} records.`);
    }
    const latestAllowed = (now() + 600) * 1000; // a device's clock may run a little fast
    const clean = [];
    const seen = new Set();
    for (const r of records) {
      const ok = typeof r?.item === 'string' && SYNC_ITEM.test(r.item) && !seen.has(r.item)
        && Number.isInteger(r.updatedAt) && r.updatedAt > 0 && r.updatedAt <= latestAllowed
        && typeof r.nonce === 'string' && /^[A-Za-z0-9_-]{16}$/.test(r.nonce)
        && typeof r.ciphertext === 'string' && /^[A-Za-z0-9_-]+$/.test(r.ciphertext) && r.ciphertext.length <= LIMITS.syncRecordChars;
      if (!ok) return error(400, 'bad_sync', 'Each record needs {item, updatedAt, nonce, ciphertext}, encrypted.');
      seen.add(r.item);
      clean.push({ item: r.item, updatedAt: r.updatedAt, nonce: r.nonce, ciphertext: r.ciphertext });
    }
    const usage = await store.syncUsage(me.code);
    const incoming = clean.reduce((n, r) => n + r.ciphertext.length, 0);
    if (usage.records + clean.length > LIMITS.syncRecords || usage.bytes + incoming > LIMITS.syncBytes) {
      return error(413, 'sync_full', 'Your synced library is full.');
    }
    // An item only changes when this version is newer; older ones lose silently.
    const { accepted, latest } = await store.putSyncRecords(me.code, clean);
    if (accepted.length) await notify(me.code, { type: 'sync.changed', body: { latest } });
    return json(200, { accepted, latest });
  }

  async function pullSync(me, url) {
    const after = Math.max(0, Math.floor(Number(url.searchParams.get('after')) || 0));
    const rows = await store.listSyncRecords(me.code, after, LIMITS.syncPerPage);
    return json(200, {
      records: rows.map((r) => ({ item: r.item, seq: r.seq, updatedAt: r.updated_at, nonce: r.nonce, ciphertext: r.ciphertext })),
      latest: rows.length ? rows[rows.length - 1].seq : after,
      more: rows.length === LIMITS.syncPerPage,
    });
  }

  async function ackMessages(me, request) {
    const body = await readJson(request);
    const upTo = Number(body?.upTo);
    // 0 is a check-in: a new device with an empty inbox starts holding messages for itself.
    if (!Number.isInteger(upTo) || upTo < 0) return error(400, 'bad_ack', 'upTo must be a message id, or 0 to check in.');
    // Each device confirms its own position; older clients without one count as "legacy".
    const device = typeof body?.device === 'string' && DEVICE_ID.test(body.device) ? body.device : 'legacy';
    const t = now();
    const safeUpTo = await store.ackInbox(me.code, device, upTo, t, t - LIMITS.messageRetentionSeconds, LIMITS.inboxDevices);
    if (safeUpTo > 0) await store.deleteMessagesUpTo(me.code, safeUpTo);
    return new Response(null, { status: 204 });
  }

  // ── Router ─────────────────────────────────────────────────────────────────

  return async function handle(request) {
    const url = new URL(request.url);
    const { pathname } = url;
    const method = request.method;

    if (method === 'GET' && pathname === '/') return json(200, { service: 'moodify-social', version: 2 });

    const ip = clientIp(request);
    const authPath = method === 'POST' && (pathname === '/v1/auth/challenge' || pathname === '/v1/accounts' || pathname === '/v1/auth/verify');
    if (authPath && ip && !burst.allow(`auth:${ip}`, LIMITS.authPerMinute, 60_000, now() * 1000)) return slowDown();

    if (method === 'POST' && pathname === '/v1/auth/challenge') {
      return json(200, { challenge: await issueChallenge(secret, now() + CHALLENGE_SECONDS), expiresIn: CHALLENGE_SECONDS });
    }
    if (method === 'GET' && pathname === '/v1/push/vapid') {
      return vapidPublicKey ? json(200, { publicKey: vapidPublicKey }) : error(404, 'no_web_push', 'Browser notifications are not set up on this server.');
    }
    if (method === 'POST' && pathname === '/v1/accounts') return register(request);
    if (method === 'POST' && pathname === '/v1/auth/verify') return login(request);

    // ── Device linking (M3): the new device needs no account yet ──
    if (method === 'POST' && pathname === '/v1/link') {
      const body = await readJson(request);
      if (!keyBytes(body?.devicePub)) return error(400, 'bad_keys', 'devicePub must be a 32-byte base64url key.');
      const tag = ip ? await ipTag(secret, ip) : null;
      if (tag && (await store.countLinksSince(tag, now() - 3600)) >= LIMITS.linksPerHour) return slowDown('Too many link codes from this network. Try again in an hour.');
      const id = linkCode();
      await store.putLink({ id, device_pub: body.devicePub, created_at: now(), expires_at: now() + LIMITS.linkSeconds, ip: tag }, now());
      return json(201, { linkId: id, expiresIn: LIMITS.linkSeconds });
    }
    const linkMatch = pathname.match(/^\/v1\/link\/([^/]+)(\/result)?$/);
    if (linkMatch && method === 'GET') {
      const id = linkMatch[1].toUpperCase();
      if (!LINK_ID.test(id)) return error(400, 'bad_link', 'Unknown link code.');
      const link = await store.getLink(id, now());
      if (!link) return error(404, 'no_link', 'That link code expired. Start again on the new device.');
      if (!linkMatch[2]) return json(200, { devicePub: link.device_pub });
      if (!link.ciphertext) return json(202, { status: 'waiting' });
      await store.deleteLink(id); // one read only
      return json(200, { senderPub: link.sender_pub, nonce: link.nonce, ciphertext: link.ciphertext });
    }

    // Everything below needs a session.
    const me = await authed(request);
    if (!me) return error(401, 'unauthorized', 'Sign in again.');

    if (pathname === '/v1/me') {
      if (method === 'GET') return json(200, { account: profile(me) });
      if (method === 'PATCH') {
        // Change your name, your picture, or both.
        const body = await readJson(request);
        const hasName = body && 'displayName' in body;
        const hasAvatar = body && 'avatar' in body;
        if (!hasName && !hasAvatar) return error(400, 'bad_profile', 'Send a displayName or an avatar.');
        const displayName = hasName ? cleanDisplayName(body.displayName) : null;
        if (hasName && !displayName) return error(400, 'bad_name', 'Display names are 2–24 characters.');
        const avatar = hasAvatar ? (body.avatar === null ? null : cleanAvatar(body.avatar)) : undefined;
        if (hasAvatar && avatar === undefined) return error(400, 'bad_avatar', 'An avatar is an emoji on a colour, or a song.');
        if (displayName) await store.updateDisplayName(me.code, displayName);
        if (hasAvatar) await store.updateAvatar(me.code, avatar);
        // Friends' apps refresh their list to show the new name or picture.
        const friends = await store.friendCodes(me.code);
        await Promise.all(friends.map((code) => notify(code, { type: 'friend.updated', body: { code: me.code } })));
        return json(200, { account: profile(await store.getAccount(me.code)) });
      }
      if (method === 'DELETE') {
        const friends = await store.friendCodes(me.code);
        await store.deleteAccount(me.code);
        await Promise.all(friends.map((code) => notify(code, { type: 'friend.removed', body: { code: me.code } })));
        return new Response(null, { status: 204 });
      }
    }

    const userMatch = pathname.match(/^\/v1\/users\/([^/]+)$/);
    if (method === 'GET' && userMatch) {
      if (!burst.allow(`lookup:${me.code}`, LIMITS.lookupsPerMinute, 60_000, now() * 1000)) return slowDown();
      const code = normalizeCode(decodeURIComponent(userMatch[1]));
      const account = code ? await store.getAccount(code) : null;
      if (!account) return error(404, 'not_found', 'No one has that code.');
      return json(200, { user: publicUser(account) });
    }

    if (pathname === '/v1/friends' && method === 'GET') return listFriends(me);
    if (pathname === '/v1/friends/requests' && method === 'POST') return requestFriend(me, request);
    const answer = pathname.match(/^\/v1\/friends\/requests\/([^/]+)\/(accept|ignore)$/);
    if (answer && method === 'POST') {
      const code = normalizeCode(decodeURIComponent(answer[1]));
      return code ? answerRequest(me, code, answer[2] === 'accept') : error(400, 'bad_code', 'Unknown code.');
    }
    const friend = pathname.match(/^\/v1\/friends\/([^/]+)$/);
    if (friend && method === 'DELETE') {
      const code = normalizeCode(decodeURIComponent(friend[1]));
      return code ? removeFriend(me, code) : error(400, 'bad_code', 'Unknown code.');
    }

    if (pathname === '/v1/blocks' && method === 'GET') {
      const blocks = await store.listBlocks(me.code);
      return json(200, { blocked: blocks.map((b) => ({ code: b.blocked, since: b.created_at })) });
    }
    const blockMatch = pathname.match(/^\/v1\/blocks\/([^/]+)$/);
    if (blockMatch) {
      const code = normalizeCode(decodeURIComponent(blockMatch[1]));
      if (!code) return error(400, 'bad_code', 'Unknown code.');
      if (method === 'PUT') return block(me, code);
      if (method === 'DELETE') {
        await store.removeBlock(me.code, code);
        return new Response(null, { status: 204 });
      }
    }
    if (pathname === '/v1/reports' && method === 'POST') return report(me, request);

    const sealMatch = pathname.match(/^\/v1\/link\/([^/]+)\/seal$/);
    if (sealMatch && method === 'POST') {
      const id = sealMatch[1].toUpperCase();
      const body = await readJson(request);
      const link = LINK_ID.test(id) ? await store.getLink(id, now()) : null;
      if (!link) return error(404, 'no_link', 'That link code expired. Start again on the new device.');
      if (link.ciphertext) return error(409, 'already_linked', 'This link was already used.');
      const valid = keyBytes(body?.senderPub) && /^[A-Za-z0-9_-]{16}$/.test(body?.nonce ?? '') && /^[A-Za-z0-9_-]{40,120}$/.test(body?.ciphertext ?? '');
      if (!valid) return error(400, 'bad_link_payload', 'Expected an encrypted seed.');
      await store.sealLink(id, body.senderPub, body.nonce, body.ciphertext);
      return json(200, { status: 'sent' });
    }

    if (pathname === '/v1/push' && method === 'PUT') {
      const body = await readJson(request);
      const device = typeof body?.device === 'string' && DEVICE_ID.test(body.device) ? body.device : null;
      const platform = ['fcm', 'webpush'].includes(body?.platform) ? body.platform : null;
      const token = platform === 'fcm'
        ? (typeof body?.token === 'string' && PUSH_TOKEN.test(body.token) ? body.token : null)
        : webPushSubscription(body?.subscription);
      if (!device || !platform || !token) {
        return error(400, 'bad_push', 'Expected {device, platform: "fcm", token} or {device, platform: "webpush", subscription}.');
      }
      await store.putPushToken({ code: me.code, device, platform, token, updated_at: now() }, LIMITS.pushDevices);
      return new Response(null, { status: 204 });
    }
    const pushMatch = pathname.match(/^\/v1\/push\/([A-Za-z0-9_-]{8,64})$/);
    if (pushMatch && method === 'DELETE') {
      await store.deletePushToken(me.code, pushMatch[1]);
      return new Response(null, { status: 204 });
    }

    if (pathname === '/v1/sync' && method === 'PUT') return pushSync(me, request);
    if (pathname === '/v1/sync' && method === 'GET') return pullSync(me, url);

    if (pathname === '/v1/messages' && method === 'POST') return sendMessage(me, request);
    if (pathname === '/v1/messages' && method === 'GET') return listMessages(me, url);
    if (pathname === '/v1/messages/ack' && method === 'POST') return ackMessages(me, request);

    return error(404, 'not_found', 'Unknown endpoint.');
  };
}

/** D1-backed store used by the deployed Worker. */
export function d1Store(db) {
  const all = async (sql, ...args) => (await db.prepare(sql).bind(...args).all()).results ?? [];
  const run = (sql, ...args) => db.prepare(sql).bind(...args).run();
  return {
    /** Records a used challenge; false when it was used before. Expired ones are swept. */
    async useChallenge(nonce, expiresAt, now) {
      const [, inserted] = await db.batch([
        db.prepare('DELETE FROM challenges WHERE expires_at < ?').bind(now),
        db.prepare('INSERT OR IGNORE INTO challenges (nonce, expires_at) VALUES (?, ?)').bind(nonce, expiresAt),
      ]);
      return (inserted.meta?.changes ?? 0) === 1;
    },
    async countSignupsSince(tag, since) {
      const row = await db.prepare('SELECT COUNT(*) AS n FROM accounts WHERE signup_ip = ? AND created_at >= ?').bind(tag, since).first();
      return row?.n ?? 0;
    },
    async countLinksSince(tag, since) {
      const row = await db.prepare('SELECT COUNT(*) AS n FROM link_sessions WHERE ip = ? AND created_at >= ?').bind(tag, since).first();
      return row?.n ?? 0;
    },
    async countReportsSince(code, since) {
      const row = await db.prepare('SELECT COUNT(*) AS n FROM reports WHERE reporter = ? AND created_at >= ?').bind(code, since).first();
      return row?.n ?? 0;
    },
    getAccount: (code) => db.prepare('SELECT * FROM accounts WHERE code = ?').bind(code).first(),
    /** Adds an account; IP tags older than [forgetBefore] are cleared (kept for a day only). */
    async insertAccount(a, forgetBefore = 0) {
      await db.batch([
        db.prepare('INSERT INTO accounts (code, sign_pub, box_pub, display_name, created_at, signup_ip) VALUES (?, ?, ?, ?, ?, ?)')
          .bind(a.code, a.sign_pub, a.box_pub, a.display_name, a.created_at, a.signup_ip ?? null),
        db.prepare('UPDATE accounts SET signup_ip = NULL WHERE signup_ip IS NOT NULL AND created_at < ?').bind(forgetBefore),
      ]);
    },
    async updateDisplayName(code, name) { await run('UPDATE accounts SET display_name = ? WHERE code = ?', name, code); },
    async updateAvatar(code, avatar) { await run('UPDATE accounts SET avatar = ? WHERE code = ?', avatar, code); },
    async deleteAccount(code) {
      await db.batch([
        db.prepare('DELETE FROM accounts WHERE code = ?').bind(code),
        db.prepare('DELETE FROM friendships WHERE a = ? OR b = ?').bind(code, code),
        db.prepare('DELETE FROM blocks WHERE blocker = ? OR blocked = ?').bind(code, code),
        db.prepare('DELETE FROM ignored_requests WHERE from_code = ? OR to_code = ?').bind(code, code),
        db.prepare('DELETE FROM messages WHERE sender = ? OR recipient = ? OR peer = ?').bind(code, code, code),
        db.prepare('DELETE FROM inbox_devices WHERE code = ?').bind(code),
        db.prepare('DELETE FROM push_tokens WHERE code = ?').bind(code),
        db.prepare('DELETE FROM sync_records WHERE code = ?').bind(code),
      ]);
    },

    listFriendships: (code) => all('SELECT * FROM friendships WHERE a = ? OR b = ?', code, code),
    getFriendship: (a, b) => db.prepare('SELECT * FROM friendships WHERE a = ? AND b = ?').bind(a, b).first(),
    async insertFriendRequest(f) {
      await run('INSERT INTO friendships (a, b, status, requested_by, note, created_at) VALUES (?, ?, \'pending\', ?, ?, ?)',
        f.a, f.b, f.requested_by, f.note, f.created_at);
    },
    async acceptFriendship(a, b, now) { await run('UPDATE friendships SET status = \'accepted\', accepted_at = ? WHERE a = ? AND b = ?', now, a, b); },
    async deleteFriendship(a, b) { await run('DELETE FROM friendships WHERE a = ? AND b = ?', a, b); },
    async countFriends(code) {
      const row = await db.prepare('SELECT COUNT(*) AS n FROM friendships WHERE (a = ? OR b = ?) AND status = \'accepted\'').bind(code, code).first();
      return row?.n ?? 0;
    },
    async countRequestsSince(code, since) {
      const row = await db.prepare('SELECT COUNT(*) AS n FROM friendships WHERE requested_by = ? AND created_at >= ?').bind(code, since).first();
      return row?.n ?? 0;
    },
    async friendCodes(code) {
      const rows = await all('SELECT a, b FROM friendships WHERE (a = ? OR b = ?) AND status = \'accepted\'', code, code);
      return rows.map((r) => (r.a === code ? r.b : r.a));
    },

    async isBlocked(blocker, blocked) {
      return !!(await db.prepare('SELECT 1 AS x FROM blocks WHERE blocker = ? AND blocked = ?').bind(blocker, blocked).first());
    },
    async addBlock(blocker, blocked, now) { await run('INSERT OR IGNORE INTO blocks (blocker, blocked, created_at) VALUES (?, ?, ?)', blocker, blocked, now); },
    async removeBlock(blocker, blocked) { await run('DELETE FROM blocks WHERE blocker = ? AND blocked = ?', blocker, blocked); },
    listBlocks: (code) => all('SELECT * FROM blocks WHERE blocker = ?', code),
    async ignoredUntil(from, to) {
      const row = await db.prepare('SELECT until FROM ignored_requests WHERE from_code = ? AND to_code = ?').bind(from, to).first();
      return row?.until ?? 0;
    },
    async setIgnored(from, to, until) {
      await run('INSERT OR REPLACE INTO ignored_requests (from_code, to_code, until) VALUES (?, ?, ?)', from, to, until);
    },
    async addReport(r) {
      await run('INSERT INTO reports (reporter, reported, reason, evidence, created_at) VALUES (?, ?, ?, ?, ?)',
        r.reporter, r.reported, r.reason, r.evidence, r.created_at);
    },

    async countSentSince(sender, since) {
      const row = await db.prepare('SELECT COUNT(*) AS n FROM messages WHERE sender = ? AND recipient != sender AND created_at >= ?').bind(sender, since).first();
      return row?.n ?? 0;
    },
    /** Records a device's position; returns the id every recent device has confirmed (safe to delete). */
    async ackInbox(code, device, upTo, now, activeSince, maxDevices) {
      await db.batch([
        db.prepare(`INSERT INTO inbox_devices (code, device, acked_up_to, seen_at) VALUES (?, ?, ?, ?)
          ON CONFLICT (code, device) DO UPDATE SET acked_up_to = MAX(acked_up_to, excluded.acked_up_to), seen_at = excluded.seen_at`).bind(code, device, upTo, now),
        db.prepare('DELETE FROM inbox_devices WHERE code = ? AND seen_at < ?').bind(code, activeSince),
        db.prepare(`DELETE FROM inbox_devices WHERE code = ? AND device NOT IN
          (SELECT device FROM inbox_devices WHERE code = ? ORDER BY seen_at DESC LIMIT ?)`).bind(code, code, maxDevices),
      ]);
      const row = await db.prepare('SELECT MIN(acked_up_to) AS safe FROM inbox_devices WHERE code = ?').bind(code).first();
      return row?.safe ?? 0;
    },
    /** One token per device; a token moves with the phone if another account signs in on it. */
    async putPushToken(t, maxDevices) {
      await db.batch([
        db.prepare('DELETE FROM push_tokens WHERE token = ? AND code != ?').bind(t.token, t.code),
        db.prepare(`INSERT INTO push_tokens (code, device, platform, token, updated_at) VALUES (?, ?, ?, ?, ?)
          ON CONFLICT (code, device) DO UPDATE SET platform = excluded.platform, token = excluded.token, updated_at = excluded.updated_at`)
          .bind(t.code, t.device, t.platform, t.token, t.updated_at),
        db.prepare(`DELETE FROM push_tokens WHERE code = ? AND device NOT IN
          (SELECT device FROM push_tokens WHERE code = ? ORDER BY updated_at DESC LIMIT ?)`).bind(t.code, t.code, maxDevices),
      ]);
    },
    async syncUsage(code) {
      const row = await db.prepare('SELECT COUNT(*) AS n, COALESCE(SUM(LENGTH(ciphertext)), 0) AS bytes FROM sync_records WHERE code = ?').bind(code).first();
      return { records: row?.n ?? 0, bytes: row?.bytes ?? 0 };
    },
    /** Newer versions win per item; each accepted change gets the next `seq` for the account. */
    async putSyncRecords(code, records) {
      const results = await db.batch(records.map((r) => db.prepare(`INSERT INTO sync_records (code, item, seq, updated_at, nonce, ciphertext)
        VALUES (?1, ?2, (SELECT COALESCE(MAX(seq), 0) + 1 FROM sync_records WHERE code = ?1), ?3, ?4, ?5)
        ON CONFLICT (code, item) DO UPDATE SET seq = excluded.seq, updated_at = excluded.updated_at, nonce = excluded.nonce, ciphertext = excluded.ciphertext
        WHERE excluded.updated_at > sync_records.updated_at`).bind(code, r.item, r.updatedAt, r.nonce, r.ciphertext)));
      const accepted = records.filter((_, i) => (results[i]?.meta?.changes ?? 0) > 0).map((r) => r.item);
      const row = await db.prepare('SELECT COALESCE(MAX(seq), 0) AS latest FROM sync_records WHERE code = ?').bind(code).first();
      return { accepted, latest: row?.latest ?? 0 };
    },
    listSyncRecords: (code, after, limit) =>
      all('SELECT item, seq, updated_at, nonce, ciphertext FROM sync_records WHERE code = ? AND seq > ? ORDER BY seq LIMIT ?', code, after, limit),
    listPushTokens: (code) => all('SELECT device, platform, token FROM push_tokens WHERE code = ?', code),
    async deletePushToken(code, device) { await run('DELETE FROM push_tokens WHERE code = ? AND device = ?', code, device); },
    async insertMessage(m) {
      const result = await run('INSERT INTO messages (sender, recipient, peer, nonce, ciphertext, created_at) VALUES (?, ?, ?, ?, ?, ?)',
        m.sender, m.recipient, m.peer ?? null, m.nonce, m.ciphertext, m.created_at);
      return result.meta?.last_row_id ?? null;
    },
    /** Inserts one or two messages in one batch; returns their ids (null for a missing second). */
    async insertMessages(first, second) {
      const insert = (m) => db.prepare('INSERT INTO messages (sender, recipient, peer, nonce, ciphertext, created_at) VALUES (?, ?, ?, ?, ?, ?)')
        .bind(m.sender, m.recipient, m.peer ?? null, m.nonce, m.ciphertext, m.created_at);
      const results = await db.batch(second ? [insert(first), insert(second)] : [insert(first)]);
      return [results[0].meta?.last_row_id ?? null, second ? results[1].meta?.last_row_id ?? null : null];
    },
    listMessages: (recipient, after, limit) =>
      all('SELECT * FROM messages WHERE recipient = ? AND id > ? ORDER BY id LIMIT ?', recipient, after, limit),
    async deleteMessagesUpTo(recipient, id) { await run('DELETE FROM messages WHERE recipient = ? AND id <= ?', recipient, id); },
    async deleteMessagesBetween(sender, recipient) { await run('DELETE FROM messages WHERE sender = ? AND recipient = ?', sender, recipient); },
    async pruneMessages(before) { await run('DELETE FROM messages WHERE created_at < ?', before); },

    async putLink(l, now) {
      await db.batch([
        db.prepare('DELETE FROM link_sessions WHERE expires_at < ?').bind(now),
        db.prepare('INSERT INTO link_sessions (id, device_pub, created_at, expires_at, ip) VALUES (?, ?, ?, ?, ?)').bind(l.id, l.device_pub, l.created_at, l.expires_at, l.ip ?? null),
      ]);
    },
    getLink: (id, now) => db.prepare('SELECT * FROM link_sessions WHERE id = ? AND expires_at >= ?').bind(id, now).first(),
    async sealLink(id, senderPub, nonce, ciphertext) {
      await run('UPDATE link_sessions SET sender_pub = ?, nonce = ?, ciphertext = ? WHERE id = ?', senderPub, nonce, ciphertext, id);
    },
    async deleteLink(id) { await run('DELETE FROM link_sessions WHERE id = ?', id); },
  };
}
