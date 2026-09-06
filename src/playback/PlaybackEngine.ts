import type {
  PlaybackError,
  PlaybackSnapshot,
  PlaybackSourceResolver,
  PlaybackStatus,
  PlaybackTrack,
} from './playbackTypes';

const initialSnapshot: PlaybackSnapshot = {
  status: 'idle',
  currentTrack: null,
  currentTime: 0,
  duration: 0,
  buffered: 0,
  volume: 70,
  muted: false,
  playbackRate: 1,
  error: null,
};

export class PlaybackEngine {
  readonly audio: HTMLAudioElement;
  private snapshot: PlaybackSnapshot = { ...initialSnapshot };
  private listeners = new Set<(snapshot: PlaybackSnapshot) => void>();
  private requestId = 0;
  private abortController: AbortController | null = null;
  private endedListener: (() => void) | null = null;
  private mediaSessionHandlers: { next?: () => void; previous?: () => void } = {};
  private recoveryAttemptedTrackId: string | null = null;

  constructor(private readonly resolveSource: PlaybackSourceResolver) {
    this.audio = document.createElement('audio');
    this.audio.preload = 'auto';
    this.audio.crossOrigin = 'anonymous';
    if (import.meta.env?.DEV) (window as Window & { __moodifyPlaybackEngine?: PlaybackEngine }).__moodifyPlaybackEngine = this;
    this.audio.addEventListener('loadedmetadata', this.handleLoadedMetadata);
    this.audio.addEventListener('canplay', this.handleCanPlay);
    this.audio.addEventListener('playing', this.handlePlaying);
    this.audio.addEventListener('pause', this.handlePause);
    this.audio.addEventListener('waiting', this.handleWaiting);
    this.audio.addEventListener('stalled', this.handleStalled);
    this.audio.addEventListener('timeupdate', this.handleTimeUpdate);
    this.audio.addEventListener('progress', this.handleProgress);
    this.audio.addEventListener('ended', this.handleEnded);
    this.audio.addEventListener('error', this.handleError);
  }

  subscribe(listener: (snapshot: PlaybackSnapshot) => void) {
    this.listeners.add(listener);
    listener(this.snapshot);
    return () => this.listeners.delete(listener);
  }

  getSnapshot() { return this.snapshot; }
  getAudioElement() { return this.audio; }
  setEndedListener(listener: (() => void) | null) { this.endedListener = listener; }
  setMediaSessionHandlers(handlers: { next?: () => void; previous?: () => void }) {
    this.mediaSessionHandlers = handlers;
    if (!('mediaSession' in navigator)) return;
    const mediaSession = navigator.mediaSession;
    const set = (action: MediaSessionAction, handler: (() => void) | undefined) => { try { mediaSession.setActionHandler(action, handler || null); } catch { /* Unsupported action. */ } };
    set('play', () => { void this.play(); });
    set('pause', () => this.pause());
    set('nexttrack', handlers.next);
    set('previoustrack', handlers.previous);
    set('seekbackward', () => this.seek(this.audio.currentTime - 10));
    set('seekforward', () => this.seek(this.audio.currentTime + 10));
  }

  async load(track: PlaybackTrack, autoplay = true) {
    const requestId = ++this.requestId;
    this.abortController?.abort();
    const controller = new AbortController();
    this.abortController = controller;
    this.update({ status: 'loading', currentTrack: track, currentTime: 0, duration: 0, buffered: 0, error: null });
    this.audio.pause();
    this.audio.removeAttribute('src');
    this.audio.load();

    let source;
    try {
      source = await this.resolveSource(track, controller.signal, 0);
    } catch (firstError) {
      if (controller.signal.aborted || requestId !== this.requestId) return;
      try {
        source = await this.resolveSource(track, controller.signal, 1);
      } catch (secondError) {
        if (controller.signal.aborted || requestId !== this.requestId) return;
        this.fail({ code: 'SOURCE_RESOLUTION_FAILED', message: secondError instanceof Error ? secondError.message : 'Unable to resolve playback source', cause: firstError });
        return;
      }
    }

    if (controller.signal.aborted || requestId !== this.requestId) return;
    this.audio.src = source.url;
    this.audio.load();
    if ('mediaSession' in navigator) {
      navigator.mediaSession.metadata = new MediaMetadata({ title: track.title, artist: track.artist, album: track.album || 'Moodify', artwork: track.albumArt ? [{ src: track.albumArt }] : [] });
    }
    if (!autoplay) this.update({ status: 'ready' });
    if (autoplay) {
      try {
        await this.audio.play();
      } catch (error) {
        if (controller.signal.aborted || requestId !== this.requestId) return;
        this.fail({ code: 'PLAY_REJECTED', message: error instanceof Error ? error.message : 'The browser rejected playback', cause: error });
      }
    }
  }

