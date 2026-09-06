import { useState } from 'react';
import { Play, Pause, Clock, Music } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Track, Playlist } from '@/data/mockMusic';
import type { DiscoveryContext } from '@/listening/listeningTypes';
import { discoveryContextForPlaylist } from '@/listening/discoveryAttribution';
import { Artwork } from './Artwork';

interface PlaylistViewProps {
  playlist: Playlist;
  currentTrack?: Track;
  isPlaying: boolean;
  onTrackSelect: (track: Track, tracks: Track[], discoveryContext?: DiscoveryContext) => void;
  onPlayPause: () => void;
}

const PlaylistView = ({
  playlist,
  currentTrack,
  isPlaying,
  onTrackSelect,
  onPlayPause,
}: PlaylistViewProps) => {
  const [hoveredTrack, setHoveredTrack] = useState<string | null>(null);

  const formatDuration = (seconds: number) => {
    const mins = Math.floor(seconds / 60);
    const secs = seconds % 60;
    return `${mins}:${secs.toString().padStart(2, '0')}`;
  };

  const totalDuration = playlist.tracks.reduce((total, track) => total + track.duration, 0);

  return (
    <div className="glass-card rounded-3xl p-8 max-w-4xl mx-auto fade-in-up">
      {/* Playlist Header */}
      <div className="flex items-center gap-6 mb-8">
        <div className="w-48 h-48 rounded-2xl bg-gradient-primary shadow-elegant flex items-center justify-center">
          <Music className="w-20 h-20 text-white/80" />
        </div>
        
        <div className="flex-1 space-y-4">
          <h1 className="text-4xl font-bold font-japanese bg-gradient-primary bg-clip-text text-transparent">
            {playlist.name}
          </h1>
          <p className="text-muted-foreground text-lg">
            {playlist.description}
          </p>
          
          <div className="flex items-center gap-6 text-sm text-muted-foreground">
            <span className="flex items-center gap-2">
              <Music className="w-4 h-4" />
              {playlist.tracks.length} tracks
            </span>
            <span className="flex items-center gap-2">
              <Clock className="w-4 h-4" />
              {Math.floor(totalDuration / 60)} min
            </span>
          </div>
          
          <Button
            variant="hero"
            size="lg"
            onClick={() => {
              if (currentTrack && playlist.tracks.includes(currentTrack)) {
                onPlayPause();
              } else {
                onTrackSelect(playlist.tracks[0], playlist.tracks, discoveryContextForPlaylist(playlist.id, 0));
              }
            }}
            className="rounded-full font-japanese"
          >
            {isPlaying && currentTrack && playlist.tracks.includes(currentTrack) ? (
              <>
                <Pause className="w-5 h-5" />
                Pause
              </>
            ) : (
              <>
                <Play className="w-5 h-5" />
                Play All
              </>
            )}
          </Button>
        </div>
      </div>

      {/* Track List */}
      <div className="space-y-2">
        <h2 className="text-xl font-medium font-japanese mb-4">Tracks</h2>
        
        {playlist.tracks.map((track, index) => {
          const isCurrentTrack = currentTrack?.id === track.id;
          
          return (
            <div
              key={track.id}
              className={`group flex items-center gap-4 p-4 rounded-xl transition-all duration-300 cursor-pointer ${
                isCurrentTrack
                  ? 'bg-gradient-primary/10 border border-primary/20'
                  : hoveredTrack === track.id
                  ? 'bg-white/5'
                  : 'hover:bg-white/5'
              }`}
              onClick={() => onTrackSelect(track, playlist.tracks, discoveryContextForPlaylist(playlist.id, index))}
              onMouseEnter={() => setHoveredTrack(track.id)}
              onMouseLeave={() => setHoveredTrack(null)}
            >
              {/* Track Number / Play Button */}
              <div className="w-10 flex items-center justify-center">
                {hoveredTrack === track.id || isCurrentTrack ? (
                  <Button
                    variant="ghost"
                    size="icon"
                    className="w-8 h-8 rounded-full"
                    onClick={(e) => {
                      e.stopPropagation();
                      if (isCurrentTrack) {
                        onPlayPause();
                      } else {
                        onTrackSelect(track, playlist.tracks, discoveryContextForPlaylist(playlist.id, index));
                      }
                    }}
                  >
                    {isCurrentTrack && isPlaying ? (
                      <Pause className="w-4 h-4" />
                    ) : (
                      <Play className="w-4 h-4" />
                    )}
                  </Button>
                ) : (
                  <span className="text-muted-foreground text-sm font-mono">
                    {(index + 1).toString().padStart(2, '0')}
                  </span>
                )}
              </div>

              {/* Album Art */}
              <Artwork src={track.albumArt} alt="" className="w-12 h-12 rounded-lg object-cover shadow-md" />

              {/* Track Info */}
              <div className="flex-1 min-w-0">
                <h4 className={`font-medium truncate ${
                  isCurrentTrack ? 'text-primary' : 'text-foreground'
                }`}>
                  {track.title}
                </h4>
                <p className="text-sm text-muted-foreground truncate">
                  {track.artist}
                </p>
              </div>

              {/* Album */}
              <div className="hidden md:block w-48">
                <p className="text-sm text-muted-foreground truncate">
                  {track.album}
                </p>
              </div>

              {/* Duration */}
              <div className="w-16 text-right">
                <span className="text-sm text-muted-foreground font-mono">
                  {formatDuration(track.duration)}
                </span>
              </div>

              {/* Audio Visualizer for current track */}
              {isCurrentTrack && isPlaying && (
                <div className="flex items-center gap-0.5 ml-4">
                  {Array.from({ length: 4 }).map((_, i) => (
                    <div
                      key={i}
                      className="w-0.5 bg-primary rounded-full animate-pulse"
                      style={{
                        height: `${Math.random() * 12 + 4}px`,
                        animationDelay: `${i * 0.1}s`,
                        animationDuration: '1s',
                      }}
                    />
                  ))}
                </div>
              )}
            </div>
          );
        })}
      </div>
    </div>
  );
};

export default PlaylistView;
