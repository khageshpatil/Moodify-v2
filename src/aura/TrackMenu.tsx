import { Disc3, Heart, ListEnd, ListMusic, ListPlus, Plus, Send, Trash2, User, X } from 'lucide-react';
import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { useNavigate } from 'react-router-dom';
import type { Track } from '@/data/mockMusic';
import { usePlayer } from './PlayerContext';
import { useSocial } from './social/SocialProvider';
import { Artwork } from './ui';

/** Extra actions a screen can add, e.g. "Remove from this playlist". */
export interface TrackMenuExtra { label: string; icon?: ReactNode; danger?: boolean; run: () => void }
interface TrackMenuApi { open(track: Track, extras?: TrackMenuExtra[]): void; say(message: string): void }

const TrackMenuContext = createContext<TrackMenuApi | null>(null);

/** Opens the track actions sheet (falls back to nothing outside the provider, e.g. in tests). */
export function useTrackMenu(): TrackMenuApi {
  return useContext(TrackMenuContext) ?? { open: () => {}, say: () => {} };
}

/** A small text prompt (playlist names), in place of the browser's prompt(). */
export function TextDialog({ title, label, initial = '', confirm, onSubmit, onClose }: {
  title: string; label: string; initial?: string; confirm: string; onSubmit: (value: string) => void; onClose: () => void;
}) {
  const [value, setValue] = useState(initial);
  return (
    <div className="aura-dialog-backdrop" onClick={onClose}>
      <form className="aura-dialog" role="dialog" aria-label={title} onClick={(e) => e.stopPropagation()}
        onSubmit={(e) => { e.preventDefault(); if (value.trim()) onSubmit(value.trim()); }}>
        <div className="type-title-large">{title}</div>
        <input className="aura-field" autoFocus value={value} maxLength={60} aria-label={label} placeholder={label} onChange={(e) => setValue(e.target.value)} />
        <div className="aura-row" style={{ justifyContent: 'flex-end' }}>
          <button type="button" className="aura-btn-text" onClick={onClose}>Cancel</button>
          <button type="submit" className="aura-btn aura-btn-primary" disabled={!value.trim()}>{confirm}</button>
        </div>
      </form>
    </div>
  );
}

export function TrackMenuProvider({ children }: { children: ReactNode }) {
  const [target, setTarget] = useState<{ track: Track; extras: TrackMenuExtra[] } | null>(null);
  const [toast, setToast] = useState<string | null>(null);
  useEffect(() => { if (!toast) return; const t = setTimeout(() => setToast(null), 3000); return () => clearTimeout(t); }, [toast]);
  const open = useCallback((track: Track, extras: TrackMenuExtra[] = []) => setTarget({ track, extras }), []);
  const api = useMemo(() => ({ open, say: setToast }), [open]);
  return (
    <TrackMenuContext.Provider value={api}>
      {children}
      {target && <TrackSheet track={target.track} extras={target.extras} onClose={() => setTarget(null)} say={setToast} />}
      {toast && <div className="aura-toast" role="status">{toast}</div>}
    </TrackMenuContext.Provider>
  );
}

function TrackSheet({ track, extras, onClose, say }: { track: Track; extras: TrackMenuExtra[]; onClose: () => void; say: (m: string) => void }) {
  const player = usePlayer();
  const social = useSocial();
  const navigate = useNavigate();
  const [page, setPage] = useState<'main' | 'playlists' | 'friends' | 'new'>('main');
  const liked = player.isFavorite(track.id);
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);
  const done = (message: string) => { say(message); onClose(); };
  const friends = social.lists.friends;

  if (page === 'new') {
    return (
      <TextDialog title="New playlist" label="Playlist name" confirm="Create" onClose={onClose}
        onSubmit={(name) => { player.createPlaylist(name, [track]); done(`Added to "${name}"`); }} />
    );
  }

  const Item = ({ icon, label, onClick, danger = false }: { icon: ReactNode; label: string; onClick: () => void; danger?: boolean }) => (
    <button className="aura-menu-item" onClick={onClick} style={danger ? { color: 'var(--aura-error)' } : undefined}>{icon}<span>{label}</span></button>
  );

  return (
    <div className="aura-dialog-backdrop aura-sheet-backdrop" onClick={onClose}>
      <div className="aura-sheet" role="dialog" aria-label={`Options for ${track.title}`} onClick={(e) => e.stopPropagation()}>
        <div className="aura-row" style={{ padding: '4px 8px 12px' }}>
          <Artwork src={track.albumArt} size={52} placeholder={track.id} />
          <div style={{ flex: 1, minWidth: 0 }}>
            <div className="type-title-medium aura-ellipsis">{track.title}</div>
            <div className="type-body-small aura-muted aura-ellipsis">{track.artist}</div>
          </div>
          <button className="aura-icon-btn" aria-label="Close" onClick={onClose}><X size={20} /></button>
        </div>
        {page === 'main' && (
          <div className="aura-menu">
            <Item icon={<ListPlus size={20} />} label="Play next" onClick={() => { player.queueNext(track); done('Playing next'); }} />
            <Item icon={<ListEnd size={20} />} label="Add to queue" onClick={() => { player.addToQueue(track); done('Added to queue'); }} />
            <Item icon={<ListMusic size={20} />} label="Add to playlist" onClick={() => setPage('playlists')} />
            <Item icon={<Heart size={20} fill={liked ? 'currentColor' : 'none'} />} label={liked ? 'Remove from liked songs' : 'Like'}
              onClick={() => { player.toggleFavorite(track); done(liked ? 'Removed from liked songs' : 'Added to liked songs'); }} />
            {track.artistId && <Item icon={<User size={20} />} label="Go to artist" onClick={() => { onClose(); navigate(`/browse/artist/${encodeURIComponent(track.artistId!)}`); }} />}
            {track.albumId && <Item icon={<Disc3 size={20} />} label="Go to album" onClick={() => { onClose(); navigate(`/browse/album/${encodeURIComponent(track.albumId!)}`); }} />}
            {social.code && friends.length > 0 && <Item icon={<Send size={20} />} label="Send to a friend" onClick={() => setPage('friends')} />}
            {extras.map((extra) => (
              <Item key={extra.label} icon={extra.icon ?? <Trash2 size={20} />} label={extra.label} danger={extra.danger} onClick={() => { extra.run(); onClose(); }} />
            ))}
          </div>
        )}
        {page === 'playlists' && (
          <div className="aura-menu">
            <Item icon={<Plus size={20} />} label="New playlist" onClick={() => setPage('new')} />
            {player.playlists.map((playlist) => {
              const has = playlist.tracks.some((t) => t.id === track.id);
              return (
                <Item key={playlist.id} icon={<ListMusic size={20} />} label={has ? `${playlist.name} (already in it)` : playlist.name}
                  onClick={() => { if (!has) player.addToPlaylist(playlist.id, track); done(has ? `Already in "${playlist.name}"` : `Added to "${playlist.name}"`); }} />
              );
            })}
          </div>
        )}
        {page === 'friends' && (
          <div className="aura-menu">
            {friends.map((friend) => (
              <Item key={friend.code} icon={<Send size={20} />} label={friend.displayName}
                onClick={() => { social.sendTrack(friend, track).then(() => done(`Sent to ${friend.displayName}`), (e: unknown) => done(e instanceof Error ? e.message : "Couldn't send it")); }} />
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