  async play() {
    try { await this.audio.play(); }
    catch (error) { this.fail({ code: 'PLAY_REJECTED', message: error instanceof Error ? error.message : 'The browser rejected playback', cause: error }); }
  }
  pause() { this.audio.pause(); }
  async resume() { return this.play(); }
  togglePlay() { return this.audio.paused ? this.play() : Promise.resolve(this.pause()); }
  seek(time: number) {
    if (Number.isFinite(time) && this.audio.readyState > 0) this.audio.currentTime = Math.max(0, Math.min(time, this.audio.duration || time));
  }
  setVolume(volume: number) { const value = Math.max(0, Math.min(100, volume)); this.audio.volume = value / 100; this.update({ volume: value }); }
  setMuted(muted: boolean) { this.audio.muted = muted; this.update({ muted }); }
  setPlaybackRate(rate: number) { const value = Math.max(0.5, Math.min(2, rate)); this.audio.playbackRate = value; this.update({ playbackRate: value }); }
  destroy() {
    this.requestId++;
    this.abortController?.abort();
    this.audio.pause();
    this.audio.removeAttribute('src');
    this.audio.load();
    this.listeners.clear();
  }

  private update(changes: Partial<PlaybackSnapshot>) { this.snapshot = { ...this.snapshot, ...changes }; this.listeners.forEach((listener) => listener(this.snapshot)); }
  private fail(error: PlaybackError) { this.update({ status: 'failed', error }); }
  private handleLoadedMetadata = () => this.update({ duration: Number.isFinite(this.audio.duration) ? this.audio.duration : 0 });
  private handleCanPlay = () => { if (this.snapshot.status === 'loading') this.update({ status: 'ready' }); };
  private handlePlaying = () => { this.recoveryAttemptedTrackId = null; this.update({ status: 'playing', error: null }); };
  private handlePause = () => { if (this.snapshot.status !== 'ended' && this.snapshot.status !== 'failed' && this.snapshot.status !== 'loading') this.update({ status: 'paused' }); };
  private handleWaiting = () => { /* Buffering is not a terminal failure. */ };
  private handleStalled = () => { /* Buffering is not a terminal failure. */ };
  private handleTimeUpdate = () => this.update({ currentTime: this.audio.currentTime });
  private handleProgress = () => { try { this.update({ buffered: this.audio.buffered.length ? this.audio.buffered.end(this.audio.buffered.length - 1) : 0 }); } catch { /* Media can change while ranges are updated. */ } };
  private handleEnded = () => { this.update({ status: 'ended', currentTime: this.audio.duration || this.audio.currentTime }); this.endedListener?.(); };
  private handleError = () => {
    if (this.snapshot.status === 'loading') return;
    if (this.snapshot.currentTrack && this.recoveryAttemptedTrackId !== this.snapshot.currentTrack.id) {
      this.recoveryAttemptedTrackId = this.snapshot.currentTrack.id;
      void this.load(this.snapshot.currentTrack, true);
      return;
    }
    this.fail({ code: 'MEDIA_ERROR', message: this.audio.error?.message || 'The media element could not play this source' });
  };
}

let canonicalEngine: PlaybackEngine | null = null;
export const getCanonicalPlaybackEngine = (resolveSource?: PlaybackSourceResolver) => {
  if (!canonicalEngine) {
    if (!resolveSource) throw new Error('The canonical playback engine requires a source resolver on first use');
    canonicalEngine = new PlaybackEngine(resolveSource);
  }
  return canonicalEngine;
};

export const resetCanonicalPlaybackEngine = () => { canonicalEngine?.destroy(); canonicalEngine = null; };
