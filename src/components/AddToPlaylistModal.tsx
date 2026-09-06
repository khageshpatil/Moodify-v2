import { useState } from 'react';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { ScrollArea } from '@/components/ui/scroll-area';
import { Plus, Music, Check, ListMusic } from 'lucide-react';
import { Track } from '@/data/mockMusic';
import { Playlist } from '@/hooks/useMusicPlayer';
import { useToast } from '@/hooks/use-toast';

interface AddToPlaylistModalProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  track: Track;
  playlists: Playlist[];
  onAddToPlaylist: (playlistId: string, track: Track) => void;
  onCreatePlaylist: (name: string) => void;
}

export const AddToPlaylistModal = ({
  open,
  onOpenChange,
  track,
  playlists,
  onAddToPlaylist,
  onCreatePlaylist,
}: AddToPlaylistModalProps) => {
  const [isCreating, setIsCreating] = useState(false);
  const [newPlaylistName, setNewPlaylistName] = useState('');
  const [selectedPlaylistId, setSelectedPlaylistId] = useState<string | null>(null);
  const { toast } = useToast();

  const handleAddToPlaylist = (playlistId: string) => {
    const playlist = playlists.find((p) => p.id === playlistId);
    
    // Check if track already exists in playlist
    if (playlist && playlist.tracks.some((t) => t.id === track.id)) {
      toast({
        title: 'Already in playlist',
        description: `"${track.title}" is already in ${playlist.name}`,
        variant: 'default',
      });
      return;
    }

    onAddToPlaylist(playlistId, track);
    setSelectedPlaylistId(playlistId);
    
    toast({
      title: 'Added to playlist',
      description: `"${track.title}" added to ${playlist?.name}`,
    });

    // Close modal after a short delay
    setTimeout(() => {
      onOpenChange(false);
      setSelectedPlaylistId(null);
    }, 800);
  };

  const handleCreatePlaylist = () => {
    if (!newPlaylistName.trim()) {
      toast({
        title: 'Error',
        description: 'Please enter a playlist name',
        variant: 'destructive',
      });
      return;
    }

    onCreatePlaylist(newPlaylistName.trim());
    
    toast({
      title: 'Playlist created',
      description: `"${newPlaylistName}" has been created`,
    });

    setNewPlaylistName('');
    setIsCreating(false);
    
    // Auto-add track to newly created playlist after a short delay
    setTimeout(() => {
      const newPlaylist = playlists[playlists.length - 1];
      if (newPlaylist) {
        onAddToPlaylist(newPlaylist.id, track);
      }
    }, 100);
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent 
        className="sm:max-w-md surface-float"
        style={{
          background: 'var(--surface-frost)',
          backdropFilter: 'var(--blur-heavy)',
          border: '1px solid var(--stroke-medium)',
        }}
      >
        <DialogHeader>
          <DialogTitle 
            className="text-xl font-medium flex items-center gap-2"
            style={{ color: 'var(--text-primary)' }}
          >
            <Plus className="h-5 w-5" style={{ color: 'var(--accent-lavender)' }} />
            Add to Playlist
          </DialogTitle>
          <DialogDescription style={{ color: 'var(--text-secondary)', fontWeight: 300 }}>
            Add "{track.title}" to a playlist
          </DialogDescription>
        </DialogHeader>

        <div className="space-y-4">
          {/* Create New Playlist Section */}
          {isCreating ? (
            <div 
              className="p-4 rounded-xl space-y-3"
              style={{
                background: 'var(--surface-frost-hover)',
                border: '1px solid var(--stroke-medium)',
              }}
            >
              <Label 
                htmlFor="playlist-name"
                className="text-sm font-medium"
                style={{ color: 'var(--text-primary)' }}
              >
                Playlist Name
              </Label>
              <Input
                id="playlist-name"
                value={newPlaylistName}
                onChange={(e) => setNewPlaylistName(e.target.value)}
                onKeyPress={(e) => e.key === 'Enter' && handleCreatePlaylist()}
                placeholder="My Awesome Playlist"
                className="search-input"
                autoFocus
              />
              <div className="flex gap-2">
                <Button
                  onClick={handleCreatePlaylist}
                  className="btn-primary flex-1"
                  disabled={!newPlaylistName.trim()}
                >
                  Create & Add
                </Button>
                <Button
                  onClick={() => {
                    setIsCreating(false);
                    setNewPlaylistName('');
                  }}
                  className="btn-ghost"
                  variant="outline"
                >
                  Cancel
                </Button>
              </div>
            </div>
          ) : (
            <button
              onClick={() => setIsCreating(true)}
              className="w-full p-4 rounded-xl flex items-center gap-3 transition-all duration-300"
              style={{
                background: 'var(--surface-frost)',
                border: '1px solid var(--stroke-subtle)',
              }}
              onMouseEnter={(e) => {
                e.currentTarget.style.background = 'var(--surface-frost-hover)';
                e.currentTarget.style.borderColor = 'var(--stroke-glow)';
              }}
              onMouseLeave={(e) => {
                e.currentTarget.style.background = 'var(--surface-frost)';
                e.currentTarget.style.borderColor = 'var(--stroke-subtle)';
              }}
            >
              <div
                className="w-12 h-12 rounded-xl flex items-center justify-center"
                style={{
                  background: 'var(--accent-subtle)',
                  border: '1px solid var(--stroke-glow)',
                }}
              >
                <Plus className="h-6 w-6" style={{ color: 'var(--accent-lavender)' }} />
              </div>
              <div className="flex-1 text-left">
                <p className="font-medium" style={{ color: 'var(--text-primary)' }}>
                  Create New Playlist
                </p>
                <p className="text-sm" style={{ color: 'var(--text-tertiary)' }}>
                  Start a fresh collection
                </p>
              </div>
            </button>
          )}

          {/* Existing Playlists */}
          {playlists.length > 0 && (
            <>
              <div className="flex items-center gap-2">
                <div className="flex-1 h-px" style={{ background: 'var(--stroke-subtle)' }} />
                <span className="text-xs uppercase tracking-wide" style={{ color: 'var(--text-tertiary)' }}>
                  Your Playlists
                </span>
                <div className="flex-1 h-px" style={{ background: 'var(--stroke-subtle)' }} />
              </div>

              <ScrollArea className="max-h-[300px] pr-4 custom-scrollbar">
                <div className="space-y-2">
                  {playlists.map((playlist) => {
                    const isAdded = selectedPlaylistId === playlist.id;
                    const alreadyExists = playlist.tracks.some((t) => t.id === track.id);

                    return (
                      <button
                        key={playlist.id}
                        onClick={() => !alreadyExists && handleAddToPlaylist(playlist.id)}
                        disabled={alreadyExists || isAdded}
                        className="w-full p-3 rounded-xl flex items-center gap-3 transition-all duration-300"
                        style={{
                          background: isAdded || alreadyExists ? 'var(--surface-frost-active)' : 'var(--surface-frost)',
                          border: `1px solid ${isAdded ? 'var(--stroke-glow)' : 'var(--stroke-subtle)'}`,
                          opacity: alreadyExists ? 0.5 : 1,
                          cursor: alreadyExists ? 'not-allowed' : 'pointer',
                        }}
                        onMouseEnter={(e) => {
                          if (!alreadyExists && !isAdded) {
                            e.currentTarget.style.background = 'var(--surface-frost-hover)';
                            e.currentTarget.style.borderColor = 'var(--stroke-medium)';
                          }
                        }}
                        onMouseLeave={(e) => {
                          if (!alreadyExists && !isAdded) {
                            e.currentTarget.style.background = 'var(--surface-frost)';
                            e.currentTarget.style.borderColor = 'var(--stroke-subtle)';
                          }
                        }}
                      >
                        <div
                          className="w-10 h-10 rounded-lg flex items-center justify-center flex-shrink-0"
                          style={{
                            background: 'var(--surface-frost-hover)',
                            border: '1px solid var(--stroke-subtle)',
                          }}
                        >
                          {isAdded ? (
                            <Check className="h-5 w-5" style={{ color: 'var(--accent-lavender)' }} />
                          ) : alreadyExists ? (
                            <Check className="h-5 w-5" style={{ color: 'var(--text-tertiary)' }} />
                          ) : (
                            <Music className="h-5 w-5" style={{ color: 'var(--text-secondary)' }} />
                          )}
                        </div>
                        <div className="flex-1 text-left min-w-0">
                          <p className="font-medium truncate" style={{ color: 'var(--text-primary)' }}>
                            {playlist.name}
                          </p>
                          <p className="text-sm truncate" style={{ color: 'var(--text-tertiary)' }}>
                            {playlist.tracks.length} {playlist.tracks.length === 1 ? 'song' : 'songs'}
                            {alreadyExists && ' • Already added'}
                          </p>
                        </div>
                        {!alreadyExists && !isAdded && (
                          <ListMusic className="h-4 w-4 flex-shrink-0" style={{ color: 'var(--text-tertiary)' }} />
                        )}
                      </button>
                    );
                  })}
                </div>
              </ScrollArea>
            </>
          )}

          {playlists.length === 0 && !isCreating && (
            <div className="text-center py-8">
              <Music 
                className="h-12 w-12 mx-auto mb-3 opacity-30" 
                style={{ color: 'var(--text-secondary)' }} 
              />
              <p className="text-sm" style={{ color: 'var(--text-secondary)' }}>
                No playlists yet. Create your first one!
              </p>
            </div>
          )}
        </div>
      </DialogContent>
    </Dialog>
  );
};


