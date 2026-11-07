import { ChevronDown, Heart, ListMusic, Pause, Play, Repeat, Repeat1, Shuffle, SkipBack, SkipForward, Users } from 'lucide-react';
import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { usePlayer } from './PlayerContext';
import { useRoom } from './social/RoomProvider';
import { Artwork, formatTime } from './ui';

/** Docked above the tabs; tap to open the full player. */
export function MiniPlayer({ onOpen }: { onOpen: () => void }) {
  const player = usePlayer();
  const track = player.currentTrack;
  if (!track) return null;
  const progress = player.duration > 0 ? (player.currentTime / player.duration) * 100 : 0;
  return (
    <div className="aura-mini" role="region" aria-label="Now playing">
      <button className="aura-row" style={{ flex: 1, minWidth: 0, textAlign: 'left' }} onClick={onOpen} aria-label={`Open player: ${track.title}`}>
        <Artwork src={track.albumArt} size={48} radius="50%" placeholder={track.id} />
        <span style={{ minWidth: 0 }}>
          <span className="type-title-small aura-ellipsis" style={{ display: 'block' }}>{track.title}</span>
          <span className="type-body-small aura-muted aura-ellipsis" style={{ display: 'block' }}>{track.artist}</span>
        </span>
      </button>
      <button className="aura-icon-btn" aria-label={player.isPlaying ? 'Pause' : 'Play'} onClick={player.togglePlayPause}>
        {player.isPlaying ? <Pause size={24} fill="currentColor" /> : <Play size={24} fill="currentColor" />}
      </button>
      <button className="aura-icon-btn" aria-label="Next track" onClick={player.playNext}><SkipForward size={22} fill="currentColor" /></button>
      <div className="aura-mini-progress" style={{ width: `${progress}%` }} />
    </div>
  );
}

