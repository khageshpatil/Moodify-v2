import { ChevronDown, Heart, ListMusic, Pause, Play, Repeat, Shuffle, SkipBack, SkipForward } from 'lucide-react';
import { useEffect, useState } from 'react';
import type { Track } from '@/data/mockMusic';
import { Artwork } from './Artwork';
import { presentTrack } from '@/presentation/providerPresentation';
import { fetchTrackLyrics } from '@/services/moodifyMusicApi';

interface MoodifyPlayerProps {
  currentTrack: Track | null;
  isPlaying: boolean;
  currentTime: number;
  duration: number;
  volume: number;
  shuffle: boolean;
  repeat: 'off' | 'all' | 'one';
  isFavorite: boolean;
  onPlayPause: () => void;
  onNext: () => void;
  onPrevious: () => void;
  onSeek: (time: number) => void;
  onVolumeChange: (volume: number) => void;
  onToggleShuffle: () => void;
  onToggleRepeat: () => void;
  onToggleFavorite: () => void;
  onOpenQueue?: () => void;
  relatedTracks?: Track[];
  onPlayRelated?: (track: Track, tracks: Track[]) => void;
}

const time = (value: number) => `${Math.floor(value / 60)}:${Math.floor(value % 60).toString().padStart(2, '0')}`;

