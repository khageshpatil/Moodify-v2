import { useState, useEffect, useRef } from 'react';
import { Play, Pause, SkipBack, SkipForward, Volume2, Shuffle, Repeat, Heart, Share2, MoreHorizontal, Sliders, Music, Maximize2, Minimize2, Download } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Slider } from '@/components/ui/slider';
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger } from '@/components/ui/dropdown-menu';
import { useMobileGestures, useHapticFeedback, useDeviceCapabilities } from '@/hooks/useMobileEnhancements';
import { useEnhancedNotifications } from '@/hooks/useEnhancedNotifications';
import { useEnhancedAudioEngine } from '@/hooks/useEnhancedAudioEngine';
import { LoadingState } from '@/components/ui/loading';
import { PremiumAudioControls } from './PremiumAudioControls';
import { downloadTrack, canDownloadTrack } from '@/utils/downloadMusic';
import { useToast } from '@/hooks/use-toast';

interface Track {
  id: string;
  title: string;
  artist: string;
  album: string;
  albumArt: string;
  duration: number;
}

interface EnhancedMusicPlayerProps {
  currentTrack: Track | null;
  isPlaying: boolean;
  isLoading?: boolean;
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
  onShare?: () => void;
}

export const EnhancedMusicPlayer = ({
  currentTrack,
  isPlaying,
  isLoading = false,
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
  onShare,
}: EnhancedMusicPlayerProps) => {
  const { toast } = useToast();
  const [isExpanded, setIsExpanded] = useState(false);
  const [showPremiumControls, setShowPremiumControls] = useState(false);
  const [showVisualizer, setShowVisualizer] = useState(false);
  const [isDownloading, setIsDownloading] = useState(false);
  const [downloadProgress, setDownloadProgress] = useState(0);
  const playerRef = useRef<HTMLDivElement>(null);
  const { handleTouchStart, handleTouchMove, handleTouchEnd } = useMobileGestures();
  const { lightTap, mediumTap, successPattern } = useHapticFeedback();
  const { isMobile, hasTouch } = useDeviceCapabilities();
  const { notifyMusicAction } = useEnhancedNotifications();
  const { visualizerData, settings, effects } = useEnhancedAudioEngine();

  // Enhanced gesture handling
  useEffect(() => {
    if (!hasTouch || !playerRef.current) return;

    const element = playerRef.current;

    const handleTouch = (e: TouchEvent) => {
      const result = handleTouchEnd();
      if (!result) return;

      const { isLeftSwipe, isRightSwipe, isUpSwipe, isDownSwipe } = result;

      if (isLeftSwipe) {
        onNext();
        mediumTap();
        notifyMusicAction('Next Track', currentTrack?.title || '');
      } else if (isRightSwipe) {
        onPrevious();
        mediumTap();
        notifyMusicAction('Previous Track', currentTrack?.title || '');
      } else if (isUpSwipe && isMobile) {
        setIsExpanded(true);
        lightTap();
      } else if (isDownSwipe && isMobile && isExpanded) {
        setIsExpanded(false);
        lightTap();
      }
    };

    element.addEventListener('touchstart', handleTouchStart);
    element.addEventListener('touchmove', handleTouchMove);
    element.addEventListener('touchend', handleTouch);

    return () => {
      element.removeEventListener('touchstart', handleTouchStart);
      element.removeEventListener('touchmove', handleTouchMove);
      element.removeEventListener('touchend', handleTouch);
    };
  }, [hasTouch, handleTouchStart, handleTouchMove, handleTouchEnd, onNext, onPrevious, isMobile, isExpanded, mediumTap, lightTap, notifyMusicAction, currentTrack]);

  const formatTime = (seconds: number) => {
    const mins = Math.floor(seconds / 60);
    const secs = Math.floor(seconds % 60);
    return `${mins}:${secs.toString().padStart(2, '0')}`;
  };

  const handlePlayPauseClick = () => {
    onPlayPause();
    lightTap();
    if (isPlaying) {
      notifyMusicAction('Paused', currentTrack?.title || '');
    } else {
      notifyMusicAction('Playing', currentTrack?.title || '');
    }
  };

  const handleFavoriteClick = () => {
    onToggleFavorite();
    successPattern();
    notifyMusicAction(isFavorite ? 'Removed from Favorites' : 'Added to Favorites', currentTrack?.title || '');
  };

  const handleDownload = async () => {
    if (!currentTrack || !canDownloadTrack(currentTrack)) {
      toast({
        title: 'Download Unavailable',
        description: 'This track cannot be downloaded',
        variant: 'destructive',
      });
      return;
    }

    setIsDownloading(true);
    lightTap();

    await downloadTrack(currentTrack, {
      onProgress: (progress) => {
        setDownloadProgress(progress);
      },
      onSuccess: () => {
        successPattern();
        setIsDownloading(false);
        setDownloadProgress(0);
      },
      onError: (error) => {
        mediumTap();
        toast({
          title: 'Download Failed',
          description: error.message,
          variant: 'destructive',
        });
        setIsDownloading(false);
        setDownloadProgress(0);
      },
    });
  };

  // Audio visualizer bars with enhanced animation and real data
  const visualizerBars = Array.from({ length: isMobile ? 15 : 25 }, (_, i) => {
    const dataIndex = Math.floor((i / (isMobile ? 15 : 25)) * (visualizerData?.length || 256));
    const height = visualizerData && visualizerData[dataIndex] ? visualizerData[dataIndex] : Math.random() * 20;
    return (
      <div
        key={i}
        className={`visualizer-bar w-1 bg-gradient-primary rounded-full transition-all duration-100 ${
          isPlaying ? 'animate-pulse' : 'h-1'
        }`}
        style={{
          animationDelay: `${i * 0.05}s`,
          height: isPlaying ? `${Math.max(2, (height / 255) * 16) + 4}px` : '4px',
          opacity: isPlaying ? 0.7 + (height / 255) * 0.3 : 0.3,
        }}
      />
    );
  });

  if (!currentTrack) {
    return null;
  }

  if (isLoading) {
    return (
      <div className="fixed bottom-0 left-0 right-0 z-50 glass-card border-t border-white/10 p-4">
        <LoadingState type="music" message="Loading track..." size="sm" />
      </div>
    );
  }

  return (
    <>
      {/* VisionOS-Style Floating Player */}
      <div 
        ref={playerRef}
        className={`fixed left-0 right-0 z-50 transition-all duration-500 ${
          isExpanded ? 'bottom-0 h-screen' : 'bottom-6 px-6'
        }`}
        style={{
          filter: 'drop-shadow(0 20px 60px rgba(0, 0, 0, 0.5))'
        }}
      >
        <div 
          className={`mx-auto max-w-7xl transition-all duration-500 ${
            isExpanded ? 'rounded-none h-full' : 'rounded-3xl'
          }`}
          style={{
            background: isExpanded ? 'var(--space-navy)' : 'var(--surface-frost)',
            backdropFilter: 'var(--blur-heavy)',
            border: `1px solid ${isExpanded ? 'transparent' : 'var(--stroke-subtle)'}`,
            boxShadow: isExpanded ? 'none' : 'var(--shadow-large)',
            padding: isExpanded ? '0' : 'var(--space-md)'
          }}
        >
        {!isExpanded ? (
          // Compact View
          <div className="max-w-7xl mx-auto flex items-center gap-4">
            {/* Current Track Info */}
            <div className="flex items-center gap-3 min-w-0 flex-1 max-w-xs">
              <div 
                className="relative group cursor-pointer"
                onClick={() => isMobile && setIsExpanded(true)}
              >
                <img
                  src={currentTrack.albumArt}
                  alt={currentTrack.album}
                  className="w-14 h-14 rounded-lg object-cover shadow-lg transition-all duration-300 group-hover:scale-105"
                />
                <div className="absolute inset-0 bg-black/20 rounded-lg opacity-0 group-hover:opacity-100 transition-opacity duration-300" />
                {isPlaying && (
                  <div className="absolute inset-0 border-2 border-primary rounded-lg animate-pulse" />
                )}
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
                onClick={handleFavoriteClick}
                className={`opacity-70 hover:opacity-100 transition-all ${
                  isFavorite ? 'text-mood-love' : 'text-muted-foreground'
                }`}
              >
                <Heart className={`w-4 h-4 ${isFavorite ? 'fill-current' : ''}`} />
              </Button>
              
              <Button
                variant="ghost"
                size="sm"
                onClick={handleDownload}
                disabled={isDownloading || !canDownloadTrack(currentTrack)}
                className="opacity-70 hover:opacity-100 transition-all"
                title={isDownloading ? 'Downloading...' : 'Download song'}
              >
                <Download className={`w-4 h-4 ${isDownloading ? 'animate-pulse' : ''}`} />
              </Button>
            </div>

            {/* Main Controls */}
            <div className="flex flex-col items-center gap-2 flex-1 max-w-2xl">
              <div className="flex items-center gap-2">
                {!isMobile && (
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
                )}
                
                <Button
                  variant="ghost"
                  size="sm"
                  onClick={() => { onPrevious(); mediumTap(); }}
                  className="opacity-70 hover:opacity-100 transition-all"
                >
                  <SkipBack className="w-5 h-5" />
                </Button>
                
                <Button
                  onClick={handlePlayPauseClick}
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
                  onClick={() => { onNext(); mediumTap(); }}
                  className="opacity-70 hover:opacity-100 transition-all"
                >
                  <SkipForward className="w-5 h-5" />
                </Button>
                
                {!isMobile && (
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
                )}
              </div>
              
              {/* Progress Bar */}
              {!isMobile && (
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
              )}
            </div>

            {/* Audio Visualizer & Volume */}
            <div className="flex items-center gap-4 min-w-0 flex-1 max-w-xs justify-end">
              {/* Audio Visualizer */}
              {!isMobile && (
                <div className="hidden md:flex items-end gap-0.5 h-6">
                  {visualizerBars}
                </div>
              )}
              
              {/* More Options */}
              <DropdownMenu>
                <DropdownMenuTrigger asChild>
                  <Button variant="ghost" size="sm">
                    <MoreHorizontal className="w-4 h-4" />
                  </Button>
                </DropdownMenuTrigger>
                <DropdownMenuContent align="end">
                  <DropdownMenuItem onClick={() => setShowPremiumControls(true)}>
                    <Sliders className="w-4 h-4 mr-2" />
                    Audio Controls
                  </DropdownMenuItem>
                  <DropdownMenuItem onClick={() => setShowVisualizer(!showVisualizer)}>
                    <Music className="w-4 h-4 mr-2" />
                    {showVisualizer ? 'Hide' : 'Show'} Visualizer
                  </DropdownMenuItem>
                  {onShare && (
                    <DropdownMenuItem onClick={onShare}>
                      <Share2 className="w-4 h-4 mr-2" />
                      Share Track
                    </DropdownMenuItem>
                  )}
                  <DropdownMenuItem onClick={onToggleShuffle}>
                    <Shuffle className="w-4 h-4 mr-2" />
                    {shuffle ? 'Disable' : 'Enable'} Shuffle
                  </DropdownMenuItem>
                  <DropdownMenuItem onClick={onToggleRepeat}>
                    <Repeat className="w-4 h-4 mr-2" />
                    Repeat: {repeat === 'off' ? 'Off' : repeat === 'all' ? 'All' : 'One'}
                  </DropdownMenuItem>
                  {!isMobile && (
                    <DropdownMenuItem onClick={() => setIsExpanded(true)}>
                      <Maximize2 className="w-4 h-4 mr-2" />
                      Full Screen
                    </DropdownMenuItem>
                  )}
                </DropdownMenuContent>
              </DropdownMenu>
              
              {/* Volume Control */}
              {!isMobile && (
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
              )}
            </div>
          </div>
        ) : (
          // Expanded Mobile View
          <div className="h-full flex flex-col p-6">
            {/* Header */}
            <div className="flex items-center justify-between mb-8">
              <Button
                variant="ghost"
                onClick={() => setIsExpanded(false)}
                className="p-2"
              >
                <SkipBack className="w-6 h-6 rotate-90" />
              </Button>
              <h2 className="text-lg font-semibold">Now Playing</h2>
              <DropdownMenu>
                <DropdownMenuTrigger asChild>
                  <Button variant="ghost" size="sm">
                    <MoreHorizontal className="w-6 h-6" />
                  </Button>
                </DropdownMenuTrigger>
                <DropdownMenuContent align="end">
                  {onShare && (
                    <DropdownMenuItem onClick={onShare}>
                      <Share2 className="w-4 h-4 mr-2" />
                      Share Track
                    </DropdownMenuItem>
                  )}
                </DropdownMenuContent>
              </DropdownMenu>
            </div>

            {/* Album Art */}
            <div className="flex-1 flex items-center justify-center mb-8">
              <div className="relative">
                <img
                  src={currentTrack.albumArt}
                  alt={currentTrack.album}
                  className="w-80 h-80 rounded-2xl object-cover shadow-2xl"
                />
                {isPlaying && (
                  <div className="absolute inset-0 border-4 border-primary rounded-2xl animate-pulse" />
                )}
              </div>
            </div>

            {/* Track Info */}
            <div className="text-center mb-8">
              <h1 className="text-2xl font-bold mb-2">{currentTrack.title}</h1>
              <p className="text-lg text-muted-foreground mb-4">{currentTrack.artist}</p>
              <p className="text-sm text-muted-foreground">{currentTrack.album}</p>
            </div>

            {/* Progress */}
            <div className="mb-8">
              <Slider
                value={[currentTime]}
                max={currentTrack.duration}
                step={1}
                onValueChange={([value]) => onSeek(value)}
                className="w-full mb-2"
              />
              <div className="flex justify-between text-sm text-muted-foreground">
                <span>{formatTime(currentTime)}</span>
                <span>{formatTime(currentTrack.duration)}</span>
              </div>
            </div>

            {/* Controls */}
            <div className="flex items-center justify-center gap-8 mb-8">
              <Button
                variant="ghost"
                size="lg"
                onClick={onToggleShuffle}
                className={shuffle ? 'text-primary' : 'text-muted-foreground'}
              >
                <Shuffle className="w-6 h-6" />
              </Button>
              
              <Button
                variant="ghost"
                size="lg"
                onClick={() => { onPrevious(); mediumTap(); }}
              >
                <SkipBack className="w-8 h-8" />
              </Button>
              
              <Button
                onClick={handlePlayPauseClick}
                size="lg"
                className="w-16 h-16 rounded-full bg-gradient-primary shadow-primary"
              >
                {isPlaying ? (
                  <Pause className="w-8 h-8 text-background" />
                ) : (
                  <Play className="w-8 h-8 text-background ml-1" />
                )}
              </Button>
              
              <Button
                variant="ghost"
                size="lg"
                onClick={() => { onNext(); mediumTap(); }}
              >
                <SkipForward className="w-8 h-8" />
              </Button>
              
              <Button
                variant="ghost"
                size="lg"
                onClick={onToggleRepeat}
                className={repeat !== 'off' ? 'text-primary' : 'text-muted-foreground'}
              >
                <Repeat className="w-6 h-6" />
                {repeat === 'one' && (
                  <span className="absolute -top-1 -right-1 w-3 h-3 bg-primary rounded-full" />
                )}
              </Button>
            </div>

            {/* Bottom Actions */}
            <div className="flex items-center justify-between">
              <div className="flex gap-2">
                <Button
                  variant="ghost"
                  onClick={handleFavoriteClick}
                  className={isFavorite ? 'text-mood-love' : 'text-muted-foreground'}
                >
                  <Heart className={`w-6 h-6 ${isFavorite ? 'fill-current' : ''}`} />
                </Button>
                
                <Button
                  variant="ghost"
                  onClick={handleDownload}
                  disabled={isDownloading || !canDownloadTrack(currentTrack)}
                  className="text-muted-foreground"
                  title={isDownloading ? 'Downloading...' : 'Download song'}
                >
                  <Download className={`w-6 h-6 ${isDownloading ? 'animate-pulse' : ''}`} />
                </Button>
              </div>
              
              <div className="flex items-center gap-4">
                <Volume2 className="w-5 h-5 text-muted-foreground" />
                <div className="w-32">
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
        )}
      </div>

      {/* Swipe hint for mobile */}
      {isMobile && hasTouch && !isExpanded && (
        <div className="fixed bottom-20 left-1/2 transform -translate-x-1/2 z-40 text-xs text-muted-foreground animate-pulse">
          ← Swipe for controls • Tap to expand →
        </div>
      )}

      {/* Premium Audio Controls */}
      <PremiumAudioControls 
        isOpen={showPremiumControls}
        onClose={() => setShowPremiumControls(false)}
      />

      {/* Enhanced Visualizer Overlay */}
      {showVisualizer && (
        <div className="fixed inset-0 z-40 bg-black/80 backdrop-blur-sm flex items-center justify-center p-8">
          <div className="w-full max-w-4xl">
            <div className="flex justify-between items-center mb-8">
              <div>
                <h2 className="text-2xl font-bold text-white">Audio Visualizer</h2>
                <p className="text-white/70">{currentTrack.title} • {currentTrack.artist}</p>
              </div>
              <Button 
                variant="ghost" 
                size="sm" 
                onClick={() => setShowVisualizer(false)}
                className="text-white hover:bg-white/10"
              >
                <Minimize2 className="w-4 h-4" />
              </Button>
            </div>
            
            {/* Large Visualizer */}
            <div className="h-64 bg-gradient-to-t from-primary/20 to-transparent rounded-2xl p-8 flex items-end justify-center gap-2">
              {Array.from({ length: 64 }, (_, i) => {
                const dataIndex = Math.floor((i / 64) * (visualizerData?.length || 256));
                const height = visualizerData && visualizerData[dataIndex] ? visualizerData[dataIndex] : Math.random() * 50;
                return (
                  <div
                    key={i}
                    className="bg-gradient-to-t from-primary to-primary/60 rounded-full transition-all duration-100"
                    style={{
                      width: '8px',
                      height: `${Math.max(4, (height / 255) * 200)}px`,
                      opacity: 0.8 + (height / 255) * 0.2,
                    }}
                  />
                );
              })}
            </div>

            {/* Audio Effects Status */}
            <div className="mt-6 flex flex-wrap gap-3 justify-center">
              {effects.equalizer.enabled && (
                <div className="px-3 py-1 bg-primary/20 rounded-full text-sm text-white">
                  EQ: {effects.equalizer.presets}
                </div>
              )}
              {effects.bassBoost.enabled && (
                <div className="px-3 py-1 bg-primary/20 rounded-full text-sm text-white">
                  Bass Boost: {effects.bassBoost.level}%
                </div>
              )}
              {effects.reverb.enabled && (
                <div className="px-3 py-1 bg-primary/20 rounded-full text-sm text-white">
                  Reverb: {effects.reverb.type}
                </div>
              )}
              {settings.playbackSpeed !== 1.0 && (
                <div className="px-3 py-1 bg-primary/20 rounded-full text-sm text-white">
                  Speed: {settings.playbackSpeed}x
                </div>
              )}
              {settings.crossfadeDuration > 0 && (
                <div className="px-3 py-1 bg-primary/20 rounded-full text-sm text-white">
                  Crossfade: {settings.crossfadeDuration}s
                </div>
              )}
            </div>
          </div>
        </div>
      )}
      </div>
    </>
  );
};
