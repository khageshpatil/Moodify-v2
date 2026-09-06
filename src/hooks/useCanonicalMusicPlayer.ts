import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Track } from '@/data/mockMusic';
import { getCanonicalPlaybackEngine } from '@/playback/PlaybackEngine';
import type { PlaybackSnapshot, PlaybackTrack } from '@/playback/playbackTypes';
import { searchMoodifyTracks } from '@/services/moodifyMusicApi';
import { toast } from '@/hooks/use-toast';
import { getMoodPlaylist } from '@/data/moodPlaylists';
import { ListeningEventCollector, toPlaybackTrack } from '@/listening/ListeningEventCollector';
import { deriveListeningSignals } from '@/listening/listeningSignals';
import type { DiscoveryContext, ListeningEvent } from '@/listening/listeningTypes';
import { discoveryContextForPlaylist, discoveryContextForVibe } from '@/listening/discoveryAttribution';
import { deriveTasteSnapshot, tasteStore } from '@/taste';
import { trackDnaStore } from '@/trackDna';
import {
  buildRecommendationContextFromEvents,
  generateRecommendations,
  trackKeyFromTrack,
  type AutoplayDiagnostics,
  type RecommendationBatch,
  type RecommendationResult,
} from '@/recommendation';
import {
  createRecommendationBatch,
  markBatchConsumed,
  markBatchFailed,
  markBatchInjected,
  remainingQueue,
  selectInjectableRecommendations,
  shouldInjectRecommendations,
  shouldPrefetchRecommendations,
  shouldReplaceBatch,
  stampRecommendationContexts,
  userQueueSignature,
} from '@/recommendation/recommendationAutoplay';
import { deriveRecommendationOutcomes, recommendationEvalStore } from '@/recommendation/recommendationEval';
import { fetchDiscoveryGraph } from '@/services/moodifyMusicApi';
import type { DiscoveryGraphSnapshot } from '@/recommendation/recommendationTypes';
import { assembleUserState, localUserStateStore } from '@/userState';
import { deriveHomeModel } from '@/home';
import { appendFinishedListen, reviveListenHistory } from '@/listening/listenHistory';
import { buildOpenThread, type OpenListeningThread } from '@/listening/openThread';

export interface Playlist { id: string; name: string; tracks: Track[]; createdAt: Date; }
export interface HistoryItem { track: Track; playedAt: Date; duration: number; }
type State = { currentTrack: Track | null; isPlaying: boolean; currentTime: number; duration: number; volume: number; queue: Track[]; currentTrackIndex: number; playlists: Playlist[]; favorites: Track[]; history: HistoryItem[]; recentlyPlayed: Track[]; shuffle: boolean; repeat: 'off' | 'all' | 'one'; originalQueue: Track[]; playbackStatus: PlaybackSnapshot['status']; playbackError: PlaybackSnapshot['error'] };
const keys = { playlists: 'moodify_playlists', favorites: 'moodify_favorites', history: 'moodify_history', recent: 'moodify_recently_played', volume: 'moodify_volume' };
const read = <T,>(key: string, fallback: T): T => { try { const value = localStorage.getItem(key); return value ? JSON.parse(value) : fallback; } catch { return fallback; } };
const write = (key: string, value: unknown) => { try { localStorage.setItem(key, JSON.stringify(value)); } catch { /* Storage is optional. */ } };
const shuffle = <T,>(items: T[]) => { const result = [...items]; for (let i = result.length - 1; i > 0; i -= 1) { const j = Math.floor(Math.random() * (i + 1)); [result[i], result[j]] = [result[j], result[i]]; } return result; };
const playbackTrack = (track: Track): PlaybackTrack => ({ id: track.id, title: track.title, artist: track.artist, album: track.album, albumArt: track.albumArt, duration: track.duration, provider: (track as Track & { provider?: string }).provider, providerId: (track as Track & { providerId?: string }).providerId });

