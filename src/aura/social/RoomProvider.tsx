import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import type { Track } from '@/data/mockMusic';
import { getCanonicalPlaybackEngine } from '@/playback/PlaybackEngine';
import { usePlayer } from '../PlayerContext';
import { ClockSync, followStep, normalizeRoomId, roomPositionMs } from './roomSync';
import { SOCIAL_URL, useSocial, type SocialUser } from './SocialProvider';
import type { WireRoomBody, WireTrack } from './wire';

/** Listen together on the web (docs/social-protocol/rooms.md). Android: data/social/Rooms.kt. */

export interface RoomMember { code: string; displayName: string; role: 'host' | 'dj' | 'listener' }
export interface RoomState {
  roomId: string;
  you: string;
  role: RoomMember['role'];
  hostCode: string | null;
  members: RoomMember[];
  track: Track | null;
  playing: boolean;
  anchorPositionMs: number;
  anchorServerTime: number;
  queue: Track[];
  chat: Array<{ from: string; displayName: string; text: string }>;
  suggestions: Array<{ displayName: string; track: Track }>;
  connected: boolean;
  error: string | null;
  /** Following a friend: a one-way "room" fed by their listening now, no server room (rooms.md §7). */
  following: { code: string; name: string } | null;
}

interface RoomContextValue {
  room: RoomState | null;
  /** The browser blocked sound until the person taps (autoplay rules). */
  needsTap: boolean;
  unblock(): void;
  start(): Promise<void>;
  join(roomId: string): void;
  /** Your player mirrors this friend's until you leave or play something yourself. */
  followFriend(friend: SocialUser): void;
  leave(): void;
  chat(text: string): void;
  setRole(code: string, role: RoomMember['role']): void;
  next(): void;
  queue(track: Track): void;
  invite(friend: SocialUser): Promise<void>;
}

const RoomContext = createContext<RoomContextValue | null>(null);
export const useRoom = () => {
  const value = useContext(RoomContext);
  if (!value) throw new Error('useRoom must be used inside <RoomProvider>');
  return value;
};

const canControl = (room: RoomState | null) => !room?.following && (room?.role === 'host' || room?.role === 'dj');
const trackKey = (track: Track | null | undefined) => (track ? track.providerId || track.id : null);

const toWire = (track: Track) => ({
  trackId: track.providerId || track.id, title: track.title, artist: track.artist,
  artworkUrl: track.albumArt, durationMs: Math.round((track.duration || 0) * 1000),
});
const fromWire = (t: WireTrack): Track => ({
  id: t.trackId, title: t.title ?? '', artist: t.artist ?? '', album: '', albumArt: t.artworkUrl ?? '',
  duration: Math.round((t.durationMs ?? 0) / 1000), provider: 'youtube-music', providerId: t.trackId,
});

const ROOM_KEY = 'moodify.room';
const SEEK_DETECT_MS = 2500;
/** The web can't seek back instantly (the stream starts where it was opened), so it waits instead. */
const WAIT_WHEN_AHEAD_MS = 6000;
const LOCAL_ACTION_MS = 3000;

