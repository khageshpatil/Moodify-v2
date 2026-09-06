import { describe, expect, it } from 'vitest';
import type { PlaybackSnapshot, PlaybackTrack } from '@/playback/playbackTypes';
import { ListeningEventCollector } from '../ListeningEventCollector';
import { ListeningEventStore } from '../ListeningEventStore';
import { deriveListeningSignals } from '../listeningSignals';
import type { ListeningEvent, ListeningSession } from '../listeningTypes';

class MemoryStore extends ListeningEventStore {
  events: ListeningEvent[] = [];
  session: ListeningSession | null = null;
  override loadEvents() { return [...this.events]; }
  override append(event: ListeningEvent) { this.events.push(event); return [...this.events]; }
  override loadSession() { return this.session; }
  override saveSession(session: ListeningSession) { this.session = { ...session, tracksPlayed: [...session.tracksPlayed] }; }
}

const track: PlaybackTrack = {
  id: 'track-a', title: 'Track A', artist: 'Artist A', album: 'Album A', duration: 180,
  provider: 'youtube-music', providerId: 'yt-a',
};

const snapshot = (status: PlaybackSnapshot['status'], currentTime: number, duration = 180): PlaybackSnapshot => ({
  status, currentTrack: track, currentTime, duration, buffered: duration, volume: 70, muted: false, playbackRate: 1, error: null,
});

describe('ListeningEventCollector', () => {
  it('records actual play, pause, resume, and completion lifecycle events', () => {
    let now = 1000;
    const collector = new ListeningEventCollector({ store: new MemoryStore(), now: () => now });
    collector.observe(snapshot('playing', 0));
    now += 20_000;
    collector.observe(snapshot('paused', 20));
    now += 1_000;
    collector.observe(snapshot('playing', 20));
    now += 180_000;
    collector.observe(snapshot('ended', 180));

    expect(collector.getEvents().map((event) => event.type)).toEqual(['play_started', 'pause', 'resume', 'play_completed']);
    expect(collector.getEvents().at(-1)?.completionRatio).toBe(1);
    expect(collector.getSession().tracksPlayed).toEqual(['youtube-music:yt-a']);
  });

  it('marks a short skip as early and includes listening context', () => {
    let now = 1000;
    const collector = new ListeningEventCollector({ store: new MemoryStore(), now: () => now });
    collector.observe(snapshot('playing', 0));
    now += 3_000;
    collector.observe(snapshot('paused', 3));
    collector.recordSkip('next');

    const event = collector.getEvents().find((entry) => entry.type === 'skip');
    expect(event?.earlySkip).toBe(true);
    expect(event?.positionSeconds).toBe(3);
    expect(event?.durationSeconds).toBe(180);
    expect(event?.completionRatio).toBeCloseTo(3 / 180);
    expect(event?.source).toBe('next');
  });

  it('counts intentional replay but not ordinary engine lifecycle events', () => {
    const collector = new ListeningEventCollector({ store: new MemoryStore() });
    collector.observe(snapshot('playing', 4));
    collector.observe(snapshot('loading', 0));
    collector.observe(snapshot('playing', 0));
    expect(collector.getEvents().filter((event) => event.type === 'replay')).toHaveLength(0);

    collector.recordReplay(track);
    collector.observe(snapshot('loading', 0));
    collector.observe(snapshot('playing', 0));
    expect(collector.getEvents().filter((event) => event.type === 'replay')).toHaveLength(1);
  });

  it('persists events and derives deterministic track and artist signals', () => {
    const store = new MemoryStore();
    const collector = new ListeningEventCollector({ store });
    collector.observe(snapshot('playing', 0));
    collector.observe(snapshot('ended', 180));
    collector.recordFavorite(track, true);
    const restored = new ListeningEventCollector({ store });
    const signals = deriveListeningSignals(restored.getEvents(), [restored.getSession()]);
    const trackSignals = signals.tracks['youtube-music:yt-a'];
    expect(restored.getEvents()).toHaveLength(3);
    expect(trackSignals.playCount).toBe(1);
    expect(trackSignals.completionCount).toBe(1);
    expect(trackSignals.favoriteCount).toBe(1);
    expect(signals.artists['Artist A'].artistCompletionRate).toBe(1);
  });

  it('attaches a pending discovery context only when the track starts playing', () => {
    const collector = new ListeningEventCollector({ store: new MemoryStore() });
    collector.recordPlayIntent(track, { type: 'search', id: 'late-night query', position: 2 });
    collector.observe(snapshot('loading', 0));
    expect(collector.getEvents()).toHaveLength(0);

    collector.observe(snapshot('playing', 0));
    expect(collector.getEvents()[0].discoveryContext).toEqual({ type: 'search', id: 'late-night query', position: 2 });
  });

  it('does not carry discovery context across a failed load', () => {
    const collector = new ListeningEventCollector({ store: new MemoryStore() });
    collector.recordPlayIntent(track, { type: 'related', sourceTrackKey: 'youtube-music:source-track' });
    collector.observe(snapshot('failed', 0));
    collector.observe(snapshot('playing', 0));
    expect(collector.getEvents()[0].discoveryContext).toBeUndefined();
  });

  it('ignores malformed discovery context instead of inferring or storing it', () => {
    const collector = new ListeningEventCollector({ store: new MemoryStore() });
    collector.recordPlayIntent(track, { type: 'unknown' } as unknown as import('../listeningTypes').DiscoveryContext);
    collector.observe(snapshot('playing', 0));
    expect(collector.getEvents()[0].discoveryContext).toBeUndefined();
  });

  it('keeps repeated intents for the same track in request order', () => {
    const collector = new ListeningEventCollector({ store: new MemoryStore() });
    collector.recordPlayIntent(track, { type: 'search', id: 'first' });
    collector.recordPlayIntent(track, { type: 'related', sourceTrackKey: 'youtube-music:second' });
    collector.observe(snapshot('playing', 0));
    expect(collector.getEvents()[0].discoveryContext).toEqual({ type: 'search', id: 'first' });
  });

  it('accepts album and artist discovery contexts', () => {
    const collector = new ListeningEventCollector({ store: new MemoryStore() });
    collector.recordPlayIntent(track, { type: 'album', id: 'MPRalbum', position: 1 });
    collector.observe(snapshot('playing', 0));
    expect(collector.getEvents()[0].discoveryContext).toEqual({ type: 'album', id: 'MPRalbum', position: 1 });
  });
});
