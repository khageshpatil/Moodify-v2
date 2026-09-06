import { useState } from 'react';
import { Plus, Edit, Trash2, Music, X, Download, Share2, Users } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogTrigger } from '@/components/ui/dialog';
import { Track } from '@/data/mockMusic';
import { MoodifyIdentity } from '@/hooks/useAnonymousIdentity';
import { PlaylistSharingDialog } from './PlaylistSharingDialog';
import { SharedPlaylistAccess } from './SharedPlaylistAccess';
import { Artwork } from './Artwork';
import type { DiscoveryContext } from '@/listening/listeningTypes';
import { discoveryContextForPlaylist } from '@/listening/discoveryAttribution';

export interface Playlist {
  id: string;
  name: string;
  tracks: Track[];
  createdAt: Date;
}

interface PlaylistManagerProps {
  playlists: Playlist[];
  onCreatePlaylist: (name: string) => void;
  onDeletePlaylist: (id: string) => void;
  onRenamePlaylist: (id: string, newName: string) => void;
  onRemoveFromPlaylist: (playlistId: string, trackId: string) => void;
  onPlayPlaylist: (playlist: Playlist) => void;
  onExportPlaylist?: (playlistId: string) => void;
  currentTrack?: Track | null;
  currentUser: { name: string; avatar?: string; identity?: MoodifyIdentity };
  onPlayTracks?: (tracks: Track[], discoveryContext?: DiscoveryContext) => void;
}

