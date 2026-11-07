/**
 * One hub per user (a Durable Object in production): holds that user's live
 * connections, their "listening now" (memory only, never written to disk), and
 * relays events from friends. The logic lives here, free of Cloudflare APIs,
 * so it can be tested in Node. Protocol: docs/social-protocol/friends.md §4
 */

const PRESENCE_MIN_INTERVAL_MS = 1000;
const FRIEND_CACHE_MS = 30_000;

const text = (v, max) => (typeof v === 'string' ? v.slice(0, max) : '');
const num = (v) => (Number.isFinite(v) && v >= 0 ? Math.floor(v) : 0);

/** Keeps only the fields friends may see; anything else a client sends is dropped. */
export function cleanPresence(input) {
  if (!input || typeof input !== 'object' || typeof input.trackId !== 'string' || !/^[A-Za-z0-9_-]{6,20}$/.test(input.trackId)) return null;
  return {
    trackId: input.trackId,
    title: text(input.title, 200),
    artist: text(input.artist, 200),
    artworkUrl: /^https:\/\//.test(input.artworkUrl ?? '') ? text(input.artworkUrl, 500) : '',
    durationMs: num(input.durationMs),
    positionMs: num(input.positionMs),
    playing: input.playing === true,
  };
}

export const envelope = (type, body = {}, ts = Date.now()) => JSON.stringify({ v: 1, type, ts, body });

/**
 * @param {object} deps
 * @param {string} deps.code this hub's user
 * @param {() => Array<{send(data: string): void, state: object}>} deps.sockets open connections
 * @param {() => Promise<string[]>} deps.friendCodes accepted friends
 * @param {(code: string, event: object) => Promise<void>} deps.sendToHub deliver an event to another user's hub
 * @param {(code: string, from: string) => Promise<object|null>} deps.askHub read a friend's presence
 */
export function createHub({ code, sockets, friendCodes, sendToHub, askHub, now = () => Date.now() }) {
  let friendCache = null;
  let lastSeen = null; // { presence, at } after the last device goes offline
  let lastFanoutAt = 0;
  let lastFanoutKey = '';

  async function friends() {
    if (!friendCache || now() - friendCache.at > FRIEND_CACHE_MS) friendCache = { at: now(), codes: await friendCodes() };
    return friendCache.codes;
  }

  /** What friends see: the most recently updated sharing device, or nothing. */
  function currentPresence() {
    const open = sockets();
    if (open.some((s) => s.state.sharing === false)) return { online: false, presence: null, lastSeenAt: null };
    const live = open.map((s) => s.state).filter((st) => st.presence).sort((x, y) => y.updatedAt - x.updatedAt)[0];
    if (live) return { online: true, presence: live.presence, lastSeenAt: null };
    if (open.length > 0) return { online: true, presence: null, lastSeenAt: null };
    return { online: false, presence: lastSeen?.presence ?? null, lastSeenAt: lastSeen?.at ?? null };
  }

  async function fanOut(force = false) {
    const snapshot = currentPresence();
    const key = JSON.stringify([snapshot.online, snapshot.presence?.trackId, snapshot.presence?.playing]);
    // Position ticks are rate-limited; track and play/pause changes go out at once.
    if (!force && key === lastFanoutKey && now() - lastFanoutAt < PRESENCE_MIN_INTERVAL_MS) return;
    lastFanoutAt = now();
    lastFanoutKey = key;
    const event = { type: 'presence.friend', body: { code, ...snapshot } };
    await Promise.all((await friends()).map((friend) => sendToHub(friend, event).catch(() => {})));
  }

  return {
    async onConnect(socket) {
      socket.state.sharing ??= true;
      socket.state.presence ??= null;
      socket.state.updatedAt ??= 0;
      const list = await friends();
      const presences = await Promise.all(list.map(async (friend) => {
        const p = await askHub(friend, code).catch(() => null);
        return p ? { code: friend, ...p } : null;
      }));
      socket.send(envelope('hello', { code, friends: presences.filter(Boolean) }, now()));
      await fanOut(true);
    },

    async onMessage(socket, raw) {
      let message;
      try {
        message = JSON.parse(typeof raw === 'string' ? raw : new TextDecoder().decode(raw));
      } catch {
        socket.send(envelope('error', { code: 'bad_json' }, now()));
        return;
      }
      switch (message?.type) {
        case 'ping':
          socket.send(envelope('pong', { t: message.body?.t ?? null }, now()));
          return;
        case 'presence.update': {
          socket.state.presence = message.body?.cleared ? null : cleanPresence(message.body);
          socket.state.updatedAt = now();
          await fanOut(false);
          return;
        }
        case 'settings.update': {
          // "Share what I'm listening to": off makes you appear offline to friends.
          socket.state.sharing = message.body?.sharing !== false;
          await fanOut(true);
          return;
        }
        default:
          socket.send(envelope('error', { code: 'unknown_type', type: String(message?.type ?? '') }, now()));
      }
    },

    /** Called after [closing] left; `sockets()` must no longer include it. */
    async onClose(closing) {
      if (sockets().length === 0) {
        const last = closing.state;
        lastSeen = last.sharing !== false && last.presence ? { presence: { ...last.presence, playing: false }, at: now() } : lastSeen;
      }
      await fanOut(true);
    },

    /** Events from the API or other hubs, forwarded to every open device. */
    async onEvent(event) {
      if (event.type === 'friend.accepted' || event.type === 'friend.removed') friendCache = null;
      const data = envelope(event.type, event.body ?? {}, now());
      for (const socket of sockets()) {
        try { socket.send(data); } catch { /* closed meanwhile */ }
      }
      // A new friend should see what you're playing straight away.
      if (event.type === 'friend.accepted') await fanOut(true);
    },

    /** A friend's hub asking what this user is playing; strangers get nothing. */
    async presenceFor(requester) {
      if (!(await friends()).includes(requester)) return null;
      return currentPresence();
    },
  };
}