export const MoodifyPlayer = ({ currentTrack, isPlaying, currentTime, duration, volume, shuffle, repeat, isFavorite, onPlayPause, onNext, onPrevious, onSeek, onVolumeChange, onToggleShuffle, onToggleRepeat, onToggleFavorite, onOpenQueue, relatedTracks = [], onPlayRelated }: MoodifyPlayerProps) => {
  const [expanded, setExpanded] = useState(false);
  const [lyrics, setLyrics] = useState<string | null>(null);
  useEffect(() => {
    if (!expanded) return;
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setExpanded(false);
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [expanded]);
  useEffect(() => {
    if (!expanded || !currentTrack?.id) {
      setLyrics(null);
      return;
    }
    const controller = new AbortController();
    setLyrics(null);
    fetchTrackLyrics(currentTrack.id, controller.signal).then((next) => {
      if (!controller.signal.aborted) setLyrics(next);
    }).catch(() => {
      if (!controller.signal.aborted) setLyrics(null);
    });
    return () => controller.abort();
  }, [expanded, currentTrack?.id]);
  if (!currentTrack) return null;
  const shown = presentTrack(currentTrack) || { ...currentTrack, title: '', artist: '', album: '' };
  const artwork = shown.albumArt;
  const progress = duration ? Math.min(100, (currentTime / duration) * 100) : 0;
  const visibleRelated = relatedTracks.map((track) => presentTrack(track)).filter((track): track is Track => Boolean(track));
  return (
    <>
      <div className="mini-player" role="region" aria-label="Now playing">
        <button className="mini-player-track" onClick={() => setExpanded(true)} aria-label={`Open full player for ${shown.title || 'current track'}`}>
          <span className="mini-art mini-vinyl">
            <Artwork src={artwork} alt="" fallback={shown.title.slice(0, 1) || null} />
            <span className="mini-vinyl-hole" aria-hidden="true" />
          </span>
          <span>
            {shown.title ? <strong>{shown.title}</strong> : null}
            {shown.artist ? <small>{shown.artist}</small> : null}
          </span>
        </button>
        <div className="mini-seek">
          <input
            className="mini-seek-range"
            type="range"
            min="0"
            max={duration || 1}
            step="0.1"
            value={Math.min(currentTime, duration || 1)}
            onChange={(event) => onSeek(Number(event.target.value))}
            aria-label="Track progress"
            style={{ '--range-progress': `${progress}%` } as React.CSSProperties}
          />
          <span className="mini-seek-time">{time(currentTime)} / {time(duration)}</span>
        </div>
        <div className="mini-controls">
          <button className={`icon-button mini-shuffle${shuffle ? ' is-active' : ''}`} onClick={onToggleShuffle} aria-label="Toggle shuffle"><Shuffle size={16} /></button>
          <button className="icon-button mini-action" onClick={onPrevious} aria-label="Previous track"><SkipBack size={18} fill="currentColor" /></button>
          <button className="play-button" onClick={onPlayPause} aria-label={isPlaying ? 'Pause' : 'Play'}>{isPlaying ? <Pause size={18} fill="currentColor" /> : <Play size={18} fill="currentColor" />}</button>
          <button className="icon-button mini-action" onClick={onNext} aria-label="Next track"><SkipForward size={18} fill="currentColor" /></button>
          {onOpenQueue ? <button className="icon-button mini-queue" onClick={onOpenQueue} aria-label="Open queue"><ListMusic size={17} /></button> : null}
        </div>
      </div>
      {expanded && <div className="full-player-backdrop" role="dialog" aria-modal="true" aria-label="Full player" style={{ '--player-scene': artwork ? `url(${artwork})` : 'none' } as React.CSSProperties}>
        <div className="full-player">
          <button className="full-player-close icon-button" onClick={() => setExpanded(false)} aria-label="Close full player"><ChevronDown size={22} /></button>
          <div className="full-player-kicker">now playing</div>
          <div className="full-artwork"><Artwork src={artwork} alt={shown.title ? `${shown.title} artwork` : ''} fallback={shown.title ? <span>{shown.title.slice(0, 1)}</span> : null} /></div>
          <div className="full-copy"><div>{shown.title ? <h2>{shown.title}</h2> : null}{shown.artist ? <p>{shown.artist}</p> : null}</div><button className={`icon-button ${isFavorite ? 'is-favorite' : ''}`} onClick={onToggleFavorite} aria-label={isFavorite ? 'Remove from favorites' : 'Add to favorites'}><Heart size={23} fill={isFavorite ? 'currentColor' : 'none'} /></button></div>
          <input className="player-range" type="range" min="0" max={duration || 1} step="0.1" value={Math.min(currentTime, duration || 1)} onChange={(event) => onSeek(Number(event.target.value))} aria-label="Track progress" style={{ '--range-progress': `${progress}%` } as React.CSSProperties} />
          <div className="player-time"><span>{time(currentTime)}</span><span>{time(duration)}</span></div>
          <div className="full-controls"><button className={`icon-button ${shuffle ? 'is-active' : ''}`} onClick={onToggleShuffle} aria-label="Toggle shuffle"><Shuffle size={19} /></button><button className="icon-button" onClick={onPrevious} aria-label="Previous track"><SkipBack size={22} fill="currentColor" /></button><button className="full-play" onClick={onPlayPause} aria-label={isPlaying ? 'Pause' : 'Play'}>{isPlaying ? <Pause size={25} fill="currentColor" /> : <Play size={25} fill="currentColor" />}</button><button className="icon-button" onClick={onNext} aria-label="Next track"><SkipForward size={22} fill="currentColor" /></button><button className={`icon-button ${repeat !== 'off' ? 'is-active' : ''}`} onClick={onToggleRepeat} aria-label="Toggle repeat"><Repeat size={19} /></button></div>
          <div className="full-volume"><span>Volume</span><input type="range" min="0" max="100" value={volume} onChange={(event) => onVolumeChange(Number(event.target.value))} aria-label="Volume" /></div>
          {lyrics && <div className="full-lyrics">{lyrics}</div>}
          {visibleRelated.length > 0 && onPlayRelated && (
            <div className="full-related">
              {visibleRelated.slice(0, 5).map((track) => (
                <button key={track.id} type="button" className="full-related-item" onClick={() => onPlayRelated(track, visibleRelated)}>
                  <strong>{track.title}</strong>
                  <small>{track.artist}</small>
                </button>
              ))}
            </div>
          )}
        </div>
      </div>}
    </>
  );
};
