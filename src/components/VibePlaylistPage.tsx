import { useEffect, useRef, useState } from 'react';
import { ArrowLeft, ListMusic, Pause, Play, Loader2 } from 'lucide-react';
import type { Track } from '@/data/mockMusic';
import { formatVibeDuration, type VibeWorld } from '@/data/vibeWorlds';
import { presentTrack } from '@/presentation/providerPresentation';
import { fetchVibePlaylistFromYouTube } from '@/services/moodifyMusicApi';

interface VibePlaylistPageProps {
  world: VibeWorld;
  currentTrack: Track | null;
  isPlaying: boolean;
  playbackStatus: 'idle' | 'loading' | 'ready' | 'playing' | 'paused' | 'ended' | 'failed';
  saved: boolean;
  onBack: () => void;
  onPlay: (track: Track, tracks: Track[]) => void;
  onTogglePause: () => void;
  onToggleFavorite: (track: Track) => void;
  isFavorite: (id: string) => boolean;
  onSave: (tracks: Track[]) => void;
  onQueue: (track: Track) => void;
}

export const VibePlaylistPage = ({
  world,
  currentTrack,
  isPlaying,
  playbackStatus,
  onBack,
  onPlay,
  onTogglePause,
}: VibePlaylistPageProps) => {
  const [tracks, setTracks] = useState<Track[]>([]);
  const [fetchStatus, setFetchStatus] = useState<'loading' | 'ready' | 'empty' | 'error'>('loading');
  const [tab, setTab] = useState<'scene' | 'songs'>('scene');
  const listRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const controller = new AbortController();
    setFetchStatus('loading');
    setTracks([]);
    setTab('scene');
    void fetchVibePlaylistFromYouTube(world.id, world.playlistQueries, controller.signal)
      .then((result) => {
        setTracks(result.tracks);
        setFetchStatus(result.tracks.length ? 'ready' : 'empty');
      })
      .catch((error) => {
        if (error?.name === 'AbortError') return;
        setFetchStatus('error');
      });
    return () => controller.abort();
  }, [world.id, world.playlistQueries]);

  useEffect(() => {
    if (tab !== 'songs' || !currentTrack) return;
    const active = listRef.current?.querySelector('[data-current="true"]');
    if (active instanceof HTMLElement) active.scrollIntoView({ block: 'nearest' });
  }, [tab, currentTrack?.id]);

  const playingHere = Boolean(currentTrack && tracks.some((track) => track.id === currentTrack.id));
  const handlePlayPauseClick = () => {
    if (playingHere && isPlaying) {
      // Already playing this vibe — pause it
      onTogglePause();
    } else if (playingHere && !isPlaying && playbackStatus !== 'loading') {
      // Paused — resume
      onTogglePause();
    } else {
      // Not playing this vibe yet — start from the top (or current track)
      const start = playingHere && currentTrack
        ? tracks.find((track) => track.id === currentTrack.id) || tracks[0]
        : tracks[0];
      if (start) onPlay(start, tracks);
    }
  };

  // Only show preparing spinner when actively loading (not 'idle' which is the default before any play)
  const isPreparing = fetchStatus === 'loading' || (playingHere && playbackStatus === 'loading');
  const isPlaybackError = playingHere && playbackStatus === 'failed';
  const songCount = fetchStatus === 'ready' ? `${tracks.length} songs · ${formatVibeDuration(tracks)}` : fetchStatus === 'loading' ? 'Finding songs' : 'Songs';

  return (
    <div
      className={`vibe-page${currentTrack ? ' has-player' : ''}${tab === 'songs' ? ' is-songs' : ''}`}
      style={{ '--vibe-focus': world.artFocus, '--vibe-accent': world.accent } as React.CSSProperties}
    >
      <img className="vibe-poster" src={world.artwork} alt="" />
      <div className="vibe-wordmark">
        <h1>{world.title}</h1>
      </div>

      <button className="vibe-back" type="button" onClick={onBack}><ArrowLeft size={16} aria-hidden="true" /> Back</button>

      <div className="vibe-tabs" role="tablist" aria-label="Vibe view">
        <button type="button" role="tab" aria-selected={tab === 'scene'} className={tab === 'scene' ? 'is-active' : ''} onClick={() => setTab('scene')}>Scene</button>
        <button type="button" role="tab" aria-selected={tab === 'songs'} className={tab === 'songs' ? 'is-active' : ''} onClick={() => setTab('songs')}><ListMusic size={13} aria-hidden="true" /> Songs</button>
      </div>

      {tab === 'scene' && (
        <div className="vibe-play-fab-container">
          <button
            className="vibe-play-fab"
            type="button"
            onClick={handlePlayPauseClick}
            disabled={fetchStatus !== 'ready' && !playingHere}
            aria-label={playingHere && isPlaying ? 'Pause this vibe' : 'Play this vibe'}
          >
            {isPreparing ? (
              <Loader2 size={22} className="animate-spin" aria-hidden="true" />
            ) : playingHere && isPlaying ? (
              <Pause size={22} fill="currentColor" aria-hidden="true" />
            ) : (
              <Play size={22} fill="currentColor" aria-hidden="true" />
            )}
          </button>
          {isPreparing && <span className="vibe-play-status-label">Preparing your mix</span>}
          {playingHere && isPlaying && <span className="vibe-play-status-label">Playing</span>}
          {playingHere && !isPlaying && !isPreparing && !isPlaybackError && <span className="vibe-play-status-label">Paused</span>}
          {isPlaybackError && <span className="vibe-play-status-label error">Playback failed</span>}
        </div>
      )}

      {tab === 'songs' && (
        <section className="vibe-sheet" aria-label={`${world.title} songs`}>
          <div className="vibe-sheet-head">
            <small>{songCount}</small>
            <button className="vibe-play" type="button" onClick={handlePlayPauseClick} disabled={fetchStatus !== 'ready' && !playingHere}>
              {isPreparing ? <Loader2 size={15} className="animate-spin" aria-hidden="true" /> : playingHere && isPlaying ? <Pause size={15} fill="currentColor" aria-hidden="true" /> : <Play size={15} fill="currentColor" aria-hidden="true" />}
              {isPreparing ? 'Preparing...' : playingHere && isPlaying ? 'Pause' : 'Play'}
            </button>
          </div>
          <div className="vibe-sheet-list" ref={listRef}>
            {fetchStatus === 'loading' && <p className="vibe-sheet-note">Pulling songs from YouTube Music…</p>}
            {fetchStatus === 'error' && <p className="vibe-sheet-note">Couldn’t load songs. Make sure the music server is running.</p>}
            {fetchStatus === 'empty' && <p className="vibe-sheet-note">No playlist songs yet.</p>}
            {fetchStatus === 'ready' && tracks.map((track) => {
              const shown = presentTrack(track);
              if (!shown) return null;
              const current = currentTrack?.id === track.id;
              return (
                <button
                  key={track.id}
                  type="button"
                  className={`vibe-song${current ? ' is-current' : ''}`}
                  data-current={current ? 'true' : undefined}
                  onClick={() => onPlay(track, tracks)}
                >
                  <span>
                    <strong>{shown.title}</strong>
                    {shown.artist ? <small>{shown.artist}</small> : null}
                  </span>
                  {current && isPlaying ? <em>Now</em> : null}
                </button>
              );
            })}
          </div>
        </section>
      )}
    </div>
  );
};
