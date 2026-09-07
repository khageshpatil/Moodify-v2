import type {
  PlaybackError,
  PlaybackSnapshot,
  PlaybackSourceResolver,
  PlaybackTrack,
} from './playbackTypes';

declare global {
  interface Window {
    YT?: {
      Player: new (
        elementId: string | HTMLElement,
        options: {
          height?: string | number;
          width?: string | number;
          videoId?: string;
          playerVars?: Record<string, unknown>;
          events?: {
            onReady?: (event: { target: YTPlayer }) => void;
            onStateChange?: (event: { data: number; target: YTPlayer }) => void;
            onError?: (event: { data: number; target: YTPlayer }) => void;
          };
        }
      ) => YTPlayer;
      PlayerState: {
        UNSTARTED: number;
        ENDED: number;
        PLAYING: number;
        PAUSED: number;
        BUFFERING: number;
        CUED: number;
      };
    };
    onYouTubeIframeAPIReady?: () => void;
    __moodifyYouTubeEngine?: YouTubePlaybackEngine;
  }
}

export interface YTPlayer {
  loadVideoById(options: { videoId: string; startSeconds?: number } | string): void;
  cueVideoById(options: { videoId: string; startSeconds?: number } | string): void;
  playVideo(): void;
  pauseVideo(): void;
  stopVideo(): void;
  seekTo(seconds: number, allowSeekAhead?: boolean): void;
  setVolume(volume: number): void;
  getVolume(): number;
  mute(): void;
  unMute(): void;
  isMuted(): boolean;
  getPlaybackRate(): number;
  setPlaybackRate(suggestedRate: number): void;
  getCurrentTime(): number;
  getDuration(): number;
  getPlayerState(): number;
  destroy(): void;
}

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

let iframeScriptLoading = false;
let iframeScriptLoaded = false;
const readyCallbacks: Array<() => void> = [];

function loadYouTubeIframeApi(): Promise<void> {
  if (typeof window === 'undefined') return Promise.reject(new Error('Window unavailable'));
  if (window.YT && window.YT.Player) return Promise.resolve();
  if (iframeScriptLoaded) return Promise.resolve();

  return new Promise((resolve) => {
    readyCallbacks.push(resolve);
    if (!iframeScriptLoading) {
      iframeScriptLoading = true;
      const prevCallback = window.onYouTubeIframeAPIReady;
      window.onYouTubeIframeAPIReady = () => {
        iframeScriptLoaded = true;
        iframeScriptLoading = false;
        if (prevCallback) prevCallback();
        readyCallbacks.forEach((cb) => cb());
        readyCallbacks.length = 0;
      };
      const script = document.createElement('script');
      script.src = 'https://www.youtube.com/iframe_api';
      script.async = true;
      document.head.appendChild(script);
    }
  });
}

const YOUTUBE_VIDEO_ID_REGEX = /^[A-Za-z0-9_-]{11}$/;

export class YouTubePlaybackEngine {
  private snapshot: PlaybackSnapshot = { ...initialSnapshot };
  private listeners = new Set<(snapshot: PlaybackSnapshot) => void>();
  private requestId = 0;
  private endedListener: (() => void) | null = null;
  private mediaSessionHandlers: { next?: () => void; previous?: () => void } = {};
  private player: YTPlayer | null = null;
  private isPlayerReady = false;
  private timeUpdateInterval: ReturnType<typeof setInterval> | null = null;
  private containerEl: HTMLDivElement | null = null;
  private playerElementId = 'moodify-yt-player-container';

  constructor(private readonly resolveSource?: PlaybackSourceResolver) {
    if (import.meta.env?.DEV) {
      window.__moodifyYouTubeEngine = this;
    }
    this.ensureContainer();
  }

  private ensureContainer(): HTMLElement {
    if (typeof document === 'undefined') throw new Error('Document unavailable');
    let container = document.getElementById(this.playerElementId) as HTMLDivElement | null;
    if (!container) {
      container = document.createElement('div');
      container.id = this.playerElementId;
      // Position floating mini player card in DOM, fully compliant with YouTube embedded player API & browser autoplay rules
      Object.assign(container.style, {
        position: 'fixed',
        bottom: '88px',
        right: '20px',
        width: '240px',
        height: '135px',
        zIndex: '45',
        borderRadius: '12px',
        overflow: 'hidden',
        boxShadow: '0 12px 30px rgba(0,0,0,0.6)',
        border: '1px solid rgba(255,255,255,0.12)',
        backgroundColor: '#09090b',
        transition: 'transform 0.3s ease, opacity 0.3s ease',
      });
      const inner = document.createElement('div');
      inner.id = 'moodify-yt-player-iframe';
      container.appendChild(inner);
      document.body.appendChild(container);
    }
    this.containerEl = container;
    return document.getElementById('moodify-yt-player-iframe') || container;
  }

