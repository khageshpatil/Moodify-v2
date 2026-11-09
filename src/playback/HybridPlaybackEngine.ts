import { PlaybackEngine } from './PlaybackEngine';
import type { PlaybackSnapshot, PlaybackSourceResolver, PlaybackTrack } from './playbackTypes';
import { YouTubePlaybackEngine } from './YouTubePlaybackEngine';

/**
 * The parts of an <audio> element that rooms and presence read, whichever
 * engine is playing: position, paused, speed, and play/pause/seeked/ended events.
 */
export interface PlaybackMedia {
  readonly currentTime: number;
  readonly paused: boolean;
  readonly ended: boolean;
  playbackRate: number;
  addEventListener(type: MediaEventType, listener: () => void): void;
  removeEventListener(type: MediaEventType, listener: () => void): void;
}
type MediaEventType = 'play' | 'playing' | 'pause' | 'seeked' | 'ended';

/** YouTube errors where the song can't play in an embedded player (or isn't there). */
const EMBED_REFUSED = new Set(['YT_ERROR_100', 'YT_ERROR_101', 'YT_ERROR_150', 'YT_ERROR_5', 'INVALID_VIDEO_ID', 'PLAYER_INIT_FAILED']);

type Which = 'youtube' | 'relay';

/**
 * Moodify's web playback: YouTube's embedded player first (it plays from the
 * listener's own connection), and Moodify's audio relay for songs that can't be
 * embedded. Everything else in the app talks to this one engine.
 */
export class HybridPlaybackEngine {
  private readonly youtube: YouTubePlaybackEngine;
  private readonly relay: PlaybackEngine;
  private active: Which = 'youtube';
  private snapshot: PlaybackSnapshot;
  private listeners = new Set<(snapshot: PlaybackSnapshot) => void>();
  private endedListener: (() => void) | null = null;
  private loadId = 0;
  private fallbackFor = -1;
  private requestedRate = 1;
  private mediaListeners = new Map<MediaEventType, Set<() => void>>();
  readonly media: PlaybackMedia;

  constructor(resolveSource?: PlaybackSourceResolver) {
    this.youtube = new YouTubePlaybackEngine(resolveSource);
    this.relay = new PlaybackEngine(resolveSource);
    this.snapshot = this.youtube.getSnapshot();
    this.youtube.subscribe((s) => this.onSnapshot('youtube', s));
    this.relay.subscribe((s) => this.onSnapshot('relay', s));
    this.youtube.setEndedListener(() => { if (this.active === 'youtube') this.endedListener?.(); });
    this.relay.setEndedListener(() => { if (this.active === 'relay') this.endedListener?.(); });
    this.media = Object.defineProperties({
      addEventListener: (type: MediaEventType, listener: () => void) => { if (!this.mediaListeners.has(type)) this.mediaListeners.set(type, new Set()); this.mediaListeners.get(type)!.add(listener); },
      removeEventListener: (type: MediaEventType, listener: () => void) => { this.mediaListeners.get(type)?.delete(listener); },
    }, {
      currentTime: { get: () => this.currentTimeNow() },
      paused: { get: () => this.snapshot.status !== 'playing' },
      ended: { get: () => this.snapshot.status === 'ended' },
      playbackRate: {
        get: () => this.requestedRate,
        set: (rate: number) => { this.requestedRate = rate; this.current().setPlaybackRate(rate); },
      },
    }) as PlaybackMedia;
    if (import.meta.env?.DEV) (window as Window & { __moodifyPlaybackEngine?: HybridPlaybackEngine }).__moodifyPlaybackEngine = this;
  }

  private current() { return this.active === 'youtube' ? this.youtube : this.relay; }

  private onSnapshot(from: Which, next: PlaybackSnapshot) {
    if (from !== this.active) return;
    // A song YouTube won't embed: try the relay once before giving up.
    if (from === 'youtube' && next.status === 'failed' && next.currentTrack && this.fallbackFor !== this.loadId && EMBED_REFUSED.has(next.error?.code ?? '')) {
      this.fallbackFor = this.loadId;
      this.active = 'relay';
      void this.relay.load(next.currentTrack, true);
      return;
    }
    const before = this.snapshot.status;
    this.snapshot = next;
    this.listeners.forEach((listener) => listener(next));
    if (before !== next.status) {
      if (next.status === 'playing') { this.emit('play'); this.emit('playing'); }
      else if (before === 'playing' && next.status === 'paused') this.emit('pause');
      else if (next.status === 'ended') { this.emit('pause'); this.emit('ended'); }
    }
  }

  private emit(type: MediaEventType) { this.mediaListeners.get(type)?.forEach((listener) => listener()); }

  /** Loads the YouTube player ahead of the first tap, so the first song starts quickly. */
  warmUp() { this.youtube.warmUp(); }

  subscribe(listener: (snapshot: PlaybackSnapshot) => void) {
    this.listeners.add(listener);
    listener(this.snapshot);
    return () => this.listeners.delete(listener);
  }

  getSnapshot() { return this.snapshot; }
  /** The relay's <audio> element (the classic app's equalizer uses it). Use [media] for position and events. */
  getAudioElement() { return this.relay.getAudioElement(); }
  getMedia(): PlaybackMedia { return this.media; }
  setSourceResolver(resolve: PlaybackSourceResolver) { this.relay.setSourceResolver(resolve); this.youtube.setSourceResolver(resolve); }
  setEndedListener(listener: (() => void) | null) { this.endedListener = listener; }

  setMediaSessionHandlers(handlers: { next?: () => void; previous?: () => void }) {
    if (typeof navigator === 'undefined' || !('mediaSession' in navigator)) return;
    const set = (action: MediaSessionAction, handler: (() => void) | undefined) => { try { navigator.mediaSession.setActionHandler(action, handler || null); } catch { /* unsupported */ } };
    set('play', () => { void this.play(); });
    set('pause', () => this.pause());
    set('nexttrack', handlers.next);
    set('previoustrack', handlers.previous);
    set('seekbackward', () => this.seek(this.currentTimeNow() - 10));
    set('seekforward', () => this.seek(this.currentTimeNow() + 10));
    set('seekto', undefined);
  }

  async load(track: PlaybackTrack, autoplay = true) {
    this.loadId += 1;
    const other = this.active === 'youtube' ? this.relay : this.youtube;
    other.pause();
    this.active = 'youtube';
    if (this.requestedRate !== 1) { this.requestedRate = 1; this.relay.setPlaybackRate(1); }
    await this.youtube.load(track, autoplay);
  }

  play() { return this.current().play(); }
  pause() { this.current().pause(); }
  resume() { return this.play(); }
  togglePlay() { return this.current().togglePlay(); }
  seek(time: number) {
    if (!Number.isFinite(time)) return;
    this.current().seek(Math.max(0, time));
    this.emit('seeked');
  }
  /** True when jumping to [time] is instant (YouTube can jump anywhere; the relay only within what it has). */
  canSeekWithin(time: number) { return this.active === 'youtube' || this.relay.canSeekWithin(time); }
  currentTimeNow() { return this.active === 'youtube' ? this.youtube.currentTimeNow() : this.relay.getAudioElement().currentTime; }
  setVolume(volume: number) { this.youtube.setVolume(volume); this.relay.setVolume(volume); }
  setMuted(muted: boolean) { this.youtube.setMuted(muted); this.relay.setMuted(muted); }
  setPlaybackRate(rate: number) { this.media.playbackRate = rate; }
  destroy() { this.youtube.destroy(); this.relay.destroy(); this.listeners.clear(); this.mediaListeners.clear(); }
}
