/**
 * moodify-social: friends, presence, chat and listening rooms for Moodify.
 * Separate from the music Worker so neither can take the other down.
 * Design: docs/SOCIAL_AND_WEB_DESIGN.md · Protocol: docs/social-protocol/
 */
import { createApi, d1Store } from './api.mjs';
import { pushFromEnv } from './push.mjs';
import { createHub } from './hub.mjs';
import { ROOM_LIMITS, createRoom } from './room.mjs';
import { createBurstLimiter } from './limits.mjs';
import { readToken } from './tokens.mjs';

/** Per isolate (best-effort) burst limits, shared by every request this isolate serves. */
const burst = createBurstLimiter();
/** Live connections one account may hold open at once (phones, tablets, browser tabs). */
const MAX_LIVE_CONNECTIONS = 10;
/** Listening rooms one account may open in an hour. */
const ROOMS_PER_HOUR = 20;

const jsonError = (status, code, message) => new Response(JSON.stringify({ error: { code, message } }), {
  status,
  headers: { 'Content-Type': 'application/json' },
});

/**
 * Browsers (the web app) call this API from another origin. Auth is a bearer
 * token, never a cookie, so allowing any origin exposes nothing extra.
 */
const CORS = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Methods': 'GET, POST, PUT, PATCH, DELETE, OPTIONS',
  'Access-Control-Allow-Headers': 'Authorization, Content-Type',
  'Access-Control-Max-Age': '86400',
};
const withCors = (response) => {
  if (response.status === 101) return response; // WebSocket upgrade
  const headers = new Headers(response.headers);
  for (const [key, value] of Object.entries(CORS)) headers.set(key, value);
  return new Response(response.body, { status: response.status, statusText: response.statusText, headers });
};

/** A path with ids replaced, so logs group by route and never carry codes. */
const routeName = (path) => path.replace(/MOOD-[0-9A-Z]{4}-[0-9A-Z]{4}/gi, ':code').replace(/\/[0-9A-Z]{6,8}(?=\/|$)/gi, '/:id');

const ROOM_ALPHABET = '0123456789ABCDEFGHJKMNPQRSTVWXYZ';
const roomFor = (env, roomId) => env.ROOMS.get(env.ROOMS.idFromName(roomId));

/** The signed-in account for a request (Bearer header, or ?token= for WebSockets). */
async function authed(request, env, queryToken = null) {
  const header = request.headers.get('Authorization') || '';
  const token = header.startsWith('Bearer ') ? header.slice(7) : queryToken;
  const code = await readToken(env.SESSION_SECRET, token);
  return code ? d1Store(env.DB).getAccount(code) : null;
}

const hubFor = (env, code) => env.USER_HUB.get(env.USER_HUB.idFromName(code));

/** Delivers an API event (friend request, new message…) to a user's open devices; returns their device ids. */
const notifyHub = (env) => async (code, event) => {
  try {
    const response = await hubFor(env, code).fetch('https://hub/event', { method: 'POST', body: JSON.stringify(event) });
    return response.status === 200 ? (await response.json()).devices : [];
  } catch (err) {
    console.warn('hub notify failed', err?.message);
  }
};

export default {
  async fetch(request, env, ctx) {
    if (request.method === 'OPTIONS') return new Response(null, { status: 204, headers: CORS });
    return withCors(await route(request, env, ctx));
  },
};

