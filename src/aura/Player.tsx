import { ChevronDown, Heart, ListMusic, Loader2, Maximize2, MicVocal, MoreHorizontal, Pause, Play, Repeat, Repeat1, Shuffle, SkipBack, SkipForward, Users, Volume1, Volume2, VolumeX, X } from 'lucide-react';
import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { fetchTrackLyrics } from '@/services/moodifyMusicApi';
import { usePlayer } from './PlayerContext';
import { useRoom } from './social/RoomProvider';
import { useTrackMenu } from './TrackMenu';
import { Artwork, Bars, formatTime } from './ui';

type Player = ReturnType<typeof usePlayer>;

/** The play/pause button; a spinner while a song is starting. */
function PlayButton({ player, size, className }: { player: Player; size: number; className: string }) {
  const loading = player.playbackStatus === 'loading';
  return (
    <button className={className} aria-label={loading ? 'Loading' : player.isPlaying ? 'Pause' : 'Play'} onClick={player.togglePlayPause}>
      {loading ? <Loader2 size={size} className="aura-spin" />
        : player.isPlaying ? <Pause size={size} fill="currentColor" /> : <Play size={size} fill="currentColor" style={{ marginLeft: size / 9 }} />}
    </button>
  );
}

/** A seek bar that only seeks when you let go (dragging doesn't stutter the song). */
function SeekBar({ player, showTimes = true }: { player: Player; showTimes?: boolean }) {
  const [dragging, setDragging] = useState<number | null>(null);
  const duration = player.duration || player.currentTrack?.duration || 0;
  const position = Math.min(dragging ?? player.currentTime, duration || Infinity);
  const commit = () => { if (dragging !== null) { player.seekTo(dragging); setDragging(null); } };
  const pct = duration > 0 ? (position / duration) * 100 : 0;
  return (
    <div className="aura-seek-row">
      {showTimes && <span className="type-label-small aura-time">{formatTime(position)}</span>}
      <input
        className="aura-seek" type="range" min={0} max={Math.max(1, duration)} step={1} value={position || 0}
        style={{ ['--pct' as string]: `${pct}%` }} disabled={!duration}
        aria-label="Seek" aria-valuetext={`${formatTime(position)} of ${formatTime(duration)}`}
        onChange={(event) => setDragging(Number(event.target.value))}
        onPointerUp={commit} onKeyUp={commit} onBlur={commit}
      />
      {showTimes && <span className="type-label-small aura-time">{formatTime(duration)}</span>}
    </div>
  );
}

function VolumeControl({ player }: { player: Player }) {
  const before = useRef(70);
  const Icon = player.volume === 0 ? VolumeX : player.volume < 50 ? Volume1 : Volume2;
  return (
    <div className="aura-row" style={{ gap: 4 }}>
      <button className="aura-icon-btn" aria-label={player.volume === 0 ? 'Unmute' : 'Mute'}
        onClick={() => { if (player.volume === 0) player.setVolume(before.current || 70); else { before.current = player.volume; player.setVolume(0); } }}>
        <Icon size={20} />
      </button>
      <input className="aura-seek aura-volume" type="range" min={0} max={100} step={1} value={player.volume} aria-label="Volume"
        style={{ ['--pct' as string]: `${player.volume}%` }} onChange={(e) => player.setVolume(Number(e.target.value))} />
    </div>
  );
}

/**
 * Docked above the tabs on phones (tap to open the full player); a full
 * playback bar on wide screens: song, controls with seek, volume.
 */