/** Full-screen player with a blurred artwork backdrop. Escape or ⌄ closes it. */
export function FullPlayer({ onClose }: { onClose: () => void }) {
  const player = usePlayer();
  const { room } = useRoom();
  const track = player.currentTrack;
  const [showQueue, setShowQueue] = useState(false);
  const [dragging, setDragging] = useState<number | null>(null);

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => { if (event.key === 'Escape') onClose(); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  if (!track) return null;
  const liked = player.isFavorite(track.id);
  const RepeatIcon = player.repeat === 'one' ? Repeat1 : Repeat;
  const position = dragging ?? player.currentTime;
  const upNext = player.queue.slice(player.currentTrackIndex + 1, player.currentTrackIndex + 21);

  return (
    <div className="aura-full" role="dialog" aria-modal="true" aria-label={`Now playing: ${track.title}`}>
      <div className="aura-full-bg" style={{ backgroundImage: `url("${track.albumArt}")` }} />
      <div className="aura-row" style={{ padding: '12px 12px 0' }}>
        <button className="aura-icon-btn" aria-label="Close player" onClick={onClose}><ChevronDown size={28} /></button>
        <div style={{ flex: 1, textAlign: 'center' }} className="type-label-small" aria-hidden>
          <div style={{ opacity: 0.6 }}>{room ? 'LISTENING ROOM' : 'NOW PLAYING'}</div>
          {room ? <div className="type-label-medium aura-mono">{room.roomId}</div>
            : player.queue.length > 1 && <div className="type-label-medium">{player.currentTrackIndex + 1} of {player.queue.length}</div>}
        </div>
        <Link to="/room" onClick={onClose} className="aura-icon-btn" aria-label={room ? `Listening room ${room.roomId}` : 'Listen together'}><Users size={22} /></Link>
        <button className="aura-icon-btn" aria-label="Queue" aria-pressed={showQueue} onClick={() => setShowQueue((v) => !v)}><ListMusic size={22} /></button>
      </div>
      <div className="aura-full-body">
        <div />
        {showQueue ? (
          <div style={{ overflowY: 'auto', maxHeight: '56vh' }}>
            <div className="type-title-medium" style={{ marginBottom: 8 }}>Up next</div>
            {upNext.length === 0 && <div className="aura-muted">Nothing queued.</div>}
            {upNext.map((item, offset) => (
              <button key={`${item.id}-${offset}`} className="aura-track" onClick={() => player.playTrack(item, player.queue)}>
                <Artwork src={item.albumArt} size={44} placeholder={item.id} />
                <span className="aura-track-text">
                  <span className="type-title-small aura-ellipsis" style={{ display: 'block' }}>{item.title}</span>
                  <span className="type-body-small aura-ellipsis" style={{ display: 'block', opacity: 0.7 }}>{item.artist}</span>
                </span>
              </button>
            ))}
          </div>
        ) : (
          <img className="aura-full-cover" src={track.albumArt} alt={`Cover of ${track.title}`} referrerPolicy="no-referrer" />
        )}
        <div style={{ display: 'grid', gap: 16 }}>
          <div className="aura-row">
            <div style={{ flex: 1, minWidth: 0 }}>
              <div className="type-headline-small aura-ellipsis">{track.title}</div>
              {track.artistId
                ? <Link to={`/browse/artist/${encodeURIComponent(track.artistId)}`} onClick={onClose} className="type-body-large aura-ellipsis" style={{ display: 'block', opacity: 0.75 }}>{track.artist}</Link>
                : <div className="type-body-large aura-ellipsis" style={{ opacity: 0.75 }}>{track.artist}</div>}
            </div>
            <button className="aura-icon-btn" aria-label={liked ? 'Remove from liked songs' : 'Like'} onClick={() => player.toggleFavorite(track)}>
              <Heart size={26} fill={liked ? 'currentColor' : 'none'} />
            </button>
          </div>
          <div>
            <input
              className="aura-seek" type="range" min={0} max={Math.max(1, player.duration)} step={1} value={Math.min(position, player.duration || position)}
              aria-label="Seek" aria-valuetext={`${formatTime(position)} of ${formatTime(player.duration)}`}
              onChange={(event) => setDragging(Number(event.target.value))}
              onMouseUp={() => { if (dragging !== null) { player.seekTo(dragging); setDragging(null); } }}
              onTouchEnd={() => { if (dragging !== null) { player.seekTo(dragging); setDragging(null); } }}
              onKeyUp={() => { if (dragging !== null) { player.seekTo(dragging); setDragging(null); } }}
            />
            <div className="aura-row type-label-medium" style={{ opacity: 0.7 }}><span>{formatTime(position)}</span><span className="aura-spacer" /><span>{formatTime(player.duration)}</span></div>
          </div>
          <div className="aura-transport">
            <button className="aura-icon-btn" aria-label={player.shuffle ? 'Shuffle on' : 'Shuffle off'} aria-pressed={player.shuffle} onClick={player.toggleShuffle}><Shuffle size={22} /></button>
            <button className="aura-icon-btn" aria-label="Previous track" onClick={player.playPrevious}><SkipBack size={30} fill="currentColor" /></button>
            <button className="aura-play" aria-label={player.isPlaying ? 'Pause' : 'Play'} onClick={player.togglePlayPause}>
              {player.isPlaying ? <Pause size={34} fill="currentColor" /> : <Play size={34} fill="currentColor" style={{ marginLeft: 4 }} />}
            </button>
            <button className="aura-icon-btn" aria-label="Next track" onClick={player.playNext}><SkipForward size={30} fill="currentColor" /></button>
            <button className="aura-icon-btn" aria-label={`Repeat ${player.repeat}`} aria-pressed={player.repeat !== 'off'} onClick={player.toggleRepeat}><RepeatIcon size={22} /></button>
          </div>
          {player.playbackError && <div className="aura-banner" data-error="true">{player.playbackError.message}</div>}
        </div>
      </div>
    </div>
  );
}
