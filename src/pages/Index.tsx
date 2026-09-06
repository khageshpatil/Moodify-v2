import { useState, useRef, useEffect } from "react";
import { useLocation, useNavigate, useParams } from "react-router-dom";
import { MoodifyPlayer } from "@/components/MoodifyPlayer";
import { ListeningHome } from "@/components/ListeningHome";
import { VibePlaylistPage } from "@/components/VibePlaylistPage";
import { LibraryHub } from "@/components/LibraryHub";
import { SearchBar } from "@/components/SearchBar";
import { PlaylistManager } from "@/components/PlaylistManager";
import { QueueManager } from "@/components/QueueManager";
import { FavoritesView } from "@/components/FavoritesView";
import { HistoryView } from "@/components/HistoryView";
import { DiscoverView } from "@/components/DiscoverView";
import { LocalSocialDiscovery } from "@/components/LocalSocialDiscovery";
import { EnhancedListenTogetherDialog } from "@/components/EnhancedListenTogetherDialog";
import { ListeningInspector } from "@/components/ListeningInspector";
import { RecommendationInspector } from "@/recommendation/RecommendationInspector";
import { UserStateInspector } from "@/userState/UserStateInspector";
import { FriendsManager } from "@/components/FriendsManager";
import { ChatDialog } from "@/components/ChatDialog";
import { useCanonicalMusicPlayer as useMusicPlayer } from "@/hooks/useCanonicalMusicPlayer";
import { useEnhancedListenTogether } from "@/hooks/useEnhancedListenTogether";
import { useEnhancedNotifications } from "@/hooks/useEnhancedNotifications";
import { useDeviceCapabilities } from "@/hooks/useMobileEnhancements";
import { useIdentityContext } from "@/components/identity/IdentityProvider";
import { IdentitySettings } from "@/components/identity/IdentitySettings";
import { usePlaylistSharing } from "@/hooks/usePlaylistSharing";
import { Button } from "@/components/ui/button";
import { Search, Music, Home, ListMusic, Heart, Clock, Sparkles, Upload, Download, Users, User, MessageCircle } from "lucide-react";
import { toast } from "@/hooks/use-toast";
import { searchMoodifyTracks, toDiscoveryPlaybackRequest } from "@/services/moodifyMusicApi";
import { useDiscoverySidecar } from "@/hooks/useDiscoverySidecar";
import type { Track } from "@/data/mockMusic";
import { getVibeWorld } from "@/data/vibeWorlds";
import type { DiscoveryContext } from '@/listening/listeningTypes';
import {
  discoveryContextForDirect,
  discoveryContextForLibrary,
  discoveryContextForScene,
  discoveryContextForSearch,
  discoveryContextForVibe,
  resolvePlaybackStartIndex,
} from '@/listening/discoveryAttribution';