export function MiniPlayer({ onOpen }: { onOpen: (view?: View) => void }) {
  const player = usePlayer();
  const menu = useTrackMenu();
  const track = player.currentTrack;
  if (!track) return null;
  const duration = player.duration || track.duration || 0;
  const progress = duration > 0 ? (player.currentTime / duration) * 100 : 0;
  const liked = player.isFavorite(track.id);
  const RepeatIcon = player.repeat === 'one' ? Repeat1 : Repeat;
  return (
    <div className="aura-mini" role="region" aria-label="Now playing">
      <div className="aura-mini-track">
        <button className="aura-row" style={{ minWidth: 0, flex: 1, textAlign: 'left' }} onClick={() => onOpen()} aria-label={`Open player: ${track.title}`}>
          <Artwork src={track.albumArt} size={48} radius="var(--radius-sm)" placeholder={track.id} />
          <span style={{ minWidth: 0 }}>
            <span className="type-title-small aura-ellipsis" style={{ display: 'block' }}>{track.title}</span>
            <span className="type-body-small aura-muted aura-ellipsis" style={{ display: 'block' }}>
              {player.playbackStatus === 'failed' ? <span style={{ color: 'var(--aura-error)' }}>Couldn't play this song</span> : track.artist}
            </span>
          </span>
        </button>
        <button className="aura-icon-btn aura-desk-only" aria-label={liked ? 'Remove from liked songs' : 'Like'} onClick={() => player.toggleFavorite(track)}>
          <Heart size={20} fill={liked ? 'currentColor' : 'none'} color={liked ? 'var(--aura-primary)' : 'currentColor'} />
        </button>
      </div>
      <div className="aura-mini-center aura-desk-only">
        <div className="aura-row" style={{ gap: 8, justifyContent: 'center' }}>
          <button className="aura-icon-btn aura-icon-sm" aria-label={player.shuffle ? 'Shuffle on' : 'Shuffle off'} aria-pressed={player.shuffle} onClick={player.toggleShuffle}><Shuffle size={18} /></button>
          <button className="aura-icon-btn aura-icon-sm" aria-label="Previous track" onClick={player.playPrevious}><SkipBack size={20} fill="currentColor" /></button>
          <PlayButton player={player} size={20} className="aura-play aura-play-sm" />
          <button className="aura-icon-btn aura-icon-sm" aria-label="Next track" onClick={player.playNext}><SkipForward size={20} fill="currentColor" /></button>
          <button className="aura-icon-btn aura-icon-sm" aria-label={`Repeat ${player.repeat}`} aria-pressed={player.repeat !== 'off'} onClick={player.toggleRepeat}><RepeatIcon size={18} /></button>
        </div>
        <SeekBar player={player} />
      </div>
      <div className="aura-mini-right">
        <span className="aura-phone-only aura-row" style={{ gap: 0 }}>
          <PlayButton player={player} size={24} className="aura-icon-btn" />
          <button className="aura-icon-btn" aria-label="Next track" onClick={player.playNext}><SkipForward size={22} fill="currentColor" /></button>
        </span>
        <span className="aura-desk-only aura-row" style={{ gap: 0 }}>
          <button className="aura-icon-btn" aria-label="Lyrics" onClick={() => onOpen('lyrics')}><MicVocal size={20} /></button>
          <button className="aura-icon-btn" aria-label="Queue" onClick={() => onOpen('queue')}><ListMusic size={20} /></button>
          <button className="aura-icon-btn" aria-label="More options" onClick={() => menu.open(track)}><MoreHorizontal size={20} /></button>
          <VolumeControl player={player} />
          <button className="aura-icon-btn" aria-label="Open full player" onClick={() => onOpen()}><Maximize2 size={18} /></button>
        </span>
      </div>
      <div className="aura-mini-progress aura-phone-only" style={{ width: `${progress}%` }} />
    </div>
  );
}

export type View = 'cover' | 'lyrics' | 'queue';

function Lyrics({ trackId }: { trackId: string }) {
  const [state, setState] = useState<{ id: string; text: string | null; loading: boolean }>({ id: trackId, text: null, loading: true });
  useEffect(() => {
    const controller = new AbortController();
    setState({ id: trackId, text: null, loading: true });
    fetchTrackLyrics(trackId, controller.signal).then((text) => { if (!controller.signal.aborted) setState({ id: trackId, text, loading: false }); });
    return () => controller.abort();
  }, [trackId]);
  if (state.loading) return <div className="aura-lyrics aura-muted">Loading lyrics…</div>;
  if (!state.text) return <div className="aura-lyrics aura-muted" style={{ display: 'grid', placeItems: 'center' }}>No lyrics for this song.</div>;
  return (
    <div className="aura-lyrics" tabIndex={0} aria-label="Lyrics">
      {state.text.split('\n').map((line, i) => (line.trim() ? <p key={i}>{line}</p> : <br key={i} />))}
    </div>
  );
}

function Queue({ player }: { player: Player }) {
  const current = player.currentTrack;
  const start = player.currentTrackIndex + 1;
  const upNext = player.queue.slice(start, start + 50);
  return (
    <div className="aura-queue">
      {current && (
        <>
          <div className="type-label-large aura-queue-head">Now playing</div>
          <div className="aura-track" data-current="true">
            <span style={{ position: 'relative' }}><Artwork src={current.albumArt} size={44} placeholder={current.id} /><span className="aura-art-overlay">{player.isPlaying ? <Bars /> : <Play size={16} fill="currentColor" />}</span></span>
            <span className="aura-track-text"><span className="type-title-small aura-ellipsis" style={{ display: 'block' }}>{current.title}</span><span className="type-body-small aura-ellipsis" style={{ display: 'block', opacity: 0.7 }}>{current.artist}</span></span>
          </div>
        </>
      )}
      <div className="aura-row aura-queue-head">
        <span className="type-label-large" style={{ flex: 1 }}>Up next</span>
        {upNext.length > 0 && <button className="aura-btn-text" style={{ color: 'inherit', opacity: 0.8 }} onClick={player.clearQueue}>Clear</button>}
      </div>
      {upNext.length === 0 && <div style={{ opacity: 0.7, padding: '4px 8px' }}>Nothing queued. Use ⋮ on any song to add it.</div>}
      {upNext.map((item, offset) => (
        <div key={`${item.id}-${offset}`} className="aura-track">
          <button className="aura-row" style={{ flex: 1, minWidth: 0, textAlign: 'left' }} onClick={() => player.playTrack(item, player.queue)} aria-label={`Play ${item.title}`}>
            <Artwork src={item.albumArt} size={44} placeholder={item.id} />
            <span className="aura-track-text"><span className="type-title-small aura-ellipsis" style={{ display: 'block' }}>{item.title}</span><span className="type-body-small aura-ellipsis" style={{ display: 'block', opacity: 0.7 }}>{item.artist}</span></span>
          </button>
          <button className="aura-icon-btn" aria-label={`Remove ${item.title} from the queue`} onClick={() => player.removeFromQueue(start + offset)}><X size={18} /></button>
        </div>
      ))}
    </div>
  );
}

/** Full-screen player with a blurred artwork backdrop. Escape or ⌄ closes it. */
export function FullPlayer({ onClose, initialView = 'cover' }: { onClose: () => void; initialView?: View }) {
  const player = usePlayer();
  const menu = useTrackMenu();
  const { room } = useRoom();
  const track = player.currentTrack;
  const [view, setView] = useState<View>(initialView);
  useEffect(() => setView(initialView), [initialView]);

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => { if (event.key === 'Escape') onClose(); };
    window.addEventListener('keydown', onKey);
    const overflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => { window.removeEventListener('keydown', onKey); document.body.style.overflow = overflow; };
  }, [onClose]);

  if (!track) return null;
  const liked = player.isFavorite(track.id);
  const RepeatIcon = player.repeat === 'one' ? Repeat1 : Repeat;
  const toggle = (next: View) => setView((v) => (v === next ? 'cover' : next));

  return (
    <div className="aura-full" role="dialog" aria-modal="true" aria-label={`Now playing: ${track.title}`}>
      <div className="aura-full-bg" style={{ backgroundImage: track.albumArt ? `url("${track.albumArt}")` : undefined }} />
      <div className="aura-row" style={{ padding: '12px 12px 0' }}>
        <button className="aura-icon-btn" aria-label="Close player" onClick={onClose}><ChevronDown size={28} /></button>
        <div style={{ flex: 1, textAlign: 'center' }} className="type-label-small" aria-hidden>
          <div style={{ opacity: 0.6 }}>{room?.following ? 'FOLLOWING' : room ? 'LISTENING ROOM' : 'NOW PLAYING'}</div>
          {room ? <div className="type-label-medium aura-mono">{room.following ? room.following.name : room.roomId}</div>
            : player.queue.length > 1 && <div className="type-label-medium">{player.currentTrackIndex + 1} of {player.queue.length}</div>}
        </div>
        <Link to="/room" onClick={onClose} className="aura-icon-btn" aria-label={room?.following ? `Following ${room.following.name}` : room ? `Listening room ${room.roomId}` : 'Listen together'}><Users size={22} /></Link>
        <button className="aura-icon-btn" aria-label="More options" onClick={() => menu.open(track)}><MoreHorizontal size={22} /></button>
      </div>
      <div className="aura-full-body">
        <div className="aura-full-stage">
          {view === 'cover' && (track.albumArt
            ? <img className="aura-full-cover" src={track.albumArt} alt={`Cover of ${track.title}`} referrerPolicy="no-referrer" />
            : <Artwork size="min(100%, 52vh)" radius="var(--radius-xl)" placeholder={track.id} />)}
          {view === 'lyrics' && <Lyrics trackId={track.providerId || track.id} />}
          {view === 'queue' && <Queue player={player} />}
        </div>
        <div style={{ display: 'grid', gap: 14, gridTemplateColumns: 'minmax(0, 1fr)', minWidth: 0 }}>
          <div className="aura-row">
            <div style={{ flex: 1, minWidth: 0 }}>
              <div className="type-headline-small aura-ellipsis">{track.title}</div>
              {track.artistId
                ? <Link to={`/browse/artist/${encodeURIComponent(track.artistId)}`} onClick={onClose} className="type-body-large aura-ellipsis aura-link" style={{ display: 'block', opacity: 0.75 }}>{track.artist}</Link>
                : <div className="type-body-large aura-ellipsis" style={{ opacity: 0.75 }}>{track.artist}</div>}
            </div>
            <button className="aura-icon-btn" aria-label={liked ? 'Remove from liked songs' : 'Like'} onClick={() => player.toggleFavorite(track)}>
              <Heart size={26} fill={liked ? 'currentColor' : 'none'} />
            </button>
          </div>
          <SeekBar player={player} />
          <div className="aura-transport">
            <button className="aura-icon-btn" aria-label={player.shuffle ? 'Shuffle on' : 'Shuffle off'} aria-pressed={player.shuffle} onClick={player.toggleShuffle}><Shuffle size={22} /></button>
            <button className="aura-icon-btn" aria-label="Previous track" onClick={player.playPrevious}><SkipBack size={30} fill="currentColor" /></button>
            <PlayButton player={player} size={34} className="aura-play" />
            <button className="aura-icon-btn" aria-label="Next track" onClick={player.playNext}><SkipForward size={30} fill="currentColor" /></button>
            <button className="aura-icon-btn" aria-label={`Repeat ${player.repeat}`} aria-pressed={player.repeat !== 'off'} onClick={player.toggleRepeat}><RepeatIcon size={22} /></button>
          </div>
          {player.playbackError && player.playbackStatus === 'failed' && <div className="aura-banner" data-error="true">Couldn't play this song. {player.queue.length > 1 ? 'Try the next one.' : 'Try another song.'}</div>}
          <div className="aura-row aura-full-tools">
            <button className="aura-pill-btn" aria-pressed={view === 'lyrics'} onClick={() => toggle('lyrics')}><MicVocal size={18} />Lyrics</button>
            <button className="aura-pill-btn" aria-pressed={view === 'queue'} onClick={() => toggle('queue')}><ListMusic size={18} />Queue</button>
            <span className="aura-spacer" />
            <span className="aura-desk-only"><VolumeControl player={player} /></span>
          </div>
        </div>
      </div>
    </div>
  );
}
