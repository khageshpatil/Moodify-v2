import type { PlaybackSnapshot, PlaybackTrack } from '@/playback/playbackTypes';
import { ListeningEventStore, listeningEventStore } from './ListeningEventStore';
import type { DiscoveryContext, ListeningEvent, ListeningSession, ListeningSnapshot } from './listeningTypes';
import { identityKey, normalizeDiscoveryContext, toListeningIdentity } from './listeningTypes';

export interface ListeningEventCollectorOptions {
  earlySkipSeconds?: number;
  sessionInactivityMs?: number;
  now?: () => number;
  store?: ListeningEventStore;
}

const makeId = () => {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) return crypto.randomUUID();
  return `listening-${Date.now()}-${Math.random().toString(36).slice(2)}`;
};

export class ListeningEventCollector {
  readonly earlySkipSeconds: number;
  private readonly sessionInactivityMs: number;
  private readonly now: () => number;
  private readonly store: ListeningEventStore;
  private events: ListeningEvent[];
  private session: ListeningSession;
  private activeTrack: PlaybackTrack | null = null;
  private lastSnapshot: ListeningSnapshot = { status: 'idle', currentTrack: null, currentTime: 0, duration: 0 };
  private startedAt = 0;
  private completedTrackKey: string | null = null;
  private restartRequested = false;
  private seekTimer: ReturnType<typeof setTimeout> | null = null;
  private pendingSeek: { track: PlaybackTrack; from: number; to: number; duration: number; source?: string } | null = null;
  private pendingDiscoveryContexts: Array<{ key: string; context: DiscoveryContext }> = [];
  private listeners = new Set<(events: ListeningEvent[]) => void>();

  constructor(options: ListeningEventCollectorOptions = {}) {
    this.earlySkipSeconds = options.earlySkipSeconds ?? 10;
    this.sessionInactivityMs = options.sessionInactivityMs ?? 30 * 60 * 1000;
    this.now = options.now || (() => Date.now());
    this.store = options.store || listeningEventStore;
    this.events = this.store.loadEvents(this.now());
    const storedSession = this.store.loadSession();
    this.session = storedSession && this.now() - storedSession.lastActivityAt <= this.sessionInactivityMs
      ? storedSession
      : { sessionId: makeId(), startedAt: 0, lastActivityAt: 0, tracksPlayed: [] };
  }

  subscribe(listener: (events: ListeningEvent[]) => void) {
    this.listeners.add(listener);
    listener(this.events);
    return () => { this.listeners.delete(listener); };
  }

  getEvents() { return [...this.events]; }
  getSession() { return { ...this.session, tracksPlayed: [...this.session.tracksPlayed] }; }

  recordPlayIntent(track: PlaybackTrack, discoveryContext?: DiscoveryContext) {
    const normalizedContext = normalizeDiscoveryContext(discoveryContext);
    if (!normalizedContext) return;
    const key = identityKey(toListeningIdentity(track));
    this.pendingDiscoveryContexts.push({ key, context: normalizedContext });
    if (this.pendingDiscoveryContexts.length > 100) this.pendingDiscoveryContexts.splice(0, this.pendingDiscoveryContexts.length - 100);
  }

  observe(snapshot: PlaybackSnapshot) {
    const current = this.toListeningSnapshot(snapshot);
    const track = current.currentTrack;
    const trackKey = track ? identityKey(toListeningIdentity(track)) : null;
    const activeKey = this.activeTrack ? identityKey(toListeningIdentity(this.activeTrack)) : null;

    if (current.status === 'playing' && track) {
      const isNewPlay = trackKey !== activeKey || this.restartRequested || this.completedTrackKey === trackKey;
      if (isNewPlay) {
        this.activeTrack = track;
        this.startedAt = this.now();
        this.completedTrackKey = null;
        this.restartRequested = false;
        const pendingIndex = trackKey ? this.pendingDiscoveryContexts.findIndex((entry) => entry.key === trackKey) : -1;
        const discoveryContext = pendingIndex >= 0 ? this.pendingDiscoveryContexts[pendingIndex].context : undefined;
        if (pendingIndex >= 0) this.pendingDiscoveryContexts.splice(pendingIndex, 1);
        this.emit('play_started', track, {
          positionSeconds: current.currentTime,
          durationSeconds: this.duration(current),
          source: 'playback_engine',
          discoveryContext,
        });
      } else if (this.lastSnapshot.status === 'paused') {
        this.emit('resume', track, {
          positionSeconds: current.currentTime,
          durationSeconds: this.duration(current),
          source: 'playback_engine',
        });
        this.touchSession();
      }
    } else if (current.status === 'paused' && this.activeTrack && this.lastSnapshot.status !== 'paused') {
      this.emit('pause', this.activeTrack, {
        positionSeconds: current.currentTime,
        durationSeconds: this.duration(current),
        source: 'playback_engine',
      });
    } else if (current.status === 'ended' && this.activeTrack && this.completedTrackKey !== activeKey) {
      const duration = this.duration(current);
      const position = duration || current.currentTime;
      this.emit('play_completed', this.activeTrack, {
        positionSeconds: position,
        durationSeconds: duration,
        completionRatio: duration > 0 ? Math.min(1, position / duration) : undefined,
        listenedSeconds: Math.max(0, this.now() - this.startedAt) / 1000,
        source: 'playback_engine',
      });
      this.completedTrackKey = activeKey;
      this.touchSession();
    } else if (current.status === 'failed' && trackKey) {
      this.pendingDiscoveryContexts = this.pendingDiscoveryContexts.filter((entry) => entry.key !== trackKey);
    }

    this.lastSnapshot = current;
  }