const Index = () => {
  const [currentView, setCurrentView] = useState<'moods' | 'search' | 'library' | 'playlists' | 'queue' | 'favorites' | 'history' | 'discover' | 'social' | 'identity' | 'friends'>('moods');
  const [listenTogetherOpen, setListenTogetherOpen] = useState(false);
  const [chatFriendId, setChatFriendId] = useState<string | null>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);
  
  // React Router hooks
  const location = useLocation();
  const navigate = useNavigate();
  const { vibeId } = useParams();
  const vibeWorld = getVibeWorld(vibeId);
  
  // Race condition prevention (from friend's version)
  const remoteActionRef = useRef<{ lastType: string; lastAt: number }>({ lastType: '', lastAt: 0 });
  const processingRemotePlayRef = useRef(false);
  
  // Identity context (must be at top level, not conditional)
  const { identity, updateDisplayName, exportIdentity, importIdentity, resetIdentity } = useIdentityContext();
  
  // Enhanced hooks
  const {
    notifyMusicAction,
    notifyPlaylistAction,
    notifyConnectionStatus
  } = useEnhancedNotifications();
  const { isMobile } = useDeviceCapabilities();
  
  
  const {
    currentTrack,
    isPlaying,
    currentTime,
    duration,
    volume,
    queue,
    currentTrackIndex,
    playlists,
    favorites,
    history,
    recentlyPlayed,
    shuffle,
    repeat,
    playTrack,
    loadMoodPlaylist,
    togglePlayPause,
    playNext,
    playPrevious,
    seekTo,
    setVolume,
    toggleShuffle,
    toggleRepeat,
    toggleFavorite,
    isFavorite,
    createPlaylist,
    deletePlaylist,
    renamePlaylist,
    addToPlaylist,
    removeFromPlaylist,
    playFromPlaylist,
    exportPlaylist,
    importPlaylist,
    clearHistory,
    addToQueue,
    removeFromQueue,
    clearQueue,
    tasteSnapshot,
    recommendationResult,
    autoplayDiagnostics,
    recommendationBatch,
    userState,
    homeModel,
    listeningCollector,
    listeningSignals,
  } = useMusicPlayer();
  const { related, upNext } = useDiscoverySidecar(currentTrack);

  useEffect(() => {
    if (vibeId && !vibeWorld) navigate('/', { replace: true });
  }, [vibeId, vibeWorld, navigate]);

  // Playlist sharing hook
  const { importFromURLShare } = usePlaylistSharing({
    name: identity?.displayName || 'Anonymous',
    identity: identity || undefined,
  });

  // Handle URL-based playlist import on mount
  useEffect(() => {
    const hash = location.hash;
    
    // Check if URL contains shared playlist data
    if (hash.startsWith('#/shared/')) {
      const encodedData = hash.replace('#/shared/', '');
      
      if (encodedData) {
        console.log('🔗 Detected shared playlist in URL, importing...');
        
        // Import the playlist (async with password handling)
        const attemptImport = async (password?: string) => {
          try {
            const importedPlaylist = await importFromURLShare(encodedData, password);
            
            if (importedPlaylist) {
              // Add to user's playlists
              createPlaylist(importedPlaylist.name, importedPlaylist.tracks);
              
              // Clear the hash from URL
              navigate('/', { replace: true });
              
              // Success toast already shown in importFromURLShare
            }
          } catch (error) {
            if (error instanceof Error && error.message === 'PASSWORD_REQUIRED') {
              // Prompt user for password
              const userPassword = window.prompt('🔒 This playlist is password-protected.\n\nEnter password to import:');
              
              if (userPassword) {
                // Retry with password
                attemptImport(userPassword);
              } else {
                // User cancelled
                navigate('/', { replace: true });
                toast({
                  title: 'Import Cancelled',
                  description: 'Password-protected playlist was not imported',
                });
              }
            }
          }
        };
        
        attemptImport();
      }
    }
  }, [location.hash, importFromURLShare, createPlaylist, navigate]);

  type RemoteControlData = { track?: Track; queue?: Track[]; time?: number; volume?: number; streamUrl?: string; discoveryContext?: DiscoveryContext };
  const handleRemoteControl = (action: string, data?: RemoteControlData) => {
    console.log('[Guest] Received remote control:', action, data);

    // Deduplicate rapid identical actions to avoid flicker (improved from friend's version)
    const now = Date.now();
    const last = remoteActionRef.current;
    if (last.lastType === action && now - last.lastAt < 300) {
      console.log('[Guest] Ignoring duplicate action:', action);
      return;
    }
    remoteActionRef.current = { lastType: action, lastAt: now };

    switch (action) {
      case 'play': {
        if (!data?.track) return;
        
        // Prevent race conditions in play command
        if (processingRemotePlayRef.current) {
          console.log('[Guest] Play already processing, skipped');
          return;
        }
        processingRemotePlayRef.current = true;
        
        try {
          playTrack(data.track, data.queue, data.discoveryContext || discoveryContextForDirect('listen-together'));
          if (typeof data.time === 'number') seekTo(data.time);
          if (typeof data.volume === 'number') setVolume(data.volume);
        } finally {
          // Release lock after a short delay
          setTimeout(() => {
            processingRemotePlayRef.current = false;
          }, 500);
        }
        break;
      }
      case 'pause':
        if (isPlaying) togglePlayPause();
        break;
      case 'next':
        playNext();
        break;
      case 'previous':
        playPrevious();
        break;
      case 'seek':
        if (data?.time !== undefined) seekTo(data.time);
        break;
      case 'volume':
        if (data?.volume !== undefined) setVolume(data.volume);
        break;
      case 'live_sync':
        // Handle live stream sync
        if (data?.streamUrl) {
          console.log('[Guest] Syncing live stream:', data);
        }
        break;
    }
  };

  const {
    state: listenTogetherState,
    createRoom,
    joinRoom,
    disconnect,
    sendMessage,
    startLiveStream,
    stopLiveStream,
    toggleGuestLiveMode,
    sendControl,
    broadcastIdentity,
  } = useEnhancedListenTogether(handleRemoteControl, identity);

  const lastSentTrackId = useRef<string | null>(null);
  const lastDiscoveryContext = useRef<DiscoveryContext | undefined>(undefined);

  const playTrackWithSync = (track: Track, tracks?: Track[], discoveryContext?: DiscoveryContext) => {
    lastDiscoveryContext.current = discoveryContext;
    playTrack(track, tracks ?? queue, discoveryContext);
    if (listenTogetherState.isHost && listenTogetherState.isConnected) {
      sendControl('play', { track, queue: tracks ?? queue, time: 0, volume, discoveryContext });
    }
  };

  const handlePlayPauseWithSync = () => {
    togglePlayPause();
    if (listenTogetherState.isHost && listenTogetherState.isConnected) {
      sendControl(isPlaying ? 'pause' : 'play', { 
        track: currentTrack, 
        queue,
        time: currentTime,
        volume,
        discoveryContext: lastDiscoveryContext.current,
      });
    }
  };

  const handleNextWithSync = () => {
    playNext();
    if (listenTogetherState.isHost && listenTogetherState.isConnected) {
      sendControl('next');
    }
  };

  const handlePreviousWithSync = () => {
    playPrevious();
    if (listenTogetherState.isHost && listenTogetherState.isConnected) {
      sendControl('previous');
    }
  };

  const handleSeekWithSync = (time: number) => {
    seekTo(time);
    if (listenTogetherState.isHost && listenTogetherState.isConnected) {
      sendControl('seek', { time });
    }
  };

  const handleVolumeWithSync = (vol: number) => {
    setVolume(vol);
    if (listenTogetherState.isHost && listenTogetherState.isConnected) {
      sendControl('volume', { volume: vol });
    }
  };

  const handleIntent = async (intent: string, discoveryContext?: DiscoveryContext) => {
    const normalized = intent.toLowerCase();
    const knownMood = normalized.includes('focus') || normalized.includes('coding') ? 'focus'
      : normalized.includes('energy') || normalized.includes('workout') || normalized.includes('drive') ? 'workout'
      : normalized.includes('love') || normalized.includes('romantic') ? 'love'
      : normalized.includes('party') || normalized.includes('celebrat') ? 'party'
      : normalized.includes('sad') || normalized.includes('melanch') ? 'melancholy'
      : normalized.includes('calm') || normalized.includes('comfort') || normalized.includes('reset') ? 'chill'
      : null;
    if (normalized.includes('surprise') || normalized.includes("don't know") || normalized.includes('wildcard')) {
      const candidates = favorites.length ? favorites : recentlyPlayed.length ? recentlyPlayed : await searchMoodifyTracks('songs for a good surprise', 10);
      if (candidates.length) playTrackWithSync(candidates[0], candidates, discoveryContext || discoveryContextForScene('surprise-me', 0));
      return;
    }
    if (normalized.includes('familiar') || normalized.includes('used to love')) {
      const candidates = favorites.length ? favorites : recentlyPlayed;
      if (candidates.length) playTrackWithSync(candidates[0], candidates, discoveryContext || discoveryContextForScene('familiar', 0));
      else toast({ title: 'Your familiar songs are waiting', description: 'Favorite a few tracks and we will build this session.' });
      return;
    }
    if (knownMood) {
      await loadMoodPlaylist(knownMood, discoveryContext || discoveryContextForVibe(knownMood, 0));
      return;
    }
    try {
      const tracks = await searchMoodifyTracks(intent, 10);
      if (tracks.length) playTrackWithSync(tracks[0], tracks, discoveryContext || discoveryContextForSearch(intent, 0));
      else toast({ title: 'Nothing matched that', description: 'Try a song, artist, or describe the moment.' });
    } catch {
      toast({ title: 'Search is unavailable', description: 'Make sure the Moodify music server is running.', variant: 'destructive' });
    }
  };

  const playVibeTracks = (start: Track, tracks: Track[], worldId: string) => {
    const index = Math.max(0, tracks.findIndex((track) => track.id === start.id));
    const track = tracks[index] || tracks[0];
    if (track) playTrackWithSync(track, tracks, discoveryContextForVibe(worldId, index));
  };

  const handleAddToPlaylist = (playlistId: string, track: Track) => {
    addToPlaylist(playlistId, track);
    const playlist = playlists.find(p => p.id === playlistId);
    if (playlist) {
      notifyPlaylistAction('Added to', playlist.name);
    }
  };

  const handleImportPlaylist = () => {
    fileInputRef.current?.click();
  };

  const handleFileChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (file) {
      importPlaylist(file);
    }
  };

  const renderContent = () => {
    if (vibeWorld) {
      const saved = playlists.some((playlist) => playlist.name === vibeWorld.playlistName);
      return (
        <VibePlaylistPage
          world={vibeWorld}
          currentTrack={currentTrack}
          isPlaying={isPlaying}
          saved={saved}
          onBack={() => navigate('/')}
          onPlay={(track, tracks) => playVibeTracks(track, tracks, vibeWorld.id)}
          onToggleFavorite={toggleFavorite}
          isFavorite={isFavorite}
          onSave={(tracks) => {
            if (saved || !tracks.length) return;
            createPlaylist(vibeWorld.playlistName, tracks);
            toast({ title: 'Saved to your library', description: vibeWorld.playlistName });
          }}
          onQueue={addToQueue}
        />
      );
    }

    if (currentView === 'search') {
      return (
        <SearchBar 
          onTrackSelect={(track, tracks, discoveryContext) => playTrackWithSync(track, tracks, discoveryContext)}
          playlists={playlists}
          onAddToPlaylist={handleAddToPlaylist}
          onCreatePlaylist={createPlaylist}
        />
      );
    }

    if (currentView === 'library') {
      return <LibraryHub favorites={favorites} recentlyPlayed={recentlyPlayed} queue={queue} history={history} playlists={playlists} onPlay={playTrackWithSync} onQueue={addToQueue} onOpen={(view) => setCurrentView(view)} onImport={handleImportPlaylist} />;
    }
    
    if (currentView === 'playlists') {
      return (
        <div className="space-y-4">
          <div className="flex gap-2 justify-end">
            <Button onClick={handleImportPlaylist} variant="outline" className="glass-button">
              <Upload className="h-4 w-4 mr-2" />
              Import
            </Button>
          </div>
            <PlaylistManager
              playlists={playlists}
              onCreatePlaylist={createPlaylist}
              onDeletePlaylist={deletePlaylist}
              onRenamePlaylist={renamePlaylist}
              onRemoveFromPlaylist={removeFromPlaylist}
              onPlayPlaylist={playFromPlaylist}
              onExportPlaylist={exportPlaylist}
              currentTrack={currentTrack}
              currentUser={{ 
                name: identity?.displayName || "KP", 
                avatar: undefined,
                identity: identity 
              }}
              onPlayTracks={(tracks, discoveryContext) => {
                const index = resolvePlaybackStartIndex(tracks, discoveryContext);
                const track = tracks[index];
                if (track) playTrackWithSync(track, tracks, discoveryContext);
              }}
            />
        </div>
      );
    }

    if (currentView === 'queue') {
      return (
        <QueueManager
          queue={queue}
          currentTrackIndex={currentTrackIndex}
            onTrackSelect={(track, index) => {
              playTrackWithSync(track, queue, discoveryContextForLibrary('queue', index));
            }}
          onRemoveFromQueue={removeFromQueue}
          onClearQueue={clearQueue}
          relatedTracks={related}
          upNextTracks={upNext}
          onPlayRelated={(track, tracks, index) => {
            const request = toDiscoveryPlaybackRequest('related', tracks, { sourceTrack: currentTrack || undefined, position: index });
            if (request) playTrackWithSync(request.track, request.tracks, request.discoveryContext);
          }}
          onPlayUpNext={(track, tracks, index) => {
            const request = toDiscoveryPlaybackRequest('up-next', tracks, { sourceTrack: currentTrack || undefined, position: index });
            if (request) playTrackWithSync(request.track, request.tracks, request.discoveryContext);
          }}
        />
      );
    }

    if (currentView === 'favorites') {
      return (
        <FavoritesView
          favorites={favorites}
          currentTrack={currentTrack}
          isPlaying={isPlaying}
           onTrackSelect={(track, tracks, discoveryContext) => playTrackWithSync(track, tracks, discoveryContext)}
           onRemoveFavorite={toggleFavorite}
           onPlayAll={() => { if (favorites.length) playTrackWithSync(favorites[0], favorites, discoveryContextForLibrary('favorites', 0)); }}
        />
      );
    }

    if (currentView === 'history') {
      return (
        <HistoryView
          history={history}
          onTrackSelect={(track, tracks, discoveryContext) => playTrackWithSync(track, tracks, discoveryContext)}
          onClearHistory={clearHistory}
        />
      );
    }

    if (currentView === 'discover') {
      return (
        <DiscoverView
          recentlyPlayed={recentlyPlayed}
          favorites={favorites}
           onTrackSelect={(track, tracks, discoveryContext) => playTrackWithSync(track, tracks, discoveryContext)}
           onToggleFavorite={toggleFavorite}
          onAddToQueue={addToQueue}
          isFavorite={isFavorite}
        />
      );
    }

    if (currentView === 'social') {
      return (
        <LocalSocialDiscovery
          onPlayPlaylist={(tracks, discoveryContext) => {
            const index = resolvePlaybackStartIndex(tracks, discoveryContext);
            const track = tracks[index];
            if (track) playTrackWithSync(track, tracks, discoveryContext);
          }}
          onAddToLibrary={(playlist) => {
            createPlaylist(playlist.name);
            notifyPlaylistAction('Added to Library', playlist.name);
          }}
          userPlaylists={playlists}
          userFavorites={favorites}
        />
      );
    }

    if (currentView === 'friends') {
      if (!identity) {
        return (
          <div className="text-center py-12">
            <p className="text-gray-400">Loading identity...</p>
          </div>
        );
      }

      return (
        <FriendsManager
          identity={identity}
          onOpenChat={(friendId) => setChatFriendId(friendId)}
        />
      );
    }

    if (currentView === 'identity') {
      if (!identity) {
        return (
          <div className="text-center py-12">
            <p className="text-gray-400">Loading identity...</p>
          </div>
        );
      }
      
      return (
        <IdentitySettings
          identity={identity}
          onUpdateDisplayName={updateDisplayName}
          onExport={exportIdentity}
          onImport={importIdentity}
          onReset={resetIdentity}
        />
      );
    }

    return <ListeningHome recentlyPlayed={recentlyPlayed} favorites={favorites} history={history} currentTrack={currentTrack} isPlaying={isPlaying} signals={listeningSignals} onIntent={handleIntent} onVibeSelect={(vibeId) => navigate(`/vibes/${vibeId}`)} onPlay={playTrackWithSync} onQueue={addToQueue} homeModel={homeModel} playlists={playlists} />;
  };

  return (
    <div className={`phase4-shell min-h-screen relative overflow-x-hidden${vibeWorld ? ' is-vibe' : ''}`} style={{ background: 'var(--space-navy)' }}>
      
      {/* Ambient Background Glow */}
      <div 
        className="phase4-ambient fixed inset-0 pointer-events-none z-0"
        style={{
          background: 'var(--ambient-glow)',
          opacity: 0.3
        }}
      />
      
      <div className="relative z-10">
        {/* Hero Header */}
        <header className="phase4-header text-center pt-12 md:pt-16 pb-8 md:pb-12 px-4 md:px-6">
          <div className="phase4-topbar">
            <button className="phase4-wordmark" onClick={() => { navigate('/'); setCurrentView('moods'); }} aria-label="Go to Moodify home"><span className="wordmark-dot" />Moodify</button>
            <nav className="phase4-primary-nav" aria-label="Primary navigation">
              {[{ id: 'moods' as const, label: 'Home' }, { id: 'search' as const, label: 'Search' }, { id: 'library' as const, label: 'Library' }, { id: 'social' as const, label: 'Social' }].map((item) => <button key={item.id} className={(!vibeId && currentView === item.id) || (item.id === 'moods' && !vibeId && currentView === 'moods') || (item.id === 'library' && ['playlists', 'queue', 'favorites', 'history', 'discover'].includes(currentView)) ? 'active' : ''} onClick={() => { navigate('/'); setCurrentView(item.id); }}>{item.label}</button>)}
            </nav>
            <div className="phase4-top-actions"><button className="top-action-button" onClick={() => setListenTogetherOpen(true)} aria-label="Open listen together"><Users size={17} aria-hidden="true" /></button><button className="profile-button" onClick={() => setCurrentView('identity')} aria-label="Open profile"><User size={16} aria-hidden="true" /> <span>{identity?.displayName || 'You'}</span></button></div>
          </div>
          <h1 className="legacy-brand text-4xl sm:text-5xl md:text-7xl font-medium mb-3 md:mb-4 fade-in" style={{ 
            color: 'var(--text-primary)',
            letterSpacing: '0.4px',
            fontWeight: 500
          }}>
            Moodify
          </h1>
          <p className="legacy-brand-subtitle text-sm sm:text-base md:text-lg max-w-xl mx-auto mb-8 md:mb-12 fade-in px-4" style={{ 
            color: 'var(--text-secondary)',
            fontWeight: 300,
            lineHeight: 1.6,
            animationDelay: '0.1s'
          }}>
            Experience music through your emotions
          </p>
          
          {/* Desktop: VisionOS-Style Floating Navigation */}
          <nav className="legacy-desktop-nav hidden md:inline-flex items-center gap-2 p-2 mb-8 scale-in"
            style={{
              background: 'var(--surface-frost)',
              backdropFilter: 'var(--blur-medium)',
              border: '1px solid var(--stroke-subtle)',
              borderRadius: 'var(--radius-pill)',
              boxShadow: 'var(--shadow-medium)',
              animationDelay: '0.2s'
            }}
          >
            {[
              { id: 'moods', icon: Home, label: 'Moods', onClick: () => setCurrentView('moods') },
              { id: 'search', icon: Search, label: 'Search', onClick: () => setCurrentView('search') },
              { id: 'playlists', icon: Music, label: `Playlists`, badge: playlists.length, onClick: () => setCurrentView('playlists') },
              { id: 'queue', icon: ListMusic, label: 'Queue', badge: queue.length, onClick: () => setCurrentView('queue') },
              { id: 'favorites', icon: Heart, label: 'Favorites', badge: favorites.length, onClick: () => setCurrentView('favorites') },
              { id: 'history', icon: Clock, label: 'History', onClick: () => setCurrentView('history') },
              { id: 'discover', icon: Sparkles, label: 'Discover', onClick: () => setCurrentView('discover') },
              { id: 'social', icon: Users, label: 'Community', onClick: () => setCurrentView('social') },
              { id: 'friends', icon: MessageCircle, label: 'Friends', onClick: () => setCurrentView('friends') },
            ].map((item) => {
              const Icon = item.icon;
              const isActive = currentView === item.id;
              return (
                <button
                  key={item.id}
                  onClick={item.onClick}
                  className="relative px-4 py-2.5 rounded-full transition-all duration-300 group"
                  style={{
                    background: isActive ? 'var(--accent-lavender)' : 'transparent',
                    color: isActive ? 'var(--space-navy)' : 'var(--text-secondary)',
                    fontWeight: isActive ? 500 : 400,
                    fontSize: '0.875rem',
                    whiteSpace: 'nowrap'
                  }}
                  onMouseEnter={(e) => {
                    if (!isActive) {
                      e.currentTarget.style.background = 'var(--surface-frost-hover)';
                      e.currentTarget.style.color = 'var(--text-primary)';
                    }
                  }}
                  onMouseLeave={(e) => {
                    if (!isActive) {
                      e.currentTarget.style.background = 'transparent';
                      e.currentTarget.style.color = 'var(--text-secondary)';
                    }
                  }}
                >
                  {item.label}
                  {item.badge !== undefined && item.badge > 0 && (
                    <span 
                      className="ml-1.5 px-1.5 py-0.5 rounded-full text-xs"
                      style={{
                        background: isActive ? 'rgba(11, 15, 25, 0.2)' : 'var(--surface-frost-active)',
                        color: isActive ? 'var(--space-navy)' : 'var(--text-primary)'
                      }}
                    >
                      {item.badge}
                    </span>
                  )}
                </button>
              );
            })}
          </nav>

          {/* Mobile: Horizontal Scroll Navigation */}
          <div className="legacy-mobile-nav md:hidden mb-8 -mx-6 px-6 scale-in overflow-x-auto"
            style={{
              animationDelay: '0.2s',
              scrollbarWidth: 'none',
              msOverflowStyle: 'none'
            }}
          >
            <style>{`
              .mobile-nav-scroll::-webkit-scrollbar {
                display: none;
              }
            `}</style>
            <div 
              className="mobile-nav-scroll inline-flex items-center gap-2 p-2"
              style={{
                background: 'var(--surface-frost)',
                backdropFilter: 'var(--blur-medium)',
                border: '1px solid var(--stroke-subtle)',
                borderRadius: 'var(--radius-pill)',
                boxShadow: 'var(--shadow-medium)',
              }}
            >
              {[
                { id: 'moods', icon: Home, label: 'Moods', onClick: () => setCurrentView('moods') },
                { id: 'search', icon: Search, label: 'Search', onClick: () => setCurrentView('search') },
                { id: 'playlists', icon: Music, label: 'Playlists', badge: playlists.length, onClick: () => setCurrentView('playlists') },
                { id: 'queue', icon: ListMusic, label: 'Queue', badge: queue.length, onClick: () => setCurrentView('queue') },
                { id: 'favorites', icon: Heart, label: 'Favorites', badge: favorites.length, onClick: () => setCurrentView('favorites') },
                { id: 'discover', icon: Sparkles, label: 'Discover', onClick: () => setCurrentView('discover') },
                { id: 'social', icon: Users, label: 'Community', onClick: () => setCurrentView('social') },
                { id: 'friends', icon: MessageCircle, label: 'Friends', onClick: () => setCurrentView('friends') },
              ].map((item) => {
                const Icon = item.icon;
                const isActive = currentView === item.id;
                return (
                  <button
                    key={item.id}
                    onClick={item.onClick}
                    className="relative flex items-center gap-2 px-4 py-2.5 rounded-full transition-all duration-300 flex-shrink-0"
                    style={{
                      background: isActive ? 'var(--accent-lavender)' : 'transparent',
                      color: isActive ? 'var(--space-navy)' : 'var(--text-secondary)',
                      fontWeight: isActive ? 500 : 400,
                      fontSize: '0.875rem',
                      whiteSpace: 'nowrap'
                    }}
                  >
                    <Icon className="h-4 w-4" />
                    <span>{item.label}</span>
                    {item.badge !== undefined && item.badge > 0 && (
                      <span 
                        className="px-1.5 py-0.5 rounded-full text-xs"
                        style={{
                          background: isActive ? 'rgba(11, 15, 25, 0.2)' : 'var(--surface-frost-active)',
                          color: isActive ? 'var(--space-navy)' : 'var(--text-primary)'
                        }}
                      >
                        {item.badge}
                      </span>
                    )}
                  </button>
                );
              })}
            </div>
          </div>

          {/* Secondary Actions Row */}
          <div className="legacy-header-actions flex flex-wrap justify-center gap-2 sm:gap-3 mb-8 px-4 scale-in" style={{ animationDelay: '0.3s' }}>
            <button
              onClick={() => setListenTogetherOpen(true)}
              className="btn-ghost flex items-center gap-2 text-sm"
            >
              <Users className="h-4 w-4" />
              <span>Listen Together</span>
              {listenTogetherState.isConnected && (
                <span className="w-2 h-2 rounded-full glow-pulse" style={{ background: 'var(--accent-lavender)' }} />
              )}
            </button>
            <button
              onClick={() => setCurrentView('identity')}
              className="btn-ghost flex items-center gap-2 text-sm"
            >
              <User className="h-4 w-4" />
              <span className="max-w-[120px] truncate">{identity?.displayName || 'Identity'}</span>
            </button>
          </div>

          <input
            ref={fileInputRef}
            type="file"
            accept=".json"
            onChange={handleFileChange}
            className="hidden"
          />
        </header>

        {/* Main Content */}
        <main className="max-w-7xl mx-auto px-4 md:px-6 pb-32 md:pb-40">
          {renderContent()}
        </main>
      </div>

      <MoodifyPlayer
        currentTrack={currentTrack}
        isPlaying={isPlaying}
        currentTime={currentTime}
        duration={duration}
        volume={volume}
        shuffle={shuffle}
        repeat={repeat}
        isFavorite={currentTrack ? isFavorite(currentTrack.id) : false}
        onPlayPause={handlePlayPauseWithSync}
        onNext={handleNextWithSync}
        onPrevious={handlePreviousWithSync}
        onSeek={handleSeekWithSync}
        onVolumeChange={handleVolumeWithSync}
        onToggleShuffle={toggleShuffle}
        onToggleRepeat={toggleRepeat}
        onToggleFavorite={() => currentTrack && toggleFavorite(currentTrack)}
        onOpenQueue={() => { navigate('/'); setCurrentView('queue'); }}
        relatedTracks={related}
        onPlayRelated={(track, tracks) => {
          const request = toDiscoveryPlaybackRequest('related', tracks, { sourceTrack: currentTrack || undefined, position: Math.max(0, tracks.findIndex((item) => item.id === track.id)) });
          if (request) playTrackWithSync(request.track, request.tracks, request.discoveryContext);
        }}
      />

      <ListeningInspector collector={listeningCollector} />
      <RecommendationInspector
        result={recommendationResult}
        autoplay={autoplayDiagnostics}
        batch={recommendationBatch}
        currentTitle={currentTrack?.title}
        queueRemaining={Math.max(0, queue.length - currentTrackIndex - 1)}
      />
      <UserStateInspector userState={userState} homeModel={homeModel} />

      <EnhancedListenTogetherDialog
        open={listenTogetherOpen}
        onOpenChange={setListenTogetherOpen}
        onCreateRoom={createRoom}
        onJoinRoom={joinRoom}
        roomCode={listenTogetherState.roomCode}
        isConnected={listenTogetherState.isConnected}
        isHost={listenTogetherState.isHost}
        connectedGuests={listenTogetherState.connectedGuests}
        connectionStatus={listenTogetherState.connectionStatus}
        liveStream={listenTogetherState.liveStream}
        onDisconnect={disconnect}
        messages={listenTogetherState.messages}
        onSendMessage={sendMessage}
        onStartLiveStream={startLiveStream}
        onStopLiveStream={stopLiveStream}
        onToggleGuestLiveMode={toggleGuestLiveMode}
      />

      {identity && (
        <ChatDialog
          open={chatFriendId !== null}
          onOpenChange={(open) => !open && setChatFriendId(null)}
          friendId={chatFriendId}
          identity={identity}
        />
      )}
    </div>
  );
};

export default Index;