export const PlaylistManager = ({
  playlists,
  onCreatePlaylist,
  onDeletePlaylist,
  onRenamePlaylist,
  onRemoveFromPlaylist,
  onPlayPlaylist,
  onExportPlaylist,
  currentTrack,
  currentUser,
  onPlayTracks
}: PlaylistManagerProps) => {
  const [newPlaylistName, setNewPlaylistName] = useState('');
  const [editingPlaylist, setEditingPlaylist] = useState<string | null>(null);
  const [editName, setEditName] = useState('');
  const [isCreateDialogOpen, setIsCreateDialogOpen] = useState(false);
  const [sharingPlaylist, setSharingPlaylist] = useState<Playlist | null>(null);
  const [showSharedAccess, setShowSharedAccess] = useState(false);

  const handleCreatePlaylist = () => {
    if (newPlaylistName.trim()) {
      onCreatePlaylist(newPlaylistName.trim());
      setNewPlaylistName('');
      setIsCreateDialogOpen(false);
    }
  };

  const handleRename = (playlistId: string) => {
    if (editName.trim()) {
      onRenamePlaylist(playlistId, editName.trim());
      setEditingPlaylist(null);
      setEditName('');
    }
  };

  const startEdit = (playlist: Playlist) => {
    setEditingPlaylist(playlist.id);
    setEditName(playlist.name);
  };

  const handleAddToLibrary = (sharedPlaylist: any) => {
    // Convert shared playlist to regular playlist and add to library
    onCreatePlaylist(sharedPlaylist.name);
    // In a real implementation, you'd also add the tracks
  };

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h2 className="text-xl font-bold">My Playlists</h2>
        <div className="flex gap-2">
          <Button 
            size="sm" 
            variant="outline"
            onClick={() => setShowSharedAccess(true)}
          >
            <Users className="h-4 w-4 mr-2" />
            Access Shared
          </Button>
          <Dialog open={isCreateDialogOpen} onOpenChange={setIsCreateDialogOpen}>
            <DialogTrigger asChild>
              <Button size="sm">
                <Plus className="h-4 w-4 mr-2" />
                New Playlist
              </Button>
            </DialogTrigger>
          <DialogContent>
            <DialogHeader>
              <DialogTitle>Create New Playlist</DialogTitle>
            </DialogHeader>
            <div className="space-y-4">
              <Input
                placeholder="Playlist name"
                value={newPlaylistName}
                onChange={(e) => setNewPlaylistName(e.target.value)}
                onKeyPress={(e) => e.key === 'Enter' && handleCreatePlaylist()}
              />
              <div className="flex gap-2">
                <Button onClick={handleCreatePlaylist} disabled={!newPlaylistName.trim()}>
                  Create
                </Button>
                <Button variant="outline" onClick={() => setIsCreateDialogOpen(false)}>
                  Cancel
                </Button>
              </div>
            </div>
          </DialogContent>
        </Dialog>
        </div>
      </div>

      <div className="grid gap-4">
        {playlists.map((playlist) => (
          <Card key={playlist.id}>
            <CardHeader className="pb-3">
              <div className="flex items-center justify-between">
                <div className="flex items-center gap-2">
                  {editingPlaylist === playlist.id ? (
                    <div className="flex items-center gap-2">
                      <Input
                        value={editName}
                        onChange={(e) => setEditName(e.target.value)}
                        onKeyPress={(e) => e.key === 'Enter' && handleRename(playlist.id)}
                        className="h-8"
                      />
                      <Button
                        size="sm"
                        onClick={() => handleRename(playlist.id)}
                      >
                        Save
                      </Button>
                      <Button
                        size="sm"
                        variant="ghost"
                        onClick={() => setEditingPlaylist(null)}
                      >
                        <X className="h-4 w-4" />
                      </Button>
                    </div>
                  ) : (
                    <>
                      <Music className="h-5 w-5" />
                      <CardTitle className="text-lg">{playlist.name}</CardTitle>
                    </>
                  )}
                </div>
                {editingPlaylist !== playlist.id && (
                  <div className="flex gap-2">
                    <Button
                      size="sm"
                      variant="outline"
                      onClick={() => onPlayPlaylist(playlist)}
                    >
                      Play All
                    </Button>
                    <Button
                      size="sm"
                      variant="ghost"
                      onClick={() => setSharingPlaylist(playlist)}
                    >
                      <Share2 className="h-4 w-4" />
                    </Button>
                    {onExportPlaylist && (
                      <Button
                        size="sm"
                        variant="ghost"
                        onClick={() => onExportPlaylist(playlist.id)}
                      >
                        <Download className="h-4 w-4" />
                      </Button>
                    )}
                    <Button
                      size="sm"
                      variant="ghost"
                      onClick={() => startEdit(playlist)}
                    >
                      <Edit className="h-4 w-4" />
                    </Button>
                    <Button
                      size="sm"
                      variant="ghost"
                      onClick={() => onDeletePlaylist(playlist.id)}
                    >
                      <Trash2 className="h-4 w-4" />
                    </Button>
                  </div>
                )}
              </div>
              <p className="text-sm text-muted-foreground">
                {playlist.tracks.length} songs
              </p>
            </CardHeader>
            <CardContent>
              {playlist.tracks.length > 0 ? (
                <div className="space-y-2">
                  {playlist.tracks.map((track, index) => (
                    <div
                      key={track.id}
                      className={`flex items-center gap-3 p-2 rounded-md hover:bg-accent/50 cursor-pointer ${
                        currentTrack?.id === track.id ? 'bg-accent' : ''
                      }`}
                      onClick={() => onPlayTracks?.(playlist.tracks, discoveryContextForPlaylist(playlist.id, index))}
                      role="button"
                      tabIndex={0}
                      onKeyDown={(event) => {
                        if (event.key === 'Enter' || event.key === ' ') {
                          event.preventDefault();
                          onPlayTracks?.(playlist.tracks, discoveryContextForPlaylist(playlist.id, index));
                        }
                      }}
                    >
                      <span className="text-sm text-muted-foreground w-6">
                        {index + 1}
                      </span>
                      <Artwork src={track.albumArt} alt="" className="w-10 h-10 rounded object-cover" fallback={<span className="w-10 h-10 rounded bg-muted" />} />
                      <div className="flex-1 min-w-0">
                        <p className="font-medium truncate">{track.title}</p>
                        <p className="text-sm text-muted-foreground truncate">{track.artist}</p>
                      </div>
                      <Button
                        size="sm"
                        variant="ghost"
                        onClick={(event) => {
                          event.stopPropagation();
                          onRemoveFromPlaylist(playlist.id, track.id);
                        }}
                      >
                        <X className="h-4 w-4" />
                      </Button>
                    </div>
                  ))}
                </div>
              ) : (
                <p className="text-muted-foreground text-center py-4">
                  No songs in this playlist yet
                </p>
              )}
            </CardContent>
          </Card>
        ))}
      </div>

      {playlists.length === 0 && (
        <Card>
          <CardContent className="text-center py-8">
            <Music className="h-12 w-12 mx-auto mb-4 text-muted-foreground" />
            <p className="text-muted-foreground">
              No playlists yet. Create your first playlist to get started!
            </p>
          </CardContent>
        </Card>
      )}

      {/* Playlist Sharing Dialog */}
      <PlaylistSharingDialog
        open={!!sharingPlaylist}
        onOpenChange={(open) => !open && setSharingPlaylist(null)}
        playlist={sharingPlaylist}
        currentUser={currentUser}
      />

      {/* Shared Playlist Access Dialog */}
      <SharedPlaylistAccess
        open={showSharedAccess}
        onOpenChange={setShowSharedAccess}
        onPlayPlaylist={onPlayTracks || (() => {})}
        onAddToLibrary={handleAddToLibrary}
        currentUser={currentUser}
      />
    </div>
  );
};