async function route(request, env, ctx) {
  if (!env.SESSION_SECRET) return jsonError(500, 'misconfigured', 'SESSION_SECRET is not set.');
  const url = new URL(request.url);

  // Live connection: authenticate here, then hand the socket to the user's hub.
  if (url.pathname === '/v1/live') {
    if (request.headers.get('Upgrade') !== 'websocket') return jsonError(426, 'upgrade_required', 'Connect with a WebSocket.');
    const token = url.searchParams.get('token') || (request.headers.get('Sec-WebSocket-Protocol') || '').replace(/^bearer\.?/, '');
    const code = await readToken(env.SESSION_SECRET, token);
    if (!code || !(await d1Store(env.DB).getAccount(code))) return jsonError(401, 'unauthorized', 'Sign in again.');
    const forwarded = new Request('https://hub/connect', request);
    forwarded.headers.set('X-Moodify-Code', code);
    // Which device this is, so it isn't sent a wake-up ping while connected.
    const device = url.searchParams.get('device') ?? '';
    if (/^[A-Za-z0-9_-]{8,64}$/.test(device)) forwarded.headers.set('X-Moodify-Device', device);
    return hubFor(env, code).fetch(forwarded);
  }

  // Listening rooms (M4).
  if (url.pathname === '/v1/rooms' && request.method === 'POST') {
    const me = await authed(request, env);
    if (!me) return jsonError(401, 'unauthorized', 'Sign in again.');
    if (!burst.allow(`room:${me.code}`, ROOMS_PER_HOUR, 3_600_000)) return jsonError(429, 'slow_down', 'Too many rooms. Try again later.');
    const roomId = Array.from(crypto.getRandomValues(new Uint8Array(6)), (b) => ROOM_ALPHABET[b % 32]).join('');
    await roomFor(env, roomId).fetch(`https://room/claim?id=${roomId}`, { method: 'POST', body: JSON.stringify({ hostCode: me.code }) });
    return Response.json({ roomId, invite: `moodify://room/${roomId}` }, { status: 201 });
  }
  const roomLive = url.pathname.match(/^\/v1\/rooms\/([0-9A-HJKMNP-TV-Z]{6})\/live$/i);
  if (roomLive) {
    if (request.headers.get('Upgrade') !== 'websocket') return jsonError(426, 'upgrade_required', 'Connect with a WebSocket.');
    const me = await authed(request, env, url.searchParams.get('token'));
    if (!me) return jsonError(401, 'unauthorized', 'Sign in again.');
    const forwarded = new Request('https://room/connect', request);
    forwarded.headers.set('X-Moodify-Code', me.code);
    forwarded.headers.set('X-Moodify-Name', encodeURIComponent(me.display_name));
    return roomFor(env, roomLive[1].toUpperCase()).fetch(forwarded);
  }

  const handle = createApi({
    store: d1Store(env.DB), secret: env.SESSION_SECRET, notify: notifyHub(env),
    push: pushFromEnv(env), defer: (work) => (ctx?.waitUntil ? ctx.waitUntil(work) : work),
    vapidPublicKey: env.VAPID_PUBLIC_KEY || null, burst,
  });
  try {
    return await handle(request);
  } catch (err) {
    // Workers Logs keeps these (wrangler.toml [observability]); never log bodies or tokens.
    console.error(JSON.stringify({ event: 'api_error', method: request.method, path: routeName(url.pathname), error: err?.message, stack: err?.stack?.split('\n').slice(0, 4).join(' | ') }));
    return jsonError(500, 'server_error', 'Something went wrong.');
  }
}

/**
 * Durable Object: one per user. Uses WebSocket hibernation, so idle
 * connections cost almost nothing; per-socket state survives in attachments.
 */
export class UserHub {
  constructor(state, env) {
    this.state = state;
    this.env = env;
    this.hub = null;
  }

  core(code) {
    if (this.hub) return this.hub;
    const store = d1Store(this.env.DB);
    this.hub = createHub({
      code,
      sockets: () => this.state.getWebSockets()
        .filter((ws) => ws.readyState === 1)
        .map((ws) => this.wrap(ws)),
      friendCodes: () => store.friendCodes(code),
      sendToHub: (friend, event) => hubFor(this.env, friend)
        .fetch('https://hub/event', { method: 'POST', body: JSON.stringify(event) })
        .then((response) => (response.status === 200 ? response.json() : { open: 0 }))
        .then((body) => body.open ?? 0),
      askHub: async (friend, from) => {
        const response = await hubFor(this.env, friend).fetch('https://hub/presence', { headers: { 'X-From': from } });
        return response.ok ? response.json() : null;
      },
    });
    return this.hub;
  }

  /** A socket plus its attachment, saved back whenever the hub changes it. */
  wrap(ws) {
    const state = ws.deserializeAttachment() ?? {};
    return {
      ws,
      send: (data) => ws.send(data),
      state: new Proxy(state, {
        set: (target, key, value) => {
          target[key] = value;
          ws.serializeAttachment(target);
          return true;
        },
      }),
    };
  }

  async codeOf(request) {
    let code = request.headers.get('X-Moodify-Code');
    if (code) await this.state.storage.put('code', code);
    else code = await this.state.storage.get('code');
    return code;
  }

  async fetch(request) {
    const url = new URL(request.url);
    const code = await this.codeOf(request);
    if (!code) return new Response(null, { status: 204 }); // a hub nobody has connected to yet
    const hub = this.core(code);

    if (url.pathname === '/connect') {
      // Too many open connections for one account: turn the new one away (closing the oldest
      // instead would let 11+ devices keep knocking each other off as they reconnect).
      if (this.state.getWebSockets().filter((ws) => ws.readyState === 1).length >= MAX_LIVE_CONNECTIONS) {
        const [client, server] = Object.values(new WebSocketPair());
        server.accept();
        server.close(4008, 'too_many_connections');
        return new Response(null, { status: 101, webSocket: client });
      }
      const [client, server] = Object.values(new WebSocketPair());
      this.state.acceptWebSocket(server);
      server.serializeAttachment({ sharing: true, presence: null, updatedAt: 0, device: request.headers.get('X-Moodify-Device') ?? null });
      await hub.onConnect(this.wrap(server));
      return new Response(null, { status: 101, webSocket: client });
    }
    if (url.pathname === '/event' && request.method === 'POST') {
      await hub.onEvent(await request.json());
      const open = this.state.getWebSockets().filter((ws) => ws.readyState === 1);
      const devices = open.map((ws) => ws.deserializeAttachment()?.device).filter(Boolean);
      return Response.json({ devices, open: open.length });
    }
    if (url.pathname === '/presence') {
      const presence = await hub.presenceFor(request.headers.get('X-From') ?? '');
      return presence ? Response.json(presence) : new Response(null, { status: 404 });
    }
    return new Response(null, { status: 404 });
  }

