import { useState } from 'react';
import { Dialog, DialogContent, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { ScrollArea } from '@/components/ui/scroll-area';
import { 
  Search, 
  Lock, 
  Users, 
  Heart,
  Play,
  Plus,
  Eye,
  Calendar,
  Music,
  Loader2,
  AlertCircle
} from 'lucide-react';
import { useToast } from '@/hooks/use-toast';
import { MoodifyIdentity } from '@/hooks/useAnonymousIdentity';
import { SharedPlaylist, usePlaylistSharing } from '@/hooks/usePlaylistSharing';
import { Track } from '@/data/mockMusic';
import { Artwork } from '@/components/Artwork';
import type { DiscoveryContext } from '@/listening/listeningTypes';
import { discoveryContextForPlaylist } from '@/listening/discoveryAttribution';

interface SharedPlaylistAccessProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onPlayPlaylist: (tracks: Track[], discoveryContext?: DiscoveryContext) => void;
  onAddToLibrary: (playlist: SharedPlaylist) => void;
  currentUser: { name: string; avatar?: string; identity?: MoodifyIdentity };
}

export const SharedPlaylistAccess = ({
  open,
  onOpenChange,
  onPlayPlaylist,
  onAddToLibrary,
  currentUser,
}: SharedPlaylistAccessProps) => {
  const { toast } = useToast();
  const { state, accessSharedPlaylist, likePlaylist } = usePlaylistSharing(currentUser);
  
  const [shareCode, setShareCode] = useState('');
  const [password, setPassword] = useState('');
  const [isAccessing, setIsAccessing] = useState(false);
  const [accessedPlaylist, setAccessedPlaylist] = useState<SharedPlaylist | null>(null);
  const [showPassword, setShowPassword] = useState(false);

  const handleAccessPlaylist = async () => {
    if (!shareCode.trim()) {
      toast({
        title: 'Enter Share Code',
        description: 'Please enter a valid share code',
        variant: 'destructive',
      });
      return;
    }

    setIsAccessing(true);
    try {
      const playlist = await accessSharedPlaylist(shareCode.trim(), password || undefined);
      if (playlist) {
        setAccessedPlaylist(playlist);
        setShareCode('');
        setPassword('');
        setShowPassword(false);
      }
    } catch (error) {
      console.error('Failed to access playlist:', error);
    } finally {
      setIsAccessing(false);
    }
  };

  const handleLikePlaylist = (playlist: SharedPlaylist) => {
    likePlaylist(playlist.shareId);
  };

  const formatDate = (date: Date) => {
    return new Date(date).toLocaleDateString(undefined, {
      year: 'numeric',
      month: 'short',
      day: 'numeric',
    });
  };

  const formatDuration = (tracks: Track[]) => {
    const totalSeconds = tracks.reduce((sum, track) => sum + track.duration, 0);
    const hours = Math.floor(totalSeconds / 3600);
    const minutes = Math.floor((totalSeconds % 3600) / 60);
    
    if (hours > 0) {
      return `${hours}h ${minutes}m`;
    }
    return `${minutes}m`;
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-4xl max-h-[90vh] overflow-hidden">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            <Search className="w-5 h-5" />
            Access Shared Playlist
          </DialogTitle>
        </DialogHeader>

        <div className="space-y-6">
          {/* Access by Code */}
          <Card>
            <CardHeader>
              <CardTitle className="text-lg">Enter Share Code</CardTitle>
            </CardHeader>
            <CardContent className="space-y-4">
              <div className="space-y-2">
                <Label htmlFor="shareCode">Share Code</Label>
                <div className="flex gap-2">
                  <Input
                    id="shareCode"
                    placeholder="Enter 6-digit code (e.g., ABC123)"
                    value={shareCode}
                    onChange={(e) => setShareCode(e.target.value.toUpperCase())}
                    className="font-mono text-center text-lg"
                    maxLength={6}
                  />
                  <Button
                    onClick={handleAccessPlaylist}
                    disabled={isAccessing || !shareCode.trim()}
                  >
                    {isAccessing ? (
                      <Loader2 className="w-4 h-4 animate-spin" />
                    ) : (
                      'Access'
                    )}
                  </Button>
                </div>
              </div>

              {showPassword && (
                <div className="space-y-2">
                  <Label htmlFor="password">Password</Label>
                  <Input
                    id="password"
                    type="password"
                    placeholder="Enter password"
                    value={password}
                    onChange={(e) => setPassword(e.target.value)}
                  />
                </div>
              )}

              {!showPassword && (
                <Button
                  variant="ghost"
                  size="sm"
                  onClick={() => setShowPassword(true)}
                  className="text-muted-foreground"
                >
                  <Lock className="w-4 h-4 mr-1" />
                  Playlist is password protected?
                </Button>
              )}
            </CardContent>
          </Card>

          {/* Accessed Playlist */}
          {accessedPlaylist && (
            <Card className="border-primary/20 bg-gradient-to-r from-primary/5 to-transparent">
              <CardHeader>
                <div className="flex items-start justify-between">
                  <div className="space-y-2">
                    <CardTitle className="text-xl">{accessedPlaylist.name}</CardTitle>
                    <div className="flex items-center gap-2 text-sm text-muted-foreground">
                      <span>by {accessedPlaylist.sharedBy.name}</span>
                      <span>•</span>
                      <span>{accessedPlaylist.tracks.length} songs</span>
                      <span>•</span>
                      <span>{formatDuration(accessedPlaylist.tracks)}</span>
                    </div>
                    <div className="flex gap-2">
                      {accessedPlaylist.isPublic && (
                        <Badge variant="secondary">
                          <Eye className="w-3 h-3 mr-1" />
                          Public
                        </Badge>
                      )}
                      {accessedPlaylist.collaborators.length > 0 && (
                        <Badge variant="secondary">
                          <Users className="w-3 h-3 mr-1" />
                          {accessedPlaylist.collaborators.length} collaborators
                        </Badge>
                      )}
                      <Badge variant="outline">
                        <Calendar className="w-3 h-3 mr-1" />
                        {formatDate(accessedPlaylist.sharedAt)}
                      </Badge>
                    </div>
                  </div>
                  <div className="flex flex-col gap-2">
                    <Button
                      onClick={() => onPlayPlaylist(accessedPlaylist.tracks, discoveryContextForPlaylist(accessedPlaylist.shareId, 0))}
                      className="min-w-[100px]"
                    >
                      <Play className="w-4 h-4 mr-2" />
                      Play All
                    </Button>
                    <Button
                      variant="outline"
                      onClick={() => onAddToLibrary(accessedPlaylist)}
                    >
                      <Plus className="w-4 h-4 mr-2" />
                      Add to Library
                    </Button>
                    <Button
                      variant="ghost"
                      size="sm"
                      onClick={() => handleLikePlaylist(accessedPlaylist)}
                      className="text-muted-foreground hover:text-red-500"
                    >
                      <Heart className="w-4 h-4 mr-1" />
                      {accessedPlaylist.likes}
                    </Button>
                  </div>
                </div>
              </CardHeader>
              <CardContent>
                <ScrollArea className="h-64">
                  <div className="space-y-2">
                    {accessedPlaylist.tracks.map((track, index) => (
                      <div
                        key={track.id}
                        className="flex items-center gap-3 p-2 rounded-md hover:bg-accent/50 transition-colors"
                      >
                        <span className="text-sm text-muted-foreground w-8 text-center">
                          {index + 1}
                        </span>
                        <Artwork src={track.albumArt} alt="" className="w-10 h-10 rounded object-cover" />
                        <div className="flex-1 min-w-0">
                          <p className="font-medium truncate">{track.title}</p>
                          <p className="text-sm text-muted-foreground truncate">
                            {track.artist} • {track.album}
                          </p>
                        </div>
                        <span className="text-sm text-muted-foreground">
                          {Math.floor(track.duration / 60)}:{(track.duration % 60).toString().padStart(2, '0')}
                        </span>
                      </div>
                    ))}
                  </div>
                </ScrollArea>
              </CardContent>
            </Card>
          )}

          {/* Discovered Playlists */}
          {state.discoveredPlaylists.length > 0 && (
            <Card>
              <CardHeader>
                <CardTitle className="flex items-center gap-2">
                  <Music className="w-5 h-5" />
                  Recently Discovered
                </CardTitle>
              </CardHeader>
              <CardContent>
                <ScrollArea className="h-48">
                  <div className="space-y-3">
                    {state.discoveredPlaylists.map((playlist) => (
                      <div
                        key={playlist.shareId}
                        className="flex items-center gap-3 p-3 border rounded-lg hover:bg-accent/50 transition-colors"
                      >
                        <div className="w-12 h-12 bg-gradient-primary rounded-lg flex items-center justify-center">
                          <Music className="w-6 h-6 text-white" />
                        </div>
                        <div className="flex-1 min-w-0">
                          <p className="font-medium truncate">{playlist.name}</p>
                          <p className="text-sm text-muted-foreground">
                            by {playlist.sharedBy.name} • {playlist.tracks.length} songs
                          </p>
                        </div>
                        <div className="flex items-center gap-1 text-sm text-muted-foreground">
                          <Heart className="w-4 h-4" />
                          {playlist.likes}
                        </div>
                        <Button
                          size="sm"
                          variant="outline"
                          onClick={() => onPlayPlaylist(playlist.tracks, discoveryContextForPlaylist(playlist.shareId, 0))}
                        >
                          <Play className="w-4 h-4" />
                        </Button>
                      </div>
                    ))}
                  </div>
                </ScrollArea>
              </CardContent>
            </Card>
          )}

          {/* Help */}
          <Card className="border-blue-200 bg-blue-50/50 dark:border-blue-800 dark:bg-blue-950/20">
            <CardContent className="pt-6">
              <div className="flex gap-3">
                <AlertCircle className="w-5 h-5 text-blue-600 dark:text-blue-400 mt-0.5" />
                <div className="space-y-1">
                  <p className="text-sm font-medium text-blue-900 dark:text-blue-100">
                    How to access shared playlists:
                  </p>
                  <ul className="text-sm text-blue-700 dark:text-blue-300 space-y-1">
                    <li>• Enter the 6-digit share code you received</li>
                    <li>• If password protected, enter the password</li>
                    <li>• Scan QR codes with your camera app</li>
                    <li>• Click on shared links from social media</li>
                  </ul>
                </div>
              </div>
            </CardContent>
          </Card>
        </div>
      </DialogContent>
    </Dialog>
  );
};