export const useCanonicalMusicPlayer = () => {
  const [state, setState] = useState<State>({ currentTrack: null, isPlaying: false, currentTime: 0, duration: 0, volume: read(keys.volume, 70), queue: [], currentTrackIndex: -1, playlists: read(keys.playlists, []), favorites: read(keys.favorites, []), history: reviveListenHistory(read(keys.history, [])), recentlyPlayed: read(keys.recent, []), shuffle: false, repeat: 'off', originalQueue: [], playbackStatus: 'idle', playbackError: null });
  const stateRef = useRef(state); stateRef.current = state;
  const startedAt = useRef(0);
  const queueDiscoveryContexts = useRef(new Map<string, DiscoveryContext>());
  const collectorRef = useRef<ListeningEventCollector | null>(null);
  if (!collectorRef.current) collectorRef.current = new ListeningEventCollector();
  const collector = collectorRef.current;
  const [listeningEvents, setListeningEvents] = useState<ListeningEvent[]>(() => collector.getEvents());
  const [discoveryGraph, setDiscoveryGraph] = useState<DiscoveryGraphSnapshot>({ nodes: [], edges: [] });
  const [autoplayDiagnostics, setAutoplayDiagnostics] = useState<AutoplayDiagnostics>({ remaining: 0, prefetch: false, injected: false, reason: 'idle', injectedKeys: [], failedKeys: [] });
  const recommendationBatchRef = useRef<RecommendationBatch | null>(null);
  const recommendationResultRef = useRef<RecommendationResult | null>(null);
  const recentRecommendedRef = useRef<string[]>([]);
  const skippingFailedRef = useRef(false);
  const mixOwnedRef = useRef(false);
  const [openThread, setOpenThread] = useState<OpenListeningThread | null>(() => localUserStateStore.loadEnvelope().openThread);
  const resolveSource = useCallback(async (track: PlaybackTrack, signal: AbortSignal) => { const base = (import.meta.env.VITE_MOODIFY_SERVER_URL || 'http://127.0.0.1:8787').replace(/\/$/, ''); let id = track.provider === 'youtube-music' ? track.providerId || track.id : undefined; if (!id) { const response = await fetch(`${base}/api/search?q=${encodeURIComponent(`${track.title} ${track.artist}`)}`, { signal }); if (!response.ok) throw new Error(`Playback search failed (${response.status})`); const data = await response.json() as { tracks?: Array<{ id?: string }> }; id = data.tracks?.[0]?.id; } if (!id) throw new Error('No YouTube Music source matched this track'); return { url: `${base}/api/media/${encodeURIComponent(id)}` }; }, []);
  const engineRef = useRef<ReturnType<typeof getCanonicalPlaybackEngine> | null>(null); if (!engineRef.current) engineRef.current = getCanonicalPlaybackEngine(resolveSource); const engine = engineRef.current;
  const addRecent = useCallback((track: Track) => setState((current) => ({ ...current, recentlyPlayed: [track, ...current.recentlyPlayed.filter((item) => item.id !== track.id)].slice(0, 20) })), []);
  const load = useCallback((track: Track) => { startedAt.current = Date.now(); void engine.load(playbackTrack(track)); }, [engine]);
  const playTrack = useCallback((track: Track, tracks: Track[] = [track], discoveryContext?: DiscoveryContext) => {
    const current = stateRef.current.currentTrack;
    if (current && current.id === track.id) collector.recordReplay(toPlaybackTrack(track));
    else if (current) collector.recordSkip('track_selection');
    mixOwnedRef.current = discoveryContext?.type === 'recommendation';
    queueDiscoveryContexts.current.clear();
    recommendationBatchRef.current = null;
    if (discoveryContext) tracks.forEach((item, index) => queueDiscoveryContexts.current.set(`${item.provider || 'unknown'}:${item.providerId || item.id}`, { ...discoveryContext, ...(discoveryContext.position !== undefined || tracks.length > 1 ? { position: index } : {}) }));
    collector.recordPlayIntent(toPlaybackTrack(track), discoveryContext);
    const queue = stateRef.current.shuffle ? [track, ...shuffle(tracks.filter((item) => item.id !== track.id))] : tracks;
    setState((value) => ({ ...value, currentTrack: track, queue, originalQueue: tracks, currentTrackIndex: Math.max(0, queue.findIndex((item) => item.id === track.id)), currentTime: 0, playbackError: null }));
    setAutoplayDiagnostics({ remaining: Math.max(0, queue.length - 1), prefetch: false, injected: false, reason: 'user_queue_replaced', injectedKeys: [], failedKeys: [] });
    addRecent(track);
    load(track);
  }, [addRecent, collector, load]);
  const playPlaylist = useCallback((tracksOrPlaylist: Track[] | Playlist, index = 0, discoveryContext?: DiscoveryContext) => { const tracks: Track[] = Array.isArray(tracksOrPlaylist) ? tracksOrPlaylist : tracksOrPlaylist.tracks; const playlist = Array.isArray(tracksOrPlaylist) ? undefined : tracksOrPlaylist; if (tracks[index]) playTrack(tracks[index], tracks, discoveryContext || (playlist ? discoveryContextForPlaylist(playlist.id, index) : undefined)); }, [playTrack]);
  const playFromQueueIndex = useCallback((index: number, queue: Track[], skipCurrent: boolean) => {
    const track = queue[index];
    if (!track) return false;
    if (skipCurrent && engine.getSnapshot().status !== 'ended') collector.recordSkip('next');
    const context = queueDiscoveryContexts.current.get(`${track.provider || 'unknown'}:${track.providerId || track.id}`);
    if (context?.type === 'recommendation') mixOwnedRef.current = true;
    collector.recordPlayIntent(toPlaybackTrack(track), context);
    const batch = recommendationBatchRef.current;
    const key = trackKeyFromTrack(track);
    if (batch?.injectedKeys.includes(key)) markBatchConsumed(batch, key);
    setState((value) => ({ ...value, currentTrack: track, queue, currentTrackIndex: index, currentTime: 0, playbackError: null }));
    addRecent(track);
    load(track);
    return true;
  }, [addRecent, collector, engine, load]);

  const injectRecommendations = useCallback(() => {
    const current = stateRef.current;
    const result = recommendationResultRef.current;
    const signature = userQueueSignature(current.originalQueue);
    let batch = recommendationBatchRef.current;
    if ((!batch || shouldReplaceBatch(batch, signature)) && result?.candidates.length) {
      batch = createRecommendationBatch(result.candidates, result.strategy, signature, result.generatedAt);
      recommendationBatchRef.current = batch;
    }
    if (!batch) {
      setAutoplayDiagnostics({ remaining: 0, prefetch: false, injected: false, reason: 'no_recommendation_batch', injectedKeys: [], failedKeys: [] });
      return [] as Track[];
    }
    const blocked = new Set([
      ...current.queue.map((track) => trackKeyFromTrack(track)),
      ...current.recentlyPlayed.map((track) => trackKeyFromTrack(track)),
      ...recentRecommendedRef.current,
    ]);
    const selected = selectInjectableRecommendations(batch, blocked);
    if (!selected.length) {
      setAutoplayDiagnostics({ remaining: 0, prefetch: true, injected: false, reason: 'no_playable_candidates', batchId: batch.id, injectedKeys: [], failedKeys: [...batch.failedKeys] });
      return [] as Track[];
    }
    mixOwnedRef.current = true;
    const sourceTrackKey = current.currentTrack ? trackKeyFromTrack(current.currentTrack) : undefined;
    const stamped = stampRecommendationContexts(batch, selected, sourceTrackKey);
    stamped.forEach((entry) => {
      queueDiscoveryContexts.current.set(trackKeyFromTrack(entry.track), entry.discoveryContext);
      recommendationEvalStore.append({
        id: `shown-${batch!.id}-${trackKeyFromTrack(entry.track)}`,
        type: 'shown',
        trackKey: trackKeyFromTrack(entry.track),
        batchId: batch!.id,
        timestamp: Date.now(),
        strategy: batch!.strategy,
      });
    });
    const keys = stamped.map((entry) => trackKeyFromTrack(entry.track));
    markBatchInjected(batch, keys);
    recentRecommendedRef.current = [...keys, ...recentRecommendedRef.current].slice(0, 40);
    setAutoplayDiagnostics({ remaining: 0, prefetch: true, injected: true, reason: 'queue_exhausted', batchId: batch.id, injectedKeys: keys, failedKeys: [...batch.failedKeys] });
    return stamped.map((entry) => entry.track);
  }, []);

  const next = useCallback(() => {
    const current = stateRef.current;
    let index = current.currentTrackIndex + 1;
    let queue = current.queue;
    if (index >= queue.length && current.repeat === 'all') index = 0;
    if (index >= queue.length && shouldInjectRecommendations(0, current.repeat)) {
      const injected = injectRecommendations();
      if (injected.length) {
        queue = [...queue, ...injected];
        index = current.currentTrackIndex + 1;
      }
    }
    if (index < 0 || index >= queue.length) {
      engine.pause();
      setAutoplayDiagnostics((value) => ({ ...value, remaining: 0, reason: value.injected ? value.reason : 'queue_exhausted_no_candidates' }));
      return;
    }
    playFromQueueIndex(index, queue, engine.getSnapshot().status !== 'ended');
  }, [engine, injectRecommendations, playFromQueueIndex]);
  const previous = useCallback(() => { const current = stateRef.current; if (current.currentTime > 3) { const active = engine.getSnapshot(); collector.recordSeek(active.currentTrack, current.currentTime, 0, current.duration, 'previous'); engine.seek(0); return; } const index = current.currentTrackIndex > 0 ? current.currentTrackIndex - 1 : current.repeat === 'all' ? current.queue.length - 1 : -1; if (index < 0) { engine.seek(0); return; } if (engine.getSnapshot().status !== 'ended') collector.recordSkip('previous'); const track = current.queue[index]; const context = queueDiscoveryContexts.current.get(`${track.provider || 'unknown'}:${track.providerId || track.id}`); collector.recordPlayIntent(toPlaybackTrack(track), context); setState((value) => ({ ...value, currentTrack: track, currentTrackIndex: index, currentTime: 0 })); addRecent(track); load(track); }, [addRecent, collector, engine, load]);
  useEffect(() => { const unsubscribe = engine.subscribe((snapshot) => { collector.observe(snapshot); setState((current) => ({ ...current, currentTime: snapshot.currentTime, duration: snapshot.duration || current.duration, isPlaying: snapshot.status === 'playing', playbackStatus: snapshot.status, playbackError: snapshot.error })); }); engine.setVolume(stateRef.current.volume); engine.setMediaSessionHandlers({ next, previous }); engine.setEndedListener(() => { const current = stateRef.current; if (current.currentTrack) setState((value) => ({ ...value, history: appendFinishedListen(value.history, current.currentTrack!, Date.now() - startedAt.current) })); if (current.repeat === 'one' && current.currentTrack) { const track = current.currentTrack; const context = queueDiscoveryContexts.current.get(`${track.provider || 'unknown'}:${track.providerId || track.id}`); collector.recordPlayIntent(toPlaybackTrack(track), context); load(track); } else next(); }); return () => { unsubscribe(); engine.setEndedListener(null); }; }, [collector, engine, load, next, previous]);
  useEffect(() => collector.subscribe(setListeningEvents), [collector]);
  useEffect(() => {
    const controller = new AbortController();
    fetchDiscoveryGraph(controller.signal)
      .then(setDiscoveryGraph)
      .catch(() => { /* Graph is optional for playback; recommendations degrade gracefully. */ });
    return () => controller.abort();
  }, []);
  useEffect(() => { const timers = [window.setTimeout(() => write(keys.playlists, state.playlists), 500), window.setTimeout(() => write(keys.favorites, state.favorites), 500), window.setTimeout(() => write(keys.history, state.history), 1000), window.setTimeout(() => write(keys.recent, state.recentlyPlayed), 500), window.setTimeout(() => write(keys.volume, state.volume), 300)]; return () => timers.forEach(window.clearTimeout); }, [state.playlists, state.favorites, state.history, state.recentlyPlayed, state.volume]);
  const loadMoodPlaylist = useCallback(async (mood: string, discoveryContext?: DiscoveryContext) => { const curated = getMoodPlaylist(mood); const tracks = curated.length ? curated : await searchMoodifyTracks(`${mood} music`, 15); if (!tracks.length) { toast({ title: 'No Music Found', variant: 'destructive' }); return; } const resolvedTracks = curated.length ? await Promise.all(tracks.map(async (track) => { try { const found = await searchMoodifyTracks(`${track.title} ${track.artist}`, 1); return found[0] ? { ...track, id: found[0].id, provider: found[0].provider, providerId: found[0].providerId, albumArt: found[0].albumArt || track.albumArt, duration: found[0].duration || track.duration } : track; } catch { return track; } })) : tracks; playTrack(resolvedTracks[0], resolvedTracks, discoveryContext || discoveryContextForVibe(mood, 0)); }, [playTrack]);
  const toggleFavorite = useCallback((track: Track) => setState((current) => { const favorite = current.favorites.some((item) => item.id === track.id); collector.recordFavorite(toPlaybackTrack(track), !favorite); return { ...current, favorites: favorite ? current.favorites.filter((item) => item.id !== track.id) : [...current.favorites, track] }; }), [collector]);
  const createPlaylist = useCallback((name: string, tracks: Track[] = []) => setState((current) => ({ ...current, playlists: [...current.playlists, { id: Date.now().toString(), name, tracks, createdAt: new Date() }] })), []);
  const importPlaylist = useCallback((file: File) => { const reader = new FileReader(); reader.onload = () => { try { const value = JSON.parse(String(reader.result)); createPlaylist(value.name, value.tracks || []); } catch { toast({ title: 'Import Failed', variant: 'destructive' }); } }; reader.readAsText(file); }, [createPlaylist]);
  const togglePlayPause = useCallback(() => { void engine.togglePlay(); }, [engine]);
  const seekTo = useCallback((time: number) => { const snapshot = engine.getSnapshot(); collector.recordSeek(snapshot.currentTrack, snapshot.currentTime, time, snapshot.duration, 'user'); engine.seek(time); }, [collector, engine]);
  const addToQueue = useCallback((track: Track) => { collector.recordQueueAdded(toPlaybackTrack(track)); setState((current) => ({ ...current, queue: [...current.queue, track] })); }, [collector]);
  const removeFromQueue = useCallback((index: number) => { const track = stateRef.current.queue[index]; if (track) collector.recordQueueRemoved(toPlaybackTrack(track)); setState((current) => ({ ...current, queue: current.queue.filter((_, itemIndex) => itemIndex !== index) })); }, [collector]);
  const listeningSession = collector.getSession();
  const listeningSignals = useMemo(() => deriveListeningSignals(listeningEvents, [listeningSession]), [listeningEvents, listeningSession]);
  const tasteSnapshot = useMemo(() => {
    const snapshot = deriveTasteSnapshot(listeningEvents, {
      now: Date.now(),
      currentSessionId: listeningSession.sessionId || undefined,
      trackDnaByKey: trackDnaStore.load(),
    });
    tasteStore.save(snapshot);
    return snapshot;
  }, [listeningEvents, listeningSession.sessionId]);
  const recommendationResult = useMemo<RecommendationResult | null>(() => {
    const now = Date.now();
    return generateRecommendations({
      taste: tasteSnapshot,
      graph: discoveryGraph,
      trackDnaByKey: trackDnaStore.load(),
      context: buildRecommendationContextFromEvents(listeningEvents, {
        now,
        mode: 'continue-listening',
        currentTrack: state.currentTrack,
        sessionId: listeningSession.sessionId || undefined,
        queueTrackKeys: state.queue.map((track) => trackKeyFromTrack(track)),
        recentRecommendedKeys: recentRecommendedRef.current,
        limit: 20,
      }),
    });
  }, [tasteSnapshot, discoveryGraph, listeningEvents, listeningSession.sessionId, state.currentTrack, state.queue]);
  recommendationResultRef.current = recommendationResult;
  const evidenceClock = listeningEvents[listeningEvents.length - 1]?.timestamp ?? 0;
  const recommendationEvaluation = useMemo(() => {
    const byId = new Map<string, ReturnType<typeof deriveRecommendationOutcomes>[number]>();
    [...recommendationEvalStore.load(), ...deriveRecommendationOutcomes(listeningEvents)]
      .sort((a, b) => a.timestamp - b.timestamp || a.id.localeCompare(b.id))
      .forEach((event) => byId.set(event.id, event));
    return [...byId.values()].sort((a, b) => a.timestamp - b.timestamp || a.id.localeCompare(b.id));
  }, [listeningEvents]);
  const discoverySurfaces = useMemo(
    () => [...new Set(listeningEvents.map((event) => event.discoveryContext?.type).filter((value): value is NonNullable<typeof value> => Boolean(value)))],
    [listeningEvents],
  );
  const userState = useMemo(() => assembleUserState({
    now: evidenceClock,
    events: listeningEvents,
    session: listeningSession,
    playlists: state.playlists,
    favorites: state.favorites,
    history: state.history,
    recentlyPlayed: state.recentlyPlayed,
    graphNodeCount: discoveryGraph.nodes.length,
    graphEdgeCount: discoveryGraph.edges.length,
    discoverySurfaces,
    taste: tasteSnapshot,
    recommendationResult,
    recommendationBatch: recommendationBatchRef.current,
    evaluation: recommendationEvaluation,
    envelope: localUserStateStore.loadEnvelope(),
    volume: state.volume,
    shuffle: state.shuffle,
    repeat: state.repeat,
  }), [evidenceClock, listeningEvents, listeningSession, state.playlists, state.favorites, state.history, state.recentlyPlayed, discoveryGraph.nodes.length, discoveryGraph.edges.length, discoverySurfaces, tasteSnapshot, recommendationResult, recommendationEvaluation, state.volume, state.shuffle, state.repeat]);
  const homeModel = useMemo(() => deriveHomeModel({
    now: evidenceClock,
    events: listeningEvents,
    taste: tasteSnapshot,
    recommendations: recommendationResult,
    graph: discoveryGraph,
    library: {
      playlists: state.playlists,
      favorites: state.favorites,
      history: state.history,
      recentlyPlayed: state.recentlyPlayed,
    },
    openThread,
  }), [evidenceClock, listeningEvents, tasteSnapshot, recommendationResult, discoveryGraph, state.playlists, state.favorites, state.history, state.recentlyPlayed, openThread]);

  useEffect(() => {
    if (!state.currentTrack) return;
    const trackOrigin = queueDiscoveryContexts.current.get(`${state.currentTrack.provider || 'unknown'}:${state.currentTrack.providerId || state.currentTrack.id}`)
      || queueDiscoveryContexts.current.get(trackKeyFromTrack(state.currentTrack));
    const mixOwned = mixOwnedRef.current || trackOrigin?.type === 'recommendation';
    const origin = mixOwned
      ? (trackOrigin?.type === 'recommendation' ? trackOrigin : { type: 'recommendation' as const, id: 'moodify-mix' })
      : (trackOrigin || { type: 'direct' as const, id: 'open-thread' });
    const thread = buildOpenThread({
      track: state.currentTrack,
      queue: state.queue,
      currentIndex: state.currentTrackIndex,
      origin,
      owner: mixOwned ? 'mix' : 'user',
    });
    localUserStateStore.saveOpenThread(thread);
    setOpenThread(thread);
  }, [state.currentTrack, state.queue, state.currentTrackIndex]);

  useEffect(() => {
    const remaining = remainingQueue(state.currentTrackIndex, state.queue.length);
    const prefetch = shouldPrefetchRecommendations(remaining, state.repeat);
    const signature = userQueueSignature(state.originalQueue);
    const result = recommendationResultRef.current;
    if (prefetch && result?.candidates.length && shouldReplaceBatch(recommendationBatchRef.current, signature)) {
      recommendationBatchRef.current = createRecommendationBatch(result.candidates, result.strategy, signature, result.generatedAt);
      setAutoplayDiagnostics({
        remaining,
        prefetch: true,
        injected: false,
        reason: 'prefetch_threshold',
        batchId: recommendationBatchRef.current.id,
        injectedKeys: [],
        failedKeys: [],
      });
      return;
    }
    setAutoplayDiagnostics((value) => ({
      ...value,
      remaining,
      prefetch,
      reason: prefetch ? value.reason : remaining > 1 ? 'queue_has_enough_tracks' : value.reason,
    }));
  }, [state.currentTrackIndex, state.queue.length, state.repeat, state.originalQueue, recommendationResult]);

  useEffect(() => {
    if (state.playbackStatus !== 'failed' || !state.currentTrack || skippingFailedRef.current) return;
    const key = trackKeyFromTrack(state.currentTrack);
    const batch = recommendationBatchRef.current;
    if (!batch || (!batch.injectedKeys.includes(key) && !batch.candidates.some((candidate) => candidate.trackKey === key))) return;
    skippingFailedRef.current = true;
    markBatchFailed(batch, key);
    setAutoplayDiagnostics((value) => ({ ...value, reason: 'recommendation_source_failed', failedKeys: [...batch.failedKeys] }));
    next();
    skippingFailedRef.current = false;
  }, [next, state.currentTrack, state.playbackStatus]);
  return { ...state, playTrack, playPlaylist, playFromPlaylist: playPlaylist, loadMoodPlaylist, togglePlayPause, playNext: next, playPrevious: previous, seekTo, setVolume: (volume: number) => engine.setVolume(volume), toggleShuffle: () => setState((current) => { const value = !current.shuffle; const queue = value && current.currentTrack ? [current.currentTrack, ...shuffle(current.originalQueue.filter((item) => item.id !== current.currentTrack?.id))] : current.originalQueue; recommendationBatchRef.current = null; return { ...current, shuffle: value, queue, currentTrackIndex: current.currentTrack ? queue.findIndex((item) => item.id === current.currentTrack?.id) : -1 }; }), toggleRepeat: () => setState((current) => ({ ...current, repeat: ({ off: 'all', all: 'one', one: 'off' } as const)[current.repeat] })), toggleFavorite, isFavorite: (id: string) => state.favorites.some((item) => item.id === id), createPlaylist, deletePlaylist: (id: string) => setState((current) => ({ ...current, playlists: current.playlists.filter((item) => item.id !== id) })), renamePlaylist: (id: string, name: string) => setState((current) => ({ ...current, playlists: current.playlists.map((item) => item.id === id ? { ...item, name } : item) })), addToPlaylist: (id: string, track: Track) => setState((current) => ({ ...current, playlists: current.playlists.map((item) => item.id === id && !item.tracks.some((entry) => entry.id === track.id) ? { ...item, tracks: [...item.tracks, track] } : item) })), removeFromPlaylist: (id: string, trackId: string) => setState((current) => ({ ...current, playlists: current.playlists.map((item) => item.id === id ? { ...item, tracks: item.tracks.filter((track) => track.id !== trackId) } : item) })), exportPlaylist: (id: string) => { const playlist = state.playlists.find((item) => item.id === id); if (playlist) { const url = URL.createObjectURL(new Blob([JSON.stringify(playlist)], { type: 'application/json' })); const link = document.createElement('a'); link.href = url; link.download = `${playlist.name}.json`; link.click(); URL.revokeObjectURL(url); } }, importPlaylist, clearHistory: () => setState((current) => ({ ...current, history: [] })), addToQueue, removeFromQueue, clearQueue: () => { recommendationBatchRef.current = null; setState((current) => ({ ...current, queue: current.currentTrack ? [current.currentTrack] : [], currentTrackIndex: current.currentTrack ? 0 : -1 })); }, listeningEvents, listeningSession, listeningSignals, tasteSnapshot, recommendationResult, autoplayDiagnostics, recommendationBatch: recommendationBatchRef.current, userState, homeModel, listeningCollector: collector };
};