  recordSkip(source = 'user') {
    if (!this.activeTrack || !this.lastSnapshot.currentTrack) return;
    if (this.lastSnapshot.status === 'ended' || this.lastSnapshot.status === 'failed' || this.lastSnapshot.status === 'idle') return;
    const duration = this.duration(this.lastSnapshot);
    const position = this.lastSnapshot.currentTime;
    this.emit('skip', this.activeTrack, {
      positionSeconds: position,
      durationSeconds: duration,
      completionRatio: duration > 0 ? Math.min(1, position / duration) : undefined,
      earlySkip: position <= this.earlySkipSeconds,
      listenedSeconds: Math.max(0, this.now() - this.startedAt) / 1000,
      source,
    });
  }

  recordReplay(track: PlaybackTrack, source = 'user') {
    if (!this.activeTrack || identityKey(toListeningIdentity(this.activeTrack)) !== identityKey(toListeningIdentity(track))) return;
    this.emit('replay', track, {
      positionSeconds: this.lastSnapshot.currentTime,
      durationSeconds: this.duration(this.lastSnapshot),
      source,
    });
    this.restartRequested = true;
  }

  recordSeek(track: PlaybackTrack | null, from: number, to: number, duration: number, source = 'user') {
    if (!track || !Number.isFinite(from) || !Number.isFinite(to) || Math.abs(to - from) < 0.5) return;
    this.pendingSeek = { track, from, to, duration, source };
    if (this.seekTimer) clearTimeout(this.seekTimer);
    this.seekTimer = setTimeout(() => this.flushSeek(), 250);
  }

  recordFavorite(track: PlaybackTrack, favorite: boolean, source = 'user') {
    this.emit(favorite ? 'favorite' : 'unfavorite', track, { source });
  }

  recordQueueAdded(track: PlaybackTrack, source = 'user') { this.emit('queue_added', track, { source }); }
  recordQueueRemoved(track: PlaybackTrack, source = 'user') { this.emit('queue_removed', track, { source }); }
  recordNotInterested(track: PlaybackTrack, source = 'user') { this.emit('not_interested', track, { source }); }

  private flushSeek() {
    const seek = this.pendingSeek;
    this.pendingSeek = null;
    this.seekTimer = null;
    if (!seek) return;
    this.emit('seek', seek.track, {
      fromPositionSeconds: seek.from,
      toPositionSeconds: seek.to,
      direction: seek.to > seek.from ? 'forward' : seek.to < seek.from ? 'backward' : 'none',
      positionSeconds: seek.to,
      durationSeconds: seek.duration,
      source: seek.source,
    });
  }

  private emit(type: ListeningEvent['type'], track: PlaybackTrack, values: Partial<ListeningEvent>) {
    const timestamp = this.now();
    this.ensureSession(type === 'play_started', identityKey(toListeningIdentity(track)));
    const event: ListeningEvent = {
      id: makeId(),
      type,
      ...toListeningIdentity(track),
      timestamp,
      sessionId: this.session.sessionId,
      artistName: track.artist,
      ...values,
    };
    this.events = this.store.append(event);
    this.touchSession();
    this.listeners.forEach((listener) => listener(this.events));
  }

  private ensureSession(meaningfulPlayback: boolean, trackKey?: string) {
    const now = this.now();
    if (this.session.startedAt > 0 && now - this.session.lastActivityAt > this.sessionInactivityMs) {
      this.session = { sessionId: makeId(), startedAt: 0, lastActivityAt: 0, tracksPlayed: [] };
    }
    if (meaningfulPlayback && this.session.startedAt === 0) this.session.startedAt = now;
    if (meaningfulPlayback && trackKey) this.session.tracksPlayed.push(trackKey);
    this.session.lastActivityAt = now;
    this.store.saveSession(this.session);
  }

  private touchSession() { this.ensureSession(false); }
  private duration(snapshot: ListeningSnapshot) { return snapshot.duration || snapshot.currentTrack?.duration || 0; }
  private toListeningSnapshot(snapshot: PlaybackSnapshot): ListeningSnapshot { return { status: snapshot.status, currentTrack: snapshot.currentTrack, currentTime: snapshot.currentTime, duration: snapshot.duration }; }
}

export const toPlaybackTrack = (track: { id: string; title: string; artist: string; album?: string; albumArt?: string; duration?: number; provider?: string; providerId?: string }): PlaybackTrack => ({ ...track });