  private async getOrInitPlayer(): Promise<YTPlayer> {
    if (this.player && this.isPlayerReady) return this.player;

    await loadYouTubeIframeApi();
    const targetElement = this.ensureContainer();

    return new Promise((resolve, reject) => {
      if (!window.YT) {
        reject(new Error('YouTube IFrame API failed to load'));
        return;
      }

      this.player = new window.YT.Player(targetElement, {
        width: '240',
        height: '135',
        playerVars: {
          autoplay: 1,
          controls: 0,
          disablekb: 1,
          fs: 0,
          rel: 0,
          playsinline: 1,
          enablejsapi: 1,
          origin: window.location.origin,
        },
        events: {
          onReady: (event) => {
            this.isPlayerReady = true;
            event.target.setVolume(this.snapshot.volume);
            if (this.snapshot.muted) event.target.mute();
            resolve(event.target);
          },
          onStateChange: (event) => {
            this.handlePlayerStateChange(event.data);
          },
          onError: (event) => {
            const errorCode = event.data;
            let msg = 'YouTube player error';
            if (errorCode === 2) msg = 'Invalid YouTube video ID';
            else if (errorCode === 5) msg = 'HTML5 player error';
            else if (errorCode === 100) msg = 'Video not found or removed';
            else if (errorCode === 101 || errorCode === 150) msg = 'Playback not allowed in embedded players';
            this.fail({ code: `YT_ERROR_${errorCode}`, message: msg });
          },
        },
      });
    });
  }

  subscribe(listener: (snapshot: PlaybackSnapshot) => void) {
    this.listeners.add(listener);
    listener(this.snapshot);
    return () => this.listeners.delete(listener);
  }

  getSnapshot() {
    return this.snapshot;
  }

  setEndedListener(listener: (() => void) | null) {
    this.endedListener = listener;
  }

  setMediaSessionHandlers(handlers: { next?: () => void; previous?: () => void }) {
    this.mediaSessionHandlers = handlers;
    if (!('mediaSession' in navigator)) return;
    const mediaSession = navigator.mediaSession;
    const set = (action: MediaSessionAction, handler: (() => void) | undefined) => {
      try {
        mediaSession.setActionHandler(action, handler || null);
      } catch {
        /* Unsupported action. */
      }
    };
    set('play', () => { void this.play(); });
    set('pause', () => this.pause());
    set('nexttrack', handlers.next);
    set('previoustrack', handlers.previous);
    set('seekbackward', () => this.seek(this.snapshot.currentTime - 10));
    set('seekforward', () => this.seek(this.snapshot.currentTime + 10));
  }

  async load(track: PlaybackTrack, autoplay = true) {
    const requestId = ++this.requestId;
    this.update({
      status: 'loading',
      currentTrack: track,
      currentTime: 0,
      duration: track.duration || 0,
      buffered: 0,
      error: null,
    });

    let videoId = track.providerId || track.id;
    if (!videoId || !YOUTUBE_VIDEO_ID_REGEX.test(videoId)) {
      if (this.resolveSource) {
        try {
          const controller = new AbortController();
          const source = await this.resolveSource(track, controller.signal, 0);
          // Parse videoId from url if returned or extract from source
          const match = /\/api\/media\/([A-Za-z0-9_-]{11})/.exec(source.url);
          if (match) videoId = match[1];
        } catch (err) {
          if (requestId !== this.requestId) return;
          this.fail({
            code: 'SOURCE_RESOLUTION_FAILED',
            message: err instanceof Error ? err.message : 'Unable to resolve YouTube video ID',
          });
          return;
        }
      }
    }

    if (!videoId || !YOUTUBE_VIDEO_ID_REGEX.test(videoId)) {
      this.fail({ code: 'INVALID_VIDEO_ID', message: `Invalid YouTube video ID: ${videoId || 'none'}` });
      return;
    }

    try {
      const ytPlayer = await this.getOrInitPlayer();
      if (requestId !== this.requestId) return;

      if ('mediaSession' in navigator) {
        navigator.mediaSession.metadata = new MediaMetadata({
          title: track.title,
          artist: track.artist,
          album: track.album || 'Moodify',
          artwork: track.albumArt ? [{ src: track.albumArt }] : [],
        });
      }

      if (autoplay) {
        ytPlayer.loadVideoById({ videoId });
      } else {
        ytPlayer.cueVideoById({ videoId });
        this.update({ status: 'ready' });
      }
    } catch (err) {
      if (requestId !== this.requestId) return;
      this.fail({
        code: 'PLAYER_INIT_FAILED',
        message: err instanceof Error ? err.message : 'YouTube player initialization failed',
      });
    }
  }

