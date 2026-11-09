import { Heart, MoreVertical, Music2, Play } from 'lucide-react';
import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';
import type { Track } from '@/data/mockMusic';
import type { DiscoveryCatalogItem } from '@/services/moodifyMusicApi';
import { usePlayer } from './PlayerContext';
import { useTrackMenu, type TrackMenuExtra } from './TrackMenu';

/** Stable placeholder hue from a key (same idea as Android's placeholderHue). */
export function hueFor(key: string) {
  const hues = ['violet', 'coral', 'teal', 'amber', 'blue', 'pink', 'green', 'bronze'];
  let hash = 0;
  for (const ch of key) hash = (hash * 31 + ch.charCodeAt(0)) | 0;
  return `var(--mood-${hues[Math.abs(hash) % hues.length]})`;
}

export function Artwork({ src, alt = '', size, radius = 'var(--radius-sm)', round = false, placeholder = '' }: {
  src?: string; alt?: string; size: number | string; radius?: string; round?: boolean; placeholder?: string;
}) {
  const style = { width: size, height: size, borderRadius: round ? '50%' : radius };
  if (!src) {
    return (
      <div className="aura-art" style={{ ...style, display: 'grid', placeItems: 'center', background: `color-mix(in srgb, ${hueFor(placeholder || alt)} 45%, var(--aura-surface-high))` }}>
        <Music2 size={20} color="white" aria-hidden />
      </div>
    );
  }
  return <img className="aura-art" src={src} alt={alt} loading="lazy" style={style} referrerPolicy="no-referrer" />;
}

export function SectionHeader({ title, action }: { title: string; action?: ReactNode }) {
  return (
    <div className="aura-section-head">
      <h2 className="type-title-large" style={{ margin: 0 }}>{title}</h2>
      {action}
    </div>
  );
}

export function TrackRow({ track, queue, number, subtitle, menuExtras, compact = false }: {
  track: Track; queue: Track[]; number?: number; subtitle?: string; menuExtras?: TrackMenuExtra[]; compact?: boolean;
}) {
  const player = usePlayer();
  const menu = useTrackMenu();
  const current = player.currentTrack?.id === track.id;
  const liked = player.isFavorite(track.id);
  const duration = track.duration > 0 ? formatTime(track.duration) : '';
  return (
    <div className="aura-track" data-current={current} onContextMenu={(e) => { e.preventDefault(); menu.open(track, menuExtras); }}>
      <button className="aura-row" style={{ flex: 1, minWidth: 0, textAlign: 'left' }} onClick={() => (current ? player.togglePlayPause() : player.playTrack(track, queue))}
        aria-label={current ? `${player.isPlaying ? 'Pause' : 'Play'} ${track.title}` : `Play ${track.title} by ${track.artist}`}>
        {number !== undefined && <span className="type-title-small aura-muted" style={{ width: 24, textAlign: 'right', flex: 'none' }}>{number}</span>}
        <span style={{ position: 'relative', flex: 'none' }}>
          <Artwork src={track.albumArt} alt="" size={compact ? 48 : 52} placeholder={track.id} />
          {current && <span className="aura-art-overlay" aria-hidden>{player.isPlaying ? <Bars /> : <Play size={18} fill="currentColor" />}</span>}
        </span>
        <span className="aura-track-text">
          <span className="type-title-small aura-ellipsis aura-track-title" style={{ display: 'block' }}>{track.title}</span>
          <span className="type-body-small aura-muted aura-ellipsis" style={{ display: 'block' }}>
            {subtitle ?? [track.artist, compact ? '' : track.album].filter(Boolean).join(' • ')}
          </span>
        </span>
      </button>
      {!compact && duration && <span className="type-body-small aura-muted aura-hide-phone" style={{ minWidth: 40, textAlign: 'right' }}>{duration}</span>}
      {!compact && (
        <button className="aura-icon-btn aura-hide-phone" aria-label={liked ? 'Remove from liked songs' : 'Like'} onClick={() => player.toggleFavorite(track)}>
          <Heart size={20} fill={liked ? 'currentColor' : 'none'} color={liked ? 'var(--aura-primary)' : 'currentColor'} />
        </button>
      )}
      <button className="aura-icon-btn" aria-label={`More options for ${track.title}`} onClick={() => menu.open(track, menuExtras)}>
        <MoreVertical size={20} />
      </button>
    </div>
  );
}

/** Three bouncing bars: this song is playing. */
export function Bars() {
  return <span className="aura-bars"><i /><i /><i /></span>;
}

/** Where a catalog card leads: albums/playlists/artists open a page, songs play. */
export const browsePath = (item: DiscoveryCatalogItem) => {
  const id = item.endpoint?.browseId;
  if (!id) return null;
  const params = item.endpoint?.params ? `?params=${encodeURIComponent(item.endpoint.params)}` : '';
  return `/browse/${item.type}/${encodeURIComponent(id)}${params}`;
};

export function MediaCard({ item, queue, wide = false }: { item: DiscoveryCatalogItem; queue: Track[]; wide?: boolean }) {
  const player = usePlayer();
  const size = wide ? 208 : 152;
  const art = item.track?.albumArt || item.artworkUrl;
  const round = item.type === 'artist';
  const subtitle = item.track?.artist || ({ album: 'Album', playlist: 'Playlist', artist: 'Artist' } as Record<string, string>)[item.type] || '';
  const body = (
    <>
      <Artwork src={art} alt="" size={size} radius="var(--radius-md)" round={round} placeholder={item.id} />
      <div className="type-title-small aura-ellipsis" style={{ marginTop: 8, textAlign: round ? 'center' : 'left' }}>{item.title}</div>
      <div className="type-body-small aura-muted aura-ellipsis" style={{ textAlign: round ? 'center' : 'left' }}>{subtitle}</div>
    </>
  );
  if (item.track) {
    const track = item.track;
    return <button className="aura-card-item" style={{ width: size }} onClick={() => player.playTrack(track, queue)}>{body}</button>;
  }
  const path = browsePath(item);
  return path ? <Link className="aura-card-item" style={{ width: size, display: 'block' }} to={path}>{body}</Link> : <div style={{ width: size }}>{body}</div>;
}

export function SkeletonRows({ count = 6 }: { count?: number }) {
  return (
    <div style={{ display: 'grid', gap: 12 }} aria-busy="true" aria-label="Loading">
      {Array.from({ length: count }, (_, i) => (
        <div key={i} className="aura-row">
          <div className="aura-skeleton" style={{ width: 52, height: 52 }} />
          <div style={{ flex: 1, display: 'grid', gap: 6 }}>
            <div className="aura-skeleton" style={{ height: 14, width: '60%' }} />
            <div className="aura-skeleton" style={{ height: 12, width: '35%' }} />
          </div>
        </div>
      ))}
    </div>
  );
}

export function EmptyState({ icon, title, message, action }: { icon: ReactNode; title: string; message: string; action?: ReactNode }) {
  return (
    <div style={{ textAlign: 'center', padding: '48px 16px', display: 'grid', justifyItems: 'center', gap: 8 }}>
      <div style={{ width: 64, height: 64, borderRadius: '50%', display: 'grid', placeItems: 'center', background: 'var(--aura-surface-container)', color: 'var(--aura-primary)' }}>{icon}</div>
      <div className="type-title-large">{title}</div>
      <div className="type-body-medium aura-muted" style={{ maxWidth: 360 }}>{message}</div>
      {action}
    </div>
  );
}

export const formatTime = (seconds: number) => {
  if (!Number.isFinite(seconds) || seconds < 0) return '0:00';
  const s = Math.floor(seconds);
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
};
