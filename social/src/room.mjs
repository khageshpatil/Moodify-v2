/**
 * A listening room (M4): one shared clock and queue; every device plays the
 * song itself from YouTube and stays in sync. No audio passes through here.
 * Free of Cloudflare APIs so it can be tested in Node.
 * Protocol: docs/social-protocol/rooms.md
 */
import { cleanPresence, envelope } from './hub.mjs';

export const ROOM_LIMITS = { members: 20, queue: 200, chatLength: 300, startDelayMs: 1500, emptyTtlMs: 2 * 60_000 };
const ROLES = ['host', 'dj', 'listener'];
const can = {
  playback: (role) => role === 'host' || role === 'dj',
  queue: (role) => role === 'host' || role === 'dj',
  roles: (role) => role === 'host',
};

/** A song in the room: the same fields as presence, minus the playback position. */
export function cleanTrack(input) {
  const p = cleanPresence({ ...input, positionMs: 0, playing: false });
  if (!p) return null;
  const { positionMs, playing, ...track } = p;
  return track;
}

/**
 * @param {object} deps
 * @param {string} deps.roomId
 * @param {() => Array<{send(data: string): void, state: object}>} deps.sockets open member connections
 * @param {() => number} [deps.now] server clock (ms)
 * @param {object} [deps.initial] state restored after the room was evicted
 * @param {(state: object) => void} [deps.onChange] persist state / schedule the next tick
 */