  async play() {
    if (this.player && this.isPlayerReady) {
      try {
        this.player.playVideo();
      } catch (err) {
        this.fail({ code: 'PLAY_REJECTED', message: err instanceof Error ? err.message : 'Playback failed' });
      }
    }
  }

  pause() {
    if (this.player && this.isPlayerReady) {
      try {
        this.player.pauseVideo();
      } catch {
        /* Ignore pause errors */
      }
    }
  }

  async resume() {
    return this.play();
  }

  togglePlay() {
    if (this.snapshot.status === 'playing') {
      this.pause();
      return Promise.resolve();
    }
    return this.play();
  }

  seek(time: number) {
    if (this.player && this.isPlayerReady && Number.isFinite(time)) {
      try {
        this.player.seekTo(Math.max(0, time), true);
        this.update({ currentTime: time });
      } catch {
        /* Ignore seek errors */
      }
    }
  }

  setVolume(volume: number) {
    const value = Math.max(0, Math.min(100, volume));
    this.update({ volume: value });
    if (this.player && this.isPlayerReady) {
      try {
        this.player.setVolume(value);
      } catch {
        /* Ignore volume errors */
      }
    }
  }

  setMuted(muted: boolean) {
    this.update({ muted });
    if (this.player && this.isPlayerReady) {
      try {
        if (muted) this.player.mute();
        else this.player.unMute();
      } catch {
        /* Ignore mute errors */
      }
    }
  }

  setPlaybackRate(rate: number) {
    const value = Math.max(0.5, Math.min(2, rate));
    this.update({ playbackRate: value });
    if (this.player && this.isPlayerReady) {
      try {
        this.player.setPlaybackRate(value);
      } catch {
        /* Ignore rate errors */
      }
    }
  }

  destroy() {
    this.requestId++;
    this.stopProgressTimer();
    if (this.player && this.isPlayerReady) {
      try {
        this.player.destroy();
      } catch {
        /* Ignore destroy errors */
      }
    }
    this.player = null;
    this.isPlayerReady = false;
    this.listeners.clear();
  }

  private update(changes: Partial<PlaybackSnapshot>) {
    this.snapshot = { ...this.snapshot, ...changes };
    this.listeners.forEach((listener) => listener(this.snapshot));
  }

  private fail(error: PlaybackError) {
    this.stopProgressTimer();
    this.update({ status: 'failed', error });
  }

  private handlePlayerStateChange(state: number) {
    if (!window.YT) return;
    const { PlayerState } = window.YT;

    if (state === PlayerState.PLAYING) {
      const duration = this.player?.getDuration() || this.snapshot.duration;
      this.update({ status: 'playing', duration: Number.isFinite(duration) ? duration : 0, error: null });
      this.startProgressTimer();
    } else if (state === PlayerState.PAUSED) {
      this.stopProgressTimer();
      if (this.snapshot.status !== 'ended' && this.snapshot.status !== 'failed') {
        this.update({ status: 'paused' });
      }
    } else if (state === PlayerState.ENDED) {
      this.stopProgressTimer();
      const duration = this.snapshot.duration || (this.player?.getDuration() || 0);
      this.update({ status: 'ended', currentTime: duration });
      this.endedListener?.();
    } else if (state === PlayerState.BUFFERING) {
      /* Buffering */
    } else if (state === PlayerState.CUED) {
      this.update({ status: 'ready' });
    }
  }

  private startProgressTimer() {
    this.stopProgressTimer();
    this.timeUpdateInterval = setInterval(() => {
      if (this.player && this.isPlayerReady && this.snapshot.status === 'playing') {
        try {
          const currentTime = this.player.getCurrentTime();
          const duration = this.player.getDuration();
          this.update({
            currentTime: Number.isFinite(currentTime) ? currentTime : this.snapshot.currentTime,
            duration: Number.isFinite(duration) && duration > 0 ? duration : this.snapshot.duration,
          });
        } catch {
          /* Ignore polling errors */
        }
      }
    }, 250);
  }

  private stopProgressTimer() {
    if (this.timeUpdateInterval) {
      clearInterval(this.timeUpdateInterval);
      this.timeUpdateInterval = null;
    }
  }
}
