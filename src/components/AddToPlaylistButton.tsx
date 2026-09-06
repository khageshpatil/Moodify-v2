import { useState } from 'react';
import { Plus } from 'lucide-react';
import { Track } from '@/data/mockMusic';
import { AddToPlaylistModal } from './AddToPlaylistModal';
import { Playlist } from '@/hooks/useMusicPlayer';

interface AddToPlaylistButtonProps {
  track: Track;
  variant?: 'icon' | 'button';
  className?: string;
  playlists: Playlist[];
  onAddToPlaylist: (playlistId: string, track: Track) => void;
  onCreatePlaylist: (name: string) => void;
}

export const AddToPlaylistButton = ({
  track,
  variant = 'icon',
  className = '',
  playlists,
  onAddToPlaylist,
  onCreatePlaylist,
}: AddToPlaylistButtonProps) => {
  const [showModal, setShowModal] = useState(false);

  const handleClick = (e: React.MouseEvent) => {
    e.stopPropagation(); // Prevent triggering parent click events
    setShowModal(true);
  };

  if (variant === 'button') {
    return (
      <>
        <button
          onClick={handleClick}
          className={`btn-ghost flex items-center gap-2 ${className}`}
        >
          <Plus className="h-4 w-4" />
          <span>Add to Playlist</span>
        </button>
        <AddToPlaylistModal
          open={showModal}
          onOpenChange={setShowModal}
          track={track}
          playlists={playlists}
          onAddToPlaylist={onAddToPlaylist}
          onCreatePlaylist={onCreatePlaylist}
        />
      </>
    );
  }

  return (
    <>
      <button
        onClick={handleClick}
        className={`p-2 rounded-full transition-all duration-300 ${className}`}
        style={{
          background: 'var(--surface-frost)',
          border: '1px solid var(--stroke-subtle)',
        }}
        onMouseEnter={(e) => {
          e.currentTarget.style.background = 'var(--surface-frost-hover)';
          e.currentTarget.style.borderColor = 'var(--stroke-glow)';
          e.currentTarget.style.transform = 'scale(1.05)';
        }}
        onMouseLeave={(e) => {
          e.currentTarget.style.background = 'var(--surface-frost)';
          e.currentTarget.style.borderColor = 'var(--stroke-subtle)';
          e.currentTarget.style.transform = 'scale(1)';
        }}
        title="Add to playlist"
        aria-label="Add to playlist"
      >
        <Plus className="h-4 w-4" style={{ color: 'var(--text-secondary)' }} />
      </button>
      <AddToPlaylistModal
        open={showModal}
        onOpenChange={setShowModal}
        track={track}
        playlists={playlists}
        onAddToPlaylist={onAddToPlaylist}
        onCreatePlaylist={onCreatePlaylist}
      />
    </>
  );
};


