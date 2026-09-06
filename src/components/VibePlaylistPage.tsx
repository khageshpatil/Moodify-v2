import { useEffect, useRef, useState } from 'react';
import { ArrowLeft, ListMusic, Pause, Play } from 'lucide-react';
import type { Track } from '@/data/mockMusic';
import { formatVibeDuration, type VibeWorld } from '@/data/vibeWorlds';
import { presentTrack } from '@/presentation/providerPresentation';
import { fetchVibePlaylistFromYouTube } from '@/services/moodifyMusicApi';

interface VibePlaylistPageProps {
  world: VibeWorld;
  currentTrack: Track | null;
  isPlaying: boolean;
  saved: boolean;
  onBack: () => void;
  onPlay: (track: Track, tracks: Track[]) => void;
  onToggleFavorite: (track: Track) => void;
  isFavorite: (id: string) => boolean;
  onSave: (tracks: Track[]) => void;
  onQueue: (track: Track) => void;
}

export const VibePlaylistPage = ({
  world,
  currentTrack,
  isPlaying,
  onBack,
  onPlay,
}: VibePlaylistPageProps) => {
  const [tracks, setTracks] = useState<Track[]>([]);
  const [status, setStatus] = useState<'loading' | 'ready' | 'empty' | 'error'>('loading');
  const [tab, setTab] = useState<'scene' | 'songs'>('scene');
  const listRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const controller = new AbortController();
    setStatus('loading');
    setTracks([]);
    setTab('scene');
    void fetchVibePlaylistFromYouTube(world.id, world.playlistQueries, controller.signal)
      .then((result) => {
        setTracks(result.tracks);
        setStatus(result.tracks.length ? 'ready' : 'empty');
      })
      .catch((error) => {
        if (error?.name === 'AbortError') return;
        setStatus('error');
      });
    return () => controller.abort();
  }, [world.id, world.playlistQueries]);

  useEffect(() => {
    if (tab !== 'songs' || !currentTrack) return;
    const active = listRef.current?.querySelector('[data-current="true"]');
    if (active instanceof HTMLElement) active.scrollIntoView({ block: 'nearest' });
  }, [tab, currentTrack?.id]);

  const playingHere = Boolean(currentTrack && tracks.some((track) => track.id === currentTrack.id));
  const playAll = () => {
    const start = playingHere && currentTrack
      ? tracks.find((track) => track.id === currentTrack.id) || tracks[0]
      : tracks[0];
    if (start) onPlay(start, tracks);
  };

  const songCount = status === 'ready' ? `${tracks.length} songs · ${formatVibeDuration(tracks)}` : status === 'loading' ? 'Finding songs' : 'Songs';

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
        <button className="vibe-play-fab" type="button" onClick={playAll} disabled={status !== 'ready'} aria-label={playingHere && isPlaying ? 'Pause this vibe' : 'Play this vibe'}>
          {playingHere && isPlaying ? <Pause size={22} fill="currentColor" aria-hidden="true" /> : <Play size={22} fill="currentColor" aria-hidden="true" />}
        </button>
      )}

      {tab === 'songs' && (
        <section className="vibe-sheet" aria-label={`${world.title} songs`}>
          <div className="vibe-sheet-head">
            <small>{songCount}</small>
            <button className="vibe-play" type="button" onClick={playAll} disabled={status !== 'ready'}>
              {playingHere && isPlaying ? <Pause size={15} fill="currentColor" aria-hidden="true" /> : <Play size={15} fill="currentColor" aria-hidden="true" />}
              {playingHere && isPlaying ? 'Pause' : 'Play'}
            </button>
          </div>
          <div className="vibe-sheet-list" ref={listRef}>
            {status === 'loading' && <p className="vibe-sheet-note">Pulling songs from YouTube Music…</p>}
            {status === 'error' && <p className="vibe-sheet-note">Couldn’t load songs. Make sure the music server is running.</p>}
            {status === 'empty' && <p className="vibe-sheet-note">No playlist songs yet.</p>}
            {status === 'ready' && tracks.map((track) => {
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