export function createRoom({ roomId, sockets, now = () => Date.now(), initial = null, onChange = () => {} }) {
  let state = initial ?? {
    roomId,
    hostCode: null,
    track: null,
    playing: false,
    anchorPositionMs: 0,
    anchorServerTime: now(),
    queue: [],
    version: 0,
  };
  // Names and join times live on the sockets, so they survive the room being evicted.
  const nameOf = (code) => sockets().find((s) => s.state.code === code)?.state.displayName ?? '';

  const position = () => (state.playing ? state.anchorPositionMs + (now() - state.anchorServerTime) : state.anchorPositionMs);

  function members() {
    const byCode = new Map();
    for (const s of sockets()) {
      if (!s.state.code || byCode.has(s.state.code)) continue;
      byCode.set(s.state.code, { code: s.state.code, displayName: s.state.displayName ?? '', role: s.state.role, joinedAt: s.state.joinedAt ?? 0 });
    }
    return [...byCode.values()].sort((a, b) => a.joinedAt - b.joinedAt);
  }

  const snapshot = () => ({ ...state, positionMs: position(), serverTime: now(), members: members() });

  function broadcast(type, body) {
    const data = envelope(type, body, now());
    for (const s of sockets()) {
      try { s.send(data); } catch { /* closing */ }
    }
  }

  function publish() {
    state = { ...state, version: state.version + 1 };
    broadcast('room.state', snapshot());
    onChange(state);
  }

  function setRole(code, role) {
    for (const s of sockets()) if (s.state.code === code) s.state.role = role;
  }

  /**
   * Starts [track] for everyone a moment from now, so all devices can buffer
   * and begin together. When the host's own player already started it,
   * [positionMs] anchors the clock to the host instead (guests catch up).
   */
  function schedule(track, positionMs = null) {
    const live = Number.isFinite(positionMs);
    state = {
      ...state, track, playing: true,
      anchorPositionMs: live ? Math.max(0, positionMs) : 0,
      anchorServerTime: live ? now() : now() + ROOM_LIMITS.startDelayMs,
    };
    broadcast('track.scheduled', { track, startAtServerTime: state.anchorServerTime });
    publish();
  }

  function advance() {
    const [next, ...rest] = state.queue;
    if (!next) {
      state = { ...state, playing: false, anchorPositionMs: state.track?.durationMs ?? 0, anchorServerTime: now() };
      publish();
      return;
    }
    state = { ...state, queue: rest };
    schedule(next);
  }

  const reply = (socket, type, body) => socket.send(envelope(type, body, now()));

  return {
    snapshot,

    /** The creator becomes host even if someone else connects first. */
    claim(hostCode) {
      if (!state.hostCode) {
        state = { ...state, hostCode };
        onChange(state);
      }
    },

    /** When the current song ends on the room clock (ms), or null. */
    endsAt() {
      if (!state.playing || !state.track?.durationMs) return null;
      return state.anchorServerTime + (state.track.durationMs - state.anchorPositionMs);
    },

    /** First connection creates the room with that person as host. */
    join(socket, { code, displayName }) {
      const others = members().filter((m) => m.code !== code);
      if (others.length >= ROOM_LIMITS.members) return { ok: false, reason: 'room_full' };
      if (!state.hostCode) state = { ...state, hostCode: code };
      const existing = sockets().find((s) => s !== socket && s.state.code === code);
      socket.state.code = code;
      socket.state.displayName = displayName;
      socket.state.joinedAt = existing?.state.joinedAt ?? now();
      socket.state.role = code === state.hostCode ? 'host' : existing?.state.role ?? 'listener';
      reply(socket, 'room.welcome', { you: code, role: socket.state.role });
      publish();
      return { ok: true };
    },

    message(socket, raw) {
      let message;
      try {
        message = JSON.parse(typeof raw === 'string' ? raw : new TextDecoder().decode(raw));
      } catch {
        return reply(socket, 'error', { code: 'bad_json' });
      }
      const body = message?.body ?? {};
      const role = socket.state.role;
      switch (message?.type) {
        case 'ping':
          // Clock sync: the client times the round trip and reads our clock.
          return reply(socket, 'pong', { t0: body.t0 ?? null, serverTime: now() });
        case 'room.chat': {
          const text = typeof body.text === 'string' ? body.text.replace(/\s+/g, ' ').trim().slice(0, ROOM_LIMITS.chatLength) : '';
          if (text) broadcast('room.chat', { from: socket.state.code, displayName: nameOf(socket.state.code), text });
          return;
        }
        case 'room.role': {
          if (!can.roles(role)) return reply(socket, 'error', { code: 'not_allowed' });
          if (!ROLES.includes(body.role) || body.code === state.hostCode) return reply(socket, 'error', { code: 'bad_role' });
          if (body.role === 'host') {
            setRole(state.hostCode, 'dj');
            state = { ...state, hostCode: body.code };
          }
          setRole(body.code, body.role);
          return publish();
        }
        case 'room.control':
          return this.control(socket, body);
        default:
          return reply(socket, 'error', { code: 'unknown_type', type: String(message?.type ?? '') });
      }
    },

    control(socket, body) {
      const role = socket.state.role;
      const action = body.action;
      if (['play', 'pause', 'seek', 'next', 'setTrack'].includes(action) && !can.playback(role)) {
        return reply(socket, 'error', { code: 'not_allowed', action });
      }
      if (['queueAdd', 'queueRemove'].includes(action) && !can.queue(role)) {
        // Listeners can suggest: everyone sees it, a host or DJ decides.
        if (action === 'queueAdd') {
          const track = cleanTrack(body.track);
          if (track) broadcast('room.suggestion', { from: socket.state.code, displayName: nameOf(socket.state.code), track });
        }
        return;
      }
      switch (action) {
        case 'setTrack': {
          const track = cleanTrack(body.track);
          if (!track) return reply(socket, 'error', { code: 'bad_track' });
          return schedule(track, Number.isFinite(body.positionMs) ? Number(body.positionMs) : null);
        }
        case 'play': {
          if (!state.track) return;
          // The host's player says exactly where it resumed; otherwise resume where we paused.
          const hostPosition = Number.isFinite(body.positionMs) ? Math.max(0, Number(body.positionMs)) : null;
          if (state.playing && hostPosition === null) return;
          state = { ...state, playing: true, anchorPositionMs: hostPosition ?? state.anchorPositionMs, anchorServerTime: now() };
          return publish();
        }
        case 'pause':
          if (!state.playing) return;
          state = { ...state, playing: false, anchorPositionMs: Number.isFinite(body.positionMs) ? Math.max(0, Number(body.positionMs)) : position(), anchorServerTime: now() };
          return publish();
        case 'seek': {
          const to = Math.max(0, Math.min(Number(body.positionMs) || 0, state.track?.durationMs || Number.MAX_SAFE_INTEGER));
          state = { ...state, anchorPositionMs: to, anchorServerTime: now() };
          return publish();
        }
        case 'next':
          return advance();
        case 'queueAdd': {
          const track = cleanTrack(body.track);
          if (!track || state.queue.length >= ROOM_LIMITS.queue) return reply(socket, 'error', { code: 'bad_track' });
          state = { ...state, queue: [...state.queue, track] };
          return publish();
        }
        case 'queueRemove': {
          const index = Number(body.index);
          if (!Number.isInteger(index) || index < 0 || index >= state.queue.length) return;
          state = { ...state, queue: state.queue.filter((_, i) => i !== index) };
          return publish();
        }
        default:
          return reply(socket, 'error', { code: 'unknown_action', action: String(action ?? '') });
      }
    },

    /** The song reached its end on the clock: move on (called by a timer/alarm). */
    tick() {
      if (state.playing && state.track?.durationMs && position() >= state.track.durationMs) advance();
    },

    /** After a member's last device left; `sockets()` must no longer include it. */
    leave(code) {
      if (sockets().some((s) => s.state.code === code)) return publish();
      if (code === state.hostCode) {
        // Longest-present DJ first, then the longest-present listener.
        const remaining = members();
        const next = remaining.find((m) => m.role === 'dj') ?? remaining[0];
        state = { ...state, hostCode: next?.code ?? null };
        if (next) setRole(next.code, 'host');
      }
      publish();
    },

    isEmpty: () => sockets().length === 0,
  };
}
