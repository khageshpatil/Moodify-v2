import { Track } from "@/data/mockMusic";
import { Card } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { ScrollArea } from "@/components/ui/scroll-area";
import { Heart, Play, Trash2 } from "lucide-react";
import { Artwork } from "@/components/Artwork";
import type { DiscoveryContext } from '@/listening/listeningTypes';
import { discoveryContextForLibrary } from '@/listening/discoveryAttribution';

interface FavoritesViewProps {
  favorites: Track[];
  currentTrack: Track | null;
  isPlaying: boolean;
  onTrackSelect: (track: Track, tracks: Track[], discoveryContext?: DiscoveryContext) => void;
  onRemoveFavorite: (track: Track) => void;
  onPlayAll: () => void;
}

export const FavoritesView = ({
  favorites,
  currentTrack,
  isPlaying,
  onTrackSelect,
  onRemoveFavorite,
  onPlayAll,
}: FavoritesViewProps) => {
  const formatDuration = (seconds: number) => {
    const mins = Math.floor(seconds / 60);
    const secs = Math.floor(seconds % 60);
    return `${mins}:${secs.toString().padStart(2, '0')}`;
  };

  return (
    <div className="w-full max-w-4xl mx-auto">
      <Card className="glass-card p-6">
        <div className="flex items-center justify-between mb-6">
          <div className="flex items-center gap-3">
            <Heart className="h-8 w-8 text-accent fill-accent" />
            <div>
              <h2 className="text-2xl font-bold text-white">Favorites</h2>
              <p className="text-white/60">
                {favorites.length} {favorites.length === 1 ? 'song' : 'songs'}
              </p>
            </div>
          </div>
          {favorites.length > 0 && (
            <Button onClick={onPlayAll} className="glass-button">
              <Play className="h-4 w-4 mr-2" />
              Play All
            </Button>
          )}
        </div>

        <ScrollArea className="player-safe-scroll">
          {favorites.length === 0 ? (
            <div className="flex flex-col items-center justify-center py-12 text-white/60">
              <Heart className="h-16 w-16 mb-4 opacity-40" />
              <p className="text-lg">No favorites yet</p>
              <p className="text-sm mt-2">Heart songs to add them here</p>
            </div>
          ) : (
            <div className="space-y-2">
              {favorites.map((track) => {
                const isCurrentTrack = currentTrack?.id === track.id;
                
                return (
                  <div
                    key={track.id}
                    className={`glass-card p-4 flex items-center gap-4 transition-all hover:bg-white/10 group cursor-pointer ${
                      isCurrentTrack ? 'ring-2 ring-accent' : ''
                    }`}
                    onClick={() => onTrackSelect(track, favorites, discoveryContextForLibrary('favorites', favorites.indexOf(track)))}
                  >
                    {track.albumArt && (
                      <Artwork src={track.albumArt} alt="" className="w-14 h-14 rounded object-cover" />
                    )}

                    <div className="flex-1 min-w-0">
                      <p className="font-medium text-white truncate">
                        {track.title}
                      </p>
                      <p className="text-sm text-white/60 truncate">
                        {track.artist}
                      </p>
                    </div>

                    <div className="flex items-center gap-3">
                      <span className="text-sm text-white/60">
                        {formatDuration(track.duration || 0)}
                      </span>
                      <Button
                        variant="ghost"
                        size="icon"
                        onClick={(e) => {
                          e.stopPropagation();
                          onRemoveFavorite(track);
                        }}
                        className="opacity-0 group-hover:opacity-100 transition-opacity text-accent hover:text-accent/80"
                      >
                        <Trash2 className="h-4 w-4" />
                      </Button>
                    </div>
                  </div>
                );
              })}
            </div>
          )}
        </ScrollArea>
      </Card>
    </div>
  );
};