export function RoomProvider({ children }: { children: ReactNode }) {
  const social = useSocial();
  const player = usePlayer();
  const engine = getCanonicalPlaybackEngine();
  // A reload of this tab rejoins the room it was in (per tab, so other tabs aren't pulled in).
  const [room, setRoom] = useState<RoomState | null>(() => {
    try { const saved = normalizeRoomId(sessionStorage.getItem(ROOM_KEY) ?? ''); return saved ? blankRoom(saved) : null; } catch { return null; }
  });
  const roomRef = useRef<RoomState | null>(null);
  roomRef.current = room;
  const playerRef = useRef(player);
  playerRef.current = player;
  const socket = useRef<WebSocket | null>(null);
  const clock = useRef(new ClockSync());
  /** Our own play/pause/seek calls (following the room) aren't the person's. */
  const quietUntil = useRef(0);
  /** After this device changed the room, don't follow until the room echoes it. */
  const localActionUntil = useRef(0);
  const nudging = useRef(false);
  const endedAt = useRef(0);
  const blocked = useRef(false);
  /** How long reopening the stream at a new point takes here (measured; ms). */
  const reopenLatency = useRef(3000);
  const reopenStartedAt = useRef(0);
  const lastDebugLog = useRef(0);
  const [needsTap, setNeedsTap] = useState(false);
  const socialRef = useRef(social);
  socialRef.current = social;
  /** Server time: the room's clock in a room, the live connection's while following. */
  const serverNow = () => (roomRef.current?.following ? socialRef.current.serverNow() : clock.current.serverNow(Date.now()));

  // Joining from a link has no tap yet, so the browser may refuse to play.
  useEffect(() => {
    const unsubscribe = engine.subscribe((snapshot) => {
      if (snapshot.error?.code === 'PLAY_REJECTED' && roomRef.current && !blocked.current) {
        blocked.current = true;
        setNeedsTap(true);
      }
    });
    return () => { unsubscribe(); };
  }, [engine]);

  const send = useCallback((type: string, body: object) => {
    const ws = socket.current;
    if (ws?.readyState === WebSocket.OPEN) ws.send(JSON.stringify({ v: 1, type, body }));
  }, []);
  const control = useCallback((action: string, body: object = {}) => send('room.control', { action, ...body }), [send]);
  const positionMs = () => Math.round(engine.getMedia().currentTime * 1000);
  const busy = () => engine.getSnapshot().status === 'loading';
  const quiet = (ms = 800) => { quietUntil.current = Date.now() + ms; };
  const localAction = () => { localActionUntil.current = Date.now() + LOCAL_ACTION_MS; };

  /** A host or DJ makes the room play what this device plays (paused here = paused there). */
  const pushLocalTrack = useCallback(() => {
    const track = playerRef.current.currentTrack;
    if (!track || !canControl(roomRef.current) || !roomRef.current?.connected) return;
    localAction();
    control('setTrack', { track: toWire(track), ...(busy() ? {} : { positionMs: positionMs() }) });
    if (!busy() && engine.getMedia().paused) control('pause', { positionMs: positionMs() });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [control]);

  const follow = useCallback((s: RoomState) => {
    if (!s.track || Date.now() < localActionUntil.current) return;
    const audio = engine.getMedia();
    const step = followStep({
      roomTrackId: trackKey(s.track)!,
      localTrackId: trackKey(playerRef.current.currentTrack),
      localPositionMs: positionMs(),
      busy: busy(),
      nudging: nudging.current,
      room: s,
      serverNow: serverNow(),
      durationMs: s.track.duration * 1000 || undefined,
      waitWhenAheadMs: WAIT_WHEN_AHEAD_MS,
    });
    if (!step) return;
    if (import.meta.env.DEV && step.kind === 'match' && Date.now() - lastDebugLog.current > 5000) {
      lastDebugLog.current = Date.now();
      const expected = roomPositionMs(s, serverNow());
      console.debug(`[room] follow local=${positionMs()} room=${Math.round(expected)} drift=${Math.round(positionMs() - expected)} rate=${step.rate} offset=${Math.round(clock.current.offsetMs)}`);
    }
    if (step.kind === 'load') { quiet(4000); playerRef.current.playTrack(s.track, [s.track]); return; }
    if (blocked.current) return; // waiting for the person's tap
    if (step.kind === 'hold') { if (!audio.paused) { quiet(); engine.pause(); } return; }
    if (step.play && audio.paused) { quiet(); void engine.play(); }
    if (!step.play && !audio.paused) { quiet(); engine.pause(); }
    if (step.seekToMs !== undefined) {
      quiet(4000);
      let to = step.seekToMs;
      if (!engine.canSeekWithin(to / 1000)) {
        // A new stream takes a moment to start: aim where the room will be by then.
        to += reopenLatency.current * 1.2 + 500;
        if (s.track.duration) to = Math.min(to, s.track.duration * 1000);
        reopenStartedAt.current = Date.now();
      }
      engine.seek(to / 1000);
    }
    if (audio.playbackRate !== step.rate) audio.playbackRate = step.rate; // pitch is preserved by default
    nudging.current = step.rate !== 1;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const handle = useCallback((raw: string) => {
    let message: { type?: string; body?: WireRoomBody };
    try { message = JSON.parse(raw); } catch { return; }
    const body: WireRoomBody = message.body ?? {};
    switch (message.type) {
      case 'pong': clock.current.onPong(Number(body.t0), Number(body.serverTime), Date.now()); return;
      case 'room.welcome': setRoom((s) => s && { ...s, you: body.you ?? '', role: body.role ?? 'listener' }); return;
      case 'room.state': {
        const current = roomRef.current;
        if (!current) return;
        const members: RoomMember[] = (body.members ?? []).map((m) => ({ code: m.code, displayName: m.displayName, role: m.role }));
        const next: RoomState = {
          ...current,
          hostCode: body.hostCode ?? null,
          members,
          role: members.find((m) => m.code === current.you)?.role ?? current.role,
          track: body.track ? fromWire(body.track) : null,
          playing: !!body.playing,
          anchorPositionMs: Number(body.anchorPositionMs) || 0,
          anchorServerTime: Number(body.anchorServerTime) || 0,
          queue: (body.queue ?? []).map(fromWire),
        };
        roomRef.current = next;
        setRoom(next);
        if (!next.track && canControl(next)) pushLocalTrack();
        else follow(next);
        return;
      }
      case 'room.chat':
        setRoom((s) => s && { ...s, chat: [...s.chat, { from: body.from, displayName: body.displayName, text: body.text }].slice(-100) });
        return;
      case 'room.suggestion':
        if (body.track) setRoom((s) => s && { ...s, suggestions: [...s.suggestions, { displayName: body.displayName, track: fromWire(body.track) }].slice(-20) });
        return;
      default:
    }
  }, [follow, pushLocalTrack]);

  // The connection, clock sync and follow loop live as long as you're in the room.
  const roomId = room?.roomId ?? null;
  useEffect(() => {
    try { if (roomId) sessionStorage.setItem(ROOM_KEY, roomId); else sessionStorage.removeItem(ROOM_KEY); } catch { /* private mode */ }
  }, [roomId]);
  useEffect(() => {
    if (!roomId) return;
    let closed = false;
    let backoff = 2000;
    let failuresBeforeOpen = 0;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const ping = () => { for (let i = 0; i < 5; i++) setTimeout(() => send('ping', { t0: Date.now() }), i * 200); };
    const connect = async () => {
      let opened = false;
      try {
        const token = await social.getToken();
        const ws = new WebSocket(`${SOCIAL_URL.replace(/^http/, 'ws')}/v1/rooms/${roomId}/live?token=${encodeURIComponent(token)}`);
        socket.current = ws;
        ws.onopen = () => {
          opened = true; backoff = 2000; failuresBeforeOpen = 0;
          clock.current.reset();
          setRoom((s) => s && { ...s, connected: true, error: null });
          roomRef.current = roomRef.current && { ...roomRef.current, connected: true };
          ping();
        };
        ws.onmessage = (event) => handle(String(event.data));
        ws.onclose = (event) => {
          if (socket.current === ws) socket.current = null;
          if (closed) return;
          if (event.reason === 'room_full') { setRoom((s) => s && { ...s, connected: false, error: 'This room is full.' }); return; }
          // Browsers hide the HTTP status: a room that keeps refusing us has ended.
          if (!opened && ++failuresBeforeOpen >= 3) {
            try { sessionStorage.removeItem(ROOM_KEY); } catch { /* private mode */ }
            setRoom((s) => s && { ...s, connected: false, error: 'This room has ended.' });
            return;
          }
          setRoom((s) => s && { ...s, connected: false, error: 'Reconnecting…' });
          timer = setTimeout(connect, backoff);
          backoff = Math.min(backoff * 2, 30_000);
        };
      } catch {
        if (!closed) { setRoom((s) => s && { ...s, error: "Couldn't reach Moodify." }); timer = setTimeout(connect, backoff); backoff = Math.min(backoff * 2, 30_000); }
      }
    };
    void connect();

    const resync = setInterval(() => { clock.current.reset(); ping(); }, 60_000);

    // Follow the room every second; a host or DJ who seeked moves the room with them.
    const loop = setInterval(() => { const s = roomRef.current; if (s) follow(s); }, 1000);

    // The person's own play / pause / seek (not ours, not a song loading) drives the room.
    const audio = engine.getMedia();
    const ownAction = () => Date.now() > quietUntil.current && !busy() && canControl(roomRef.current)
      && trackKey(roomRef.current?.track) === trackKey(playerRef.current.currentTrack);
    const onPlay = () => { if (ownAction() && !roomRef.current!.playing) { localAction(); control('play', { positionMs: positionMs() }); } };
    const onPause = () => {
      if (audio.ended) return; // the end of a song isn't a pause: the room moves on
      if (ownAction() && roomRef.current!.playing) { localAction(); control('pause', { positionMs: positionMs() }); }
    };
    const onSeeked = () => {
      if (!ownAction()) return;
      const s = roomRef.current!;
      if (Math.abs(positionMs() - roomPositionMs(s, clock.current.serverNow(Date.now()))) > SEEK_DETECT_MS) {
        localAction(); control('seek', { positionMs: positionMs() });
      }
    };
    const onEnded = () => { endedAt.current = Date.now(); };
    const onPlaying = () => {
      if (!reopenStartedAt.current) return;
      const took = Date.now() - reopenStartedAt.current;
      reopenStartedAt.current = 0;
      if (took < 20_000) reopenLatency.current = Math.round(reopenLatency.current * 0.5 + took * 0.5);
    };
    audio.addEventListener('playing', onPlaying);
    audio.addEventListener('play', onPlay);
    audio.addEventListener('pause', onPause);
    audio.addEventListener('seeked', onSeeked);
    audio.addEventListener('ended', onEnded);

    return () => {
      closed = true;
      clearTimeout(timer); clearInterval(resync); clearInterval(loop);
      audio.removeEventListener('play', onPlay);
      audio.removeEventListener('pause', onPause);
      audio.removeEventListener('seeked', onSeeked);
      audio.removeEventListener('ended', onEnded);
      audio.removeEventListener('playing', onPlaying);
      audio.playbackRate = 1;
      nudging.current = false;
      socket.current?.close(1000, 'left');
      socket.current = null;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [roomId]);

  // Following a friend: their "listening now" is the room; your own play, pause
  // or pick ends it.
  const followingCode = room?.following?.code ?? null;
  const friendPresence = followingCode ? social.presence[followingCode] : undefined;
  useEffect(() => {
    const current = roomRef.current;
    if (!followingCode || !current?.following) return;
    const listening = !!friendPresence?.online && !!friendPresence.track;
    const next: RoomState = listening ? {
      ...current, error: null, track: friendPresence!.track!, playing: friendPresence!.playing,
      anchorPositionMs: friendPresence!.positionMs, anchorServerTime: friendPresence!.at || socialRef.current.serverNow(),
    } : {
      ...current, playing: false, anchorPositionMs: positionMs(), anchorServerTime: socialRef.current.serverNow(),
      error: `${current.following.name} isn't listening right now`,
    };
    roomRef.current = next;
    setRoom(next);
    follow(next);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [followingCode, friendPresence]);
  useEffect(() => {
    if (!followingCode) return;
    const loop = setInterval(() => { const s = roomRef.current; if (s?.following) follow(s); }, 1000);
    const audio = engine.getMedia();
    const mine = () => Date.now() > quietUntil.current && !busy();
    const stop = () => { if (mine() && !audio.ended) { setRoom(null); blocked.current = false; setNeedsTap(false); } };
    audio.addEventListener('play', stop);
    audio.addEventListener('pause', stop);
    return () => {
      clearInterval(loop);
      audio.removeEventListener('play', stop);
      audio.removeEventListener('pause', stop);
      audio.playbackRate = 1;
      nudging.current = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [followingCode]);

  // A host or DJ who picks a song plays it for everyone. (After a song ends, the
  // player's own autoplay isn't a pick: the room moves on from its queue.)
  const localKey = trackKey(player.currentTrack);
  useEffect(() => {
    const s = roomRef.current;
    if (!s || !localKey || localKey === trackKey(s.track)) return;
    if (Date.now() < quietUntil.current || Date.now() - endedAt.current < 5000) return;
    if (s.following) { setRoom(null); return; } // you picked your own song: stop following
    if (!canControl(s)) return;
    localAction();
    control('setTrack', { track: toWire(playerRef.current.currentTrack!) });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [localKey]);

  const value = useMemo<RoomContextValue>(() => ({
    room,
    needsTap,
    unblock() {
      blocked.current = false;
      setNeedsTap(false);
      quiet();
      void engine.play(); // inside the tap, so the browser allows it; following takes over from here
    },
    async start() {
      const id = await social.createRoom();
      setRoom(blankRoom(id));
    },
    join(raw) {
      const id = normalizeRoomId(raw);
      if (!id) return;
      if (roomRef.current?.roomId !== id) setRoom(blankRoom(id));
    },
    followFriend(friend) {
      blocked.current = false;
      setNeedsTap(false);
      const next: RoomState = { ...blankRoom(''), hostCode: friend.code, connected: true, following: { code: friend.code, name: friend.displayName } };
      roomRef.current = next;
      setRoom(next);
    },
    leave() { setRoom(null); blocked.current = false; setNeedsTap(false); },
    chat(text) { const t = text.trim(); if (t) send('room.chat', { text: t }); },
    setRole(code, role) { send('room.role', { code, role }); },
    next() { control('next'); },
    queue(track) { control('queueAdd', { track: toWire(track) }); },
    async invite(friend) {
      const s = roomRef.current;
      if (s) await social.sendRoomInvite(friend, s.roomId, s.track);
    },
  }), [room, needsTap, social, send, control, engine]);

  return <RoomContext.Provider value={value}>{children}</RoomContext.Provider>;
}

const blankRoom = (roomId: string): RoomState => ({
  roomId, you: '', role: 'listener', hostCode: null, members: [], track: null, playing: false,
  anchorPositionMs: 0, anchorServerTime: 0, queue: [], chat: [], suggestions: [], connected: false, error: null, following: null,
});
