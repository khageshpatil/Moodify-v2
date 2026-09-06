import { Heart, MoreHorizontal, Play, Plus } from 'lucide-react';
import type { Track } from '@/data/mockMusic';
import { Artwork } from './Artwork';
import { presentTrack } from '@/presentation/providerPresentation';

interface TrackRowProps {
  track: Track;
  index?: number;
  isCurrent?: boolean;
  isPlaying?: boolean;
  onPlay: (track: Track) => void;
  onQueue?: (track: Track) => void;
  onFavorite?: () => void;
  favorited?: boolean;
}

const formatDuration = (seconds: number) => `${Math.floor(seconds / 60)}:${Math.floor(seconds % 60).toString().padStart(2, '0')}`;

export const TrackRow = ({ track, index, isCurrent, isPlaying, onPlay, onQueue, onFavorite, favorited }: TrackRowProps) => {
  const shown = presentTrack(track);
  if (!shown) return null;
  return (
  <article className={`track-row ${isCurrent ? 'track-row-current' : ''}`}>
    <button className="track-row-main" onClick={() => onPlay(track)} aria-label={`Play ${shown.title}${shown.artist ? ` by ${shown.artist}` : ''}`}>
      <span className="track-row-index">
        <span className="track-row-num">{isCurrent && isPlaying ? '♪' : index !== undefined ? String(index + 1).padStart(2, '0') : ''}</span>
        <span className="track-row-play"><Play size={13} fill="currentColor" aria-hidden="true" /></span>
      </span>
      <span className="track-art-small">
        <Artwork src={shown.albumArt} alt="" fallback={<span aria-hidden="true">{shown.title.slice(0, 1)}</span>} />
      </span>
      <span className="track-row-copy"><strong>{shown.title}</strong>{shown.artist ? <small>{shown.artist}{shown.album ? ` · ${shown.album}` : ''}</small> : null}</span>
    </button>
    <span className="track-row-album">{shown.album || ''}</span>
    <span className="track-row-duration">{shown.duration ? formatDuration(shown.duration) : '—'}</span>
    {onFavorite ? <button className={`icon-button track-row-action${favorited ? ' is-favorite' : ''}`} onClick={onFavorite} aria-label={favorited ? `Unfavorite ${shown.title}` : `Favorite ${shown.title}`}><Heart size={16} fill={favorited ? 'currentColor' : 'none'} aria-hidden="true" /></button> : null}
    {onQueue ? <button className="icon-button track-row-action" onClick={() => onQueue(track)} aria-label={`Add ${shown.title} to queue`}><Plus size={17} aria-hidden="true" /></button> : <button className="icon-button track-row-action" aria-label={`More options for ${shown.title}`}><MoreHorizontal size={17} aria-hidden="true" /></button>}
  </article>
  );
};
