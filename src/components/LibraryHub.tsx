import { Clock3, Heart, ListMusic, Music2, Play, Plus, Sparkles, Upload } from 'lucide-react';
import type { Track } from '@/data/mockMusic';
import type { HistoryItem, Playlist } from '@/hooks/useMusicPlayer';
import { TrackRow } from './TrackRow';
import type { DiscoveryContext } from '@/listening/listeningTypes';
import { discoveryContextForLibrary, discoveryContextForPlaylist } from '@/listening/discoveryAttribution';

interface LibraryHubProps {
  favorites: Track[];
  recentlyPlayed: Track[];
  queue: Track[];
  history: HistoryItem[];
  playlists: Playlist[];
  onPlay: (track: Track, tracks?: Track[], discoveryContext?: DiscoveryContext) => void;
  onQueue: (track: Track) => void;
  onOpen: (view: 'favorites' | 'playlists' | 'history' | 'queue' | 'discover') => void;
  onImport: () => void;
}

export const LibraryHub = ({ favorites, recentlyPlayed, queue, history, playlists, onPlay, onQueue, onOpen, onImport }: LibraryHubProps) => (
  <div className="library-hub">
    <div className="page-intro"><span className="eyebrow">your library</span><h1>A place for the things<br /><em>worth keeping.</em></h1><p>Favorites, playlists, and the trail of what you have been listening to.</p></div>
    <div className="library-shortcuts">
      {[
        { label: 'Favorites', count: favorites.length, icon: Heart, view: 'favorites' as const },
        { label: 'Playlists', count: playlists.length, icon: Music2, view: 'playlists' as const },
        { label: 'History', count: history.length, icon: Clock3, view: 'history' as const },
        { label: 'Queue', count: queue.length, icon: ListMusic, view: 'queue' as const },
        { label: 'Explore', icon: Sparkles, view: 'discover' as const },
      ].map(({ label, count, icon: Icon, view }) => <button key={label} className="library-shortcut" onClick={() => onOpen(view)}><Icon size={18} aria-hidden="true" /><span>{label}</span>{count !== undefined ? <small>{count}</small> : null}</button>)}
      <button className="library-shortcut shortcut-import" onClick={onImport}><Upload size={18} aria-hidden="true" /><span>Import playlist</span></button>
    </div>
    <section className="home-section" aria-labelledby="saved-heading">
      <div className="section-heading"><div><span className="section-index">01</span><h2 id="saved-heading">Saved for later</h2></div><button className="text-button" onClick={() => onOpen('favorites')}>See all <span aria-hidden="true">→</span></button></div>
      {favorites.length ? <div className="track-list">{favorites.slice(0, 5).map((track, index) => <TrackRow key={track.id} track={track} index={index} onPlay={(item) => onPlay(item, favorites, discoveryContextForLibrary('favorites', index))} onQueue={onQueue} />)}</div> : <div className="empty-state"><Heart size={22} aria-hidden="true" /><strong>Your listening story starts here.</strong><span>Save songs you want to find again.</span></div>}
    </section>
    <section className="home-section" aria-labelledby="playlist-heading">
      <div className="section-heading"><div><span className="section-index">02</span><h2 id="playlist-heading">Your shelves</h2></div><button className="text-button" onClick={() => onOpen('playlists')}>Manage <span aria-hidden="true">→</span></button></div>
      {playlists.length ? <div className="shelf-grid">{playlists.slice(0, 3).map((playlist) => <button key={playlist.id} className="shelf-card" onClick={() => playlist.tracks[0] && onPlay(playlist.tracks[0], playlist.tracks, discoveryContextForPlaylist(playlist.id, 0))}><span className="shelf-art"><Music2 size={24} aria-hidden="true" /></span><strong>{playlist.name}</strong><small>{playlist.tracks.length} tracks <Play size={12} fill="currentColor" aria-hidden="true" /></small></button>)}</div> : <div className="empty-state empty-state-inline"><Plus size={22} aria-hidden="true" /><strong>No playlists yet.</strong><span>Give the songs you love a place to live.</span></div>}
    </section>
  </div>
);
