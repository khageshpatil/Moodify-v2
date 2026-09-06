import { useState, useEffect } from 'react';
import { Play, Pause, SkipBack, SkipForward, Volume2, Shuffle, Repeat, Heart } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Slider } from '@/components/ui/slider';

interface Track {
  id: string;
  title: string;
  artist: string;
  album: string;
  albumArt: string;
  duration: number;
}

interface MusicPlayerProps {
  currentTrack: Track | null;
  isPlaying: boolean;
  currentTime: number;
  volume: number;
  shuffle: boolean;
  repeat: 'off' | 'all' | 'one';
  isFavorite: boolean;
  onPlayPause: () => void;
  onNext: () => void;
  onPrevious: () => void;
  onSeek: (time: number) => void;
  onVolumeChange: (volume: number) => void;
  onToggleShuffle: () => void;
  onToggleRepeat: () => void;
  onToggleFavorite: () => void;
}

const MusicPlayer = ({
  currentTrack,
  isPlaying,
  currentTime,
  volume,
  shuffle,
  repeat,
  isFavorite,
  onPlayPause,
  onNext,
  onPrevious,
  onSeek,
  onVolumeChange,
  onToggleShuffle,
  onToggleRepeat,
  onToggleFavorite,
}: MusicPlayerProps) => {

  const formatTime = (seconds: number) => {
    const mins = Math.floor(seconds / 60);
    const secs = Math.floor(seconds % 60);
    return `${mins}:${secs.toString().padStart(2, '0')}`;
  };

  // Audio visualizer bars
  const visualizerBars = Array.from({ length: 20 }, (_, i) => (
    <div
      key={i}
      className={`visualizer-bar w-1 bg-gradient-primary rounded-full transition-all duration-300 ${
        isPlaying ? 'animate-pulse' : 'h-1'
      }`}
      style={{
        animationDelay: `${i * 0.1}s`,
        height: isPlaying ? `${Math.random() * 16 + 4}px` : '4px',
      }}
    />
  ));

  if (!currentTrack) {
    return null;
  }

  return (
    <div className="fixed bottom-0 left-0 right-0 z-50 glass-card border-t border-white/10 p-4">
      <div className="max-w-7xl mx-auto flex items-center gap-4">
        {/* Current Track Info */}
        <div className="flex items-center gap-3 min-w-0 flex-1 max-w-xs">
          <div className="relative group">
            <img
              src={currentTrack.albumArt}
              alt={currentTrack.album}
              className="w-14 h-14 rounded-lg object-cover shadow-lg transition-all duration-300 group-hover:scale-105"
            />
            <div className="absolute inset-0 bg-black/20 rounded-lg opacity-0 group-hover:opacity-100 transition-opacity duration-300" />
          </div>
          
          <div className="min-w-0 flex-1">
            <h4 className="font-medium text-foreground truncate font-japanese">
              {currentTrack.title}
            </h4>
            <p className="text-sm text-muted-foreground truncate">
              {currentTrack.artist}
            </p>
          </div>
          
          <Button
            variant="ghost"
            size="sm"
            onClick={onToggleFavorite}
            className={`opacity-70 hover:opacity-100 transition-all ${
              isFavorite ? 'text-mood-love' : 'text-muted-foreground'
            }`}
          >
            <Heart className={`w-4 h-4 ${isFavorite ? 'fill-current' : ''}`} />
          </Button>
        </div>

        {/* Main Controls */}
        <div className="flex flex-col items-center gap-2 flex-1 max-w-2xl">
          <div className="flex items-center gap-2">
            <Button
              variant="ghost"
              size="sm"
              onClick={onToggleShuffle}
              className={`opacity-70 hover:opacity-100 transition-all ${
                shuffle ? 'text-primary' : 'text-muted-foreground'
              }`}
            >
              <Shuffle className="w-4 h-4" />
            </Button>
            
            <Button
              variant="ghost"
              size="sm"
              onClick={onPrevious}
              className="opacity-70 hover:opacity-100 transition-all"
            >
              <SkipBack className="w-5 h-5" />
            </Button>
            
            <Button
              onClick={onPlayPause}
              className="w-12 h-12 rounded-full bg-gradient-primary shadow-primary hover:shadow-lg transition-all duration-300 hover:scale-105"
            >
              {isPlaying ? (
                <Pause className="w-5 h-5 text-white" />
              ) : (
                <Play className="w-5 h-5 text-white ml-0.5" />
              )}
            </Button>
            
            <Button
              variant="ghost"
              size="sm"
              onClick={onNext}
              className="opacity-70 hover:opacity-100 transition-all"
            >
              <SkipForward className="w-5 h-5" />
            </Button>
            
            <Button
              variant="ghost"
              size="sm"
              onClick={onToggleRepeat}
              className={`opacity-70 hover:opacity-100 transition-all ${
                repeat !== 'off' ? 'text-primary' : 'text-muted-foreground'
              }`}
            >
              <Repeat className="w-4 h-4" />
              {repeat === 'one' && (
                <span className="absolute -top-1 -right-1 w-2 h-2 bg-primary rounded-full" />
              )}
            </Button>
          </div>
          
          {/* Progress Bar */}
          <div className="flex items-center gap-3 w-full max-w-md">
            <span className="text-xs text-muted-foreground font-mono">
              {formatTime(currentTime)}
            </span>
            
            <div className="flex-1">
              <Slider
                value={[currentTime]}
                max={currentTrack.duration}
                step={1}
                onValueChange={([value]) => onSeek(value)}
                className="w-full"
              />
            </div>
            
            <span className="text-xs text-muted-foreground font-mono">
              {formatTime(currentTrack.duration)}
            </span>
          </div>
        </div>

        {/* Audio Visualizer & Volume */}
        <div className="flex items-center gap-4 min-w-0 flex-1 max-w-xs justify-end">
          {/* Audio Visualizer */}
          <div className="hidden md:flex items-end gap-0.5 h-6">
            {visualizerBars}
          </div>
          
          {/* Volume Control */}
          <div className="flex items-center gap-2">
            <Volume2 className="w-4 h-4 text-muted-foreground" />
            <div className="w-20">
              <Slider
                value={[volume]}
                max={100}
                step={1}
                onValueChange={([value]) => onVolumeChange(value)}
                className="w-full"
              />
            </div>
          </div>
        </div>
      </div>
    </div>
  );
};

export default MusicPlayer;