  async webSocketMessage(ws, message) {
    const code = await this.state.storage.get('code');
    if (code) await this.core(code).onMessage(this.wrap(ws), message);
  }

  async webSocketClose(ws) {
    const code = await this.state.storage.get('code');
    if (code) await this.core(code).onClose(this.wrap(ws));
  }

  async webSocketError(ws) {
    await this.webSocketClose(ws);
  }
}

/**
 * Durable Object: one per listening room. Room state (song, clock, queue) is
 * kept in this object's storage so it survives hibernation, and deleted once
 * the room has been empty for two minutes.
 */
export class Room {
  constructor(state) {
    this.state = state;
    this.room = null;
    this.state.blockConcurrencyWhile(async () => {
      const saved = await this.state.storage.get('room');
      const roomId = (await this.state.storage.get('roomId')) ?? null;
      this.room = this.create(roomId, saved ?? null);
    });
  }

  create(roomId, initial) {
    return createRoom({
      roomId,
      initial,
      sockets: () => this.state.getWebSockets().filter((ws) => ws.readyState === 1).map((ws) => this.wrap(ws)),
      onChange: (roomState) => {
        void this.state.storage.put('room', roomState);
        void this.schedule();
      },
    });
  }

  wrap(ws) {
    const state = ws.deserializeAttachment() ?? {};
    return {
      ws,
      send: (data) => ws.send(data),
      state: new Proxy(state, {
        set: (target, key, value) => {
          target[key] = value;
          ws.serializeAttachment(target);
          return true;
        },
      }),
    };
  }

  /** Wake up when the song ends (to advance the queue), or to clean up an empty room. */
  async schedule() {
    const endsAt = this.room.endsAt();
    if (endsAt) await this.state.storage.setAlarm(Math.max(Date.now() + 50, endsAt));
    else if (this.room.isEmpty()) await this.state.storage.setAlarm(Date.now() + ROOM_LIMITS.emptyTtlMs);
  }

  async alarm() {
    if (this.room.isEmpty()) {
      const emptySince = (await this.state.storage.get('emptySince')) ?? Date.now();
      if (Date.now() - emptySince >= ROOM_LIMITS.emptyTtlMs) {
        await this.state.storage.deleteAll();
        this.room = this.create(null, null);
        return;
      }
      await this.state.storage.put('emptySince', emptySince);
    }
    this.room.tick();
    await this.schedule();
  }

  async fetch(request) {
    const url = new URL(request.url);
    if (url.pathname === '/claim' && request.method === 'POST') {
      const { hostCode } = await request.json();
      if (!(await this.state.storage.get('roomId'))) {
        // A fresh room: remember its code (the name this object was addressed by).
        const id = url.searchParams.get('id') ?? '';
        await this.state.storage.put('roomId', id);
        this.room = this.create(id, null);
      }
      this.room.claim(hostCode);
      return new Response(null, { status: 204 });
    }
    if (url.pathname === '/connect') {
      // Only rooms someone opened with POST /v1/rooms exist; an ended room stays ended.
      if (!(await this.state.storage.get('roomId'))) {
        return Response.json({ error: { code: 'room_not_found', message: 'This room has ended.' } }, { status: 404 });
      }
      const [client, server] = Object.values(new WebSocketPair());
      this.state.acceptWebSocket(server);
      server.serializeAttachment({});
      await this.state.storage.delete('emptySince');
      const joined = this.room.join(this.wrap(server), {
        code: request.headers.get('X-Moodify-Code'),
        displayName: decodeURIComponent(request.headers.get('X-Moodify-Name') ?? ''),
      });
      if (!joined.ok) {
        server.close(4003, joined.reason);
        return new Response(null, { status: 101, webSocket: client });
      }
      return new Response(null, { status: 101, webSocket: client });
    }
    return new Response(null, { status: 404 });
  }

  async webSocketMessage(ws, message) {
    this.room.message(this.wrap(ws), message);
  }

  async webSocketClose(ws) {
    const code = ws.deserializeAttachment()?.code;
    if (code) this.room.leave(code);
    if (this.room.isEmpty()) {
      await this.state.storage.put('emptySince', Date.now());
      await this.schedule();
    }
  }

  async webSocketError(ws) {
    await this.webSocketClose(ws);
  }
}
