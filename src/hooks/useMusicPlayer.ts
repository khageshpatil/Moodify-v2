import { useState, useEffect, useRef, useCallback } from 'react';
import { Track } from '@/data/mockMusic';
import { getCanonicalPlaybackEngine } from '@/playback/PlaybackEngine';
import type { PlaybackSnapshot, PlaybackTrack } from '@/playback/playbackTypes';
import { searchMoodifyTracks } from '@/services/moodifyMusicApi';
import { toast } from '@/hooks/use-toast';
import { getMoodPlaylist } from '@/data/moodPlaylists';

export interface Playlist {
  id: string;
  name: string;
  tracks: Track[];
  createdAt: Date;
}

export interface HistoryItem {
  track: Track;
  playedAt: Date;
  duration: number;
}

interface MusicPlayerState {
  currentTrack: Track | null;
  isPlaying: boolean;
  currentTime: number;
  duration: number;
  volume: number;
  queue: Track[];
  currentTrackIndex: number;
  playlists: Playlist[];
  favorites: Track[];
  history: HistoryItem[];
  recentlyPlayed: Track[];
  shuffle: boolean;
  repeat: 'off' | 'all' | 'one';
  originalQueue: Track[];
}

const STORAGE_KEYS = {
  PLAYLISTS: 'moodify_playlists',
  FAVORITES: 'moodify_favorites',
  HISTORY: 'moodify_history',
  RECENTLY_PLAYED: 'moodify_recently_played',
  VOLUME: 'moodify_volume',
};

const loadFromStorage = <T,>(key: string, defaultValue: T): T => {
  try {
    const item = localStorage.getItem(key);
    return item ? JSON.parse(item) : defaultValue;
  } catch {
    return defaultValue;
  }
};

const saveToStorage = <T,>(key: string, value: T) => {
  try {
    localStorage.setItem(key, JSON.stringify(value));
  } catch (error) {
    console.error('Failed to save to storage:', error);
  }
};

export const useMusicPlayer = () => {
  const [state, setState] = useState<MusicPlayerState>({
    currentTrack: null,
    isPlaying: false,
    currentTime: 0,
    duration: 0,
    volume: loadFromStorage(STORAGE_KEYS.VOLUME, 70),
    queue: [],
    currentTrackIndex: -1,
    playlists: loadFromStorage<Playlist[]>(STORAGE_KEYS.PLAYLISTS, []),
    favorites: loadFromStorage<Track[]>(STORAGE_KEYS.FAVORITES, []),
    history: loadFromStorage<HistoryItem[]>(STORAGE_KEYS.HISTORY, []),
    recentlyPlayed: loadFromStorage<Track[]>(STORAGE_KEYS.RECENTLY_PLAYED, []),
    shuffle: false,
    repeat: 'off',
    originalQueue: [],
  });

  const playStartTimeRef = useRef<number>(0);
  const saveTimeoutRef = useRef<Record<string, NodeJS.Timeout>>({});
  const stateRef = useRef(state);

  const resolvePlaybackSource = useCallback(async (track: PlaybackTrack, signal: AbortSignal) => {
    const serverBase = (import.meta.env.VITE_MOODIFY_SERVER_URL || 'http://127.0.0.1:8787').replace(/\/$/, '');
    let providerId = track.provider === 'youtube-music' ? track.providerId || track.id : undefined;
    if (!providerId) {
      const response = await fetch(`${serverBase}/api/search?q=${encodeURIComponent(`${track.title} ${track.artist}`)}`, { signal });
      if (!response.ok) throw new Error(`Playback search failed (${response.status})`);
      const data = await response.json() as { tracks?: Array<{ id?: string }> };
      providerId = data.tracks?.[0]?.id;
    }
    if (!providerId) throw new Error('No YouTube Music source matched this track');
    return { url: `${serverBase}/api/media/${encodeURIComponent(providerId)}` };
  }, []);

  const playbackEngineRef = useRef<ReturnType<typeof getCanonicalPlaybackEngine> | null>(null);
  if (!playbackEngineRef.current) playbackEngineRef.current = getCanonicalPlaybackEngine(resolvePlaybackSource);

  // Keep stateRef in sync with state
  useEffect(() => {
    stateRef.current = state;
  }, [state]);

  // Debounced save to localStorage - consolidate multiple saves
  useEffect(() => {
    // Clear existing timeouts
    Object.values(saveTimeoutRef.current).forEach(timeout => clearTimeout(timeout));
    
    // Debounce saves to avoid excessive localStorage writes
    saveTimeoutRef.current = {
      playlists: setTimeout(() => saveToStorage(STORAGE_KEYS.PLAYLISTS, state.playlists), 500),
      favorites: setTimeout(() => saveToStorage(STORAGE_KEYS.FAVORITES, state.favorites), 500),
      history: setTimeout(() => saveToStorage(STORAGE_KEYS.HISTORY, state.history), 1000),
      recentlyPlayed: setTimeout(() => saveToStorage(STORAGE_KEYS.RECENTLY_PLAYED, state.recentlyPlayed), 500),
      volume: setTimeout(() => saveToStorage(STORAGE_KEYS.VOLUME, state.volume), 300),
    };

    // Cleanup function
    return () => {
      Object.values(saveTimeoutRef.current).forEach(timeout => clearTimeout(timeout));
    };
  }, [state.playlists, state.favorites, state.history, state.recentlyPlayed, state.volume]);

  // Initialize audio element
  useEffect(() => {
    audioRef.current = getCanonicalPlaybackEngine().getAudioElement();
    
    const audio = audioRef.current;
    if (audio) {
      audio.preload = 'auto';
      audio.crossOrigin = 'anonymous';
    }
    
    // Throttle timeupdate to reduce re-renders (fires ~4 times per second instead of 250+ times per second)
    let lastUpdateTime = 0;
    const throttleDelay = 250; // Update UI every 250ms
    
    const handleTimeUpdate = () => {
      const now = Date.now();
      if (now - lastUpdateTime >= throttleDelay) {
        lastUpdateTime = now;
        setState(prev => ({
          ...prev,
          currentTime: audio.currentTime,
        }));
      }
    };

    const handleEnded = () => {
      // Save to history using setState directly
      setState(prev => {
        const newHistory = prev.currentTrack 
          ? [{
              track: prev.currentTrack,
              playedAt: new Date(),
              duration: Date.now() - playStartTimeRef.current,
            } as HistoryItem, ...prev.history].slice(0, 100)
          : prev.history;

        if (prev.repeat === 'one') {
          return {
            ...prev,
            history: newHistory,
            currentTime: 0,
            isPlaying: true,
          };
        }

        const nextIndex = prev.currentTrackIndex + 1;
        if (nextIndex < prev.queue.length) {
          return {
            ...prev,
            history: newHistory,
            currentTrackIndex: nextIndex,
            currentTrack: prev.queue[nextIndex],
            currentTime: 0,
            isPlaying: true,
          };
        } else if (prev.repeat === 'all' && prev.queue.length > 0) {
          return {
            ...prev,
            history: newHistory,
            currentTrackIndex: 0,
            currentTrack: prev.queue[0],
            currentTime: 0,
            isPlaying: true,
          };
        } else {
          return {
            ...prev,
            history: newHistory,
            isPlaying: false,
            currentTime: 0,
          };
        }
      });
    };

    const handleLoadedData = () => {
      setState(prev => ({
        ...prev,
        duration: audio.duration || 0,
      }));
    };

    const handleError = (e: Event) => {
      console.error('Audio playback error:', e);
      setState(prev => ({
        ...prev,
        isPlaying: false,
      }));
    };

    audio.addEventListener('timeupdate', handleTimeUpdate);
    audio.addEventListener('ended', handleEnded);
    audio.addEventListener('loadeddata', handleLoadedData);
    audio.addEventListener('error', handleError);

    return () => {
      audio.removeEventListener('timeupdate', handleTimeUpdate);
      audio.removeEventListener('ended', handleEnded);
      audio.removeEventListener('loadeddata', handleLoadedData);
      audio.removeEventListener('error', handleError);
      audio.pause();
      audio.src = '';
    };
  }, []);

  // Keyboard shortcuts
  useEffect(() => {
    const handleKeyPress = (e: KeyboardEvent) => {
      if (e.target instanceof HTMLInputElement || e.target instanceof HTMLTextAreaElement) return;

      switch (e.key) {
        case ' ':
          e.preventDefault();
          setState(prev => ({ ...prev, isPlaying: !prev.isPlaying }));
          break;
        case 'ArrowRight':
          if (e.shiftKey) {
            // Play next
            setState(prev => {
              const nextIndex = prev.currentTrackIndex + 1;
              if (nextIndex < prev.queue.length) {
                return {
                  ...prev,
                  currentTrackIndex: nextIndex,
                  currentTrack: prev.queue[nextIndex],
                  currentTime: 0,
                  isPlaying: true,
                };
              } else if (prev.repeat === 'all' && prev.queue.length > 0) {
                return {
                  ...prev,
                  currentTrackIndex: 0,
                  currentTrack: prev.queue[0],
                  currentTime: 0,
                  isPlaying: true,
                };
              }
              return prev;
            });
          } else {
            setState(prev => ({
              ...prev,
              currentTime: Math.max(0, Math.min(prev.currentTime + 10, prev.duration)),
            }));
          }
          break;
        case 'ArrowLeft':
          if (e.shiftKey) {
            // Play previous
            setState(prev => {
              if (prev.currentTime > 3) {
                return { ...prev, currentTime: 0 };
              }
              const prevIndex = prev.currentTrackIndex - 1;
              if (prevIndex >= 0) {
                return {
                  ...prev,
                  currentTrackIndex: prevIndex,
                  currentTrack: prev.queue[prevIndex],
                  currentTime: 0,
                  isPlaying: true,
                };
              }
              return prev;
            });
          } else {
            setState(prev => ({
              ...prev,
              currentTime: Math.max(0, prev.currentTime - 10),
            }));
          }
          break;
        case 'ArrowUp':
          e.preventDefault();
          setState(prev => ({
            ...prev,
            volume: Math.max(0, Math.min(100, prev.volume + 5)),
          }));
          break;
        case 'ArrowDown':
          e.preventDefault();
          setState(prev => ({
            ...prev,
            volume: Math.max(0, Math.min(100, prev.volume - 5)),
          }));
          break;
      }
    };

    window.addEventListener('keydown', handleKeyPress);
    return () => window.removeEventListener('keydown', handleKeyPress);
  }, []);

  // Handle track changes
  useEffect(() => {
    const audio = audioRef.current;
    if (!audio || !state.currentTrack?.url) return;

    audio.src = state.currentTrack.url;
    audio.currentTime = state.currentTime;
    playStartTimeRef.current = Date.now();
    
    if (state.isPlaying) {
      audio.play().catch(console.error);
    } else {
      audio.pause();
    }
  }, [state.currentTrack?.url]);

  // Handle play/pause state changes
  useEffect(() => {
    const audio = audioRef.current;
    if (!audio) return;

    if (state.isPlaying) {
      audio.play().catch(console.error);
    } else {
      audio.pause();
    }
  }, [state.isPlaying]);

  // Handle volume changes
  useEffect(() => {
    const audio = audioRef.current;
    if (!audio) return;
    
    audio.volume = state.volume / 100;
  }, [state.volume]);

  // Handle seek changes
  useEffect(() => {
    const audio = audioRef.current;
    if (!audio) return;
    
    if (Math.abs(audio.currentTime - state.currentTime) > 1) {
      audio.currentTime = state.currentTime;
    }
  }, [state.currentTime]);

  const shuffleArray = <T,>(array: T[]): T[] => {
    const newArray = [...array];
    for (let i = newArray.length - 1; i > 0; i--) {
      const j = Math.floor(Math.random() * (i + 1));
      [newArray[i], newArray[j]] = [newArray[j], newArray[i]];
    }
    return newArray;
  };

  const addToHistory = useCallback((track: Track, duration: number) => {
    setState(prev => {
      const newHistoryItem: HistoryItem = {
        track,
        playedAt: new Date(),
        duration,
      };
      const newHistory = [newHistoryItem, ...prev.history].slice(0, 100);
      return { ...prev, history: newHistory };
    });
  }, []);

  const addToRecentlyPlayed = useCallback((track: Track) => {
    setState(prev => {
      const filtered = prev.recentlyPlayed.filter(t => t.id !== track.id);
      const newRecentlyPlayed = [track, ...filtered].slice(0, 20);
      return { ...prev, recentlyPlayed: newRecentlyPlayed };
    });
  }, []);

  const playTrack = useCallback((track: Track, queue: Track[] = [track]) => {
    const trackIndex = queue.findIndex(t => t.id === track.id);
    addToRecentlyPlayed(track);
    setState(prev => ({
      ...prev,
      currentTrack: track,
      queue: prev.shuffle ? shuffleArray(queue) : queue,
      originalQueue: queue,
      currentTrackIndex: trackIndex >= 0 ? trackIndex : 0,
      isPlaying: true,
      currentTime: 0,
    }));
  }, [addToRecentlyPlayed]);

  const loadMoodPlaylist = useCallback(async (mood: string) => {
    try {
      console.log(`🎵 Loading ${mood} playlist from curated collection...`);
      
      // Show loading toast
      toast({ 
        title: "Loading Playlist", 
        description: `Curating ${mood} music for you...`,
        duration: 2000
      });
      
      // Get curated playlist tracks
      const curatedTracks = getMoodPlaylist(mood);
      
      if (curatedTracks.length === 0) {
        console.warn(`⚠️ No curated tracks for mood: ${mood}, falling back to API`);
        const apiTracks = await searchMoodifyTracks(`${mood} music`, 15);
        
        if (apiTracks.length > 0) {
          console.log(`✅ Loaded ${apiTracks.length} tracks from API for ${mood}`);
          
        toast({ 
          title: "Playlist Ready!", 
            description: `${apiTracks.length} ${mood} tracks loaded`,
          duration: 3000
        });
        
          addToRecentlyPlayed(apiTracks[0]);
        setState(prev => ({
          ...prev,
            currentTrack: apiTracks[0],
            queue: prev.shuffle ? shuffleArray(apiTracks) : apiTracks,
            originalQueue: apiTracks,
          currentTrackIndex: 0,
          isPlaying: true,
          currentTime: 0,
        }));
          return;
        }
        
        toast({ 
          title: "No Music Found", 
          description: `Couldn't find ${mood} music. Try another mood!`,
          variant: "destructive",
          duration: 4000
        });
        return;
      }
      
      // PERFORMANCE OPTIMIZATION: Load first track immediately, enrich rest in background
      console.log(`🎵 Loading first track immediately, enriching ${curatedTracks.length} tracks in background...`);
      
      // Enrich first track immediately for instant playback
      let firstTrackEnriched = curatedTracks[0];
      try {
        const searchResults = await searchMoodifyTracks(`${curatedTracks[0].title} ${curatedTracks[0].artist}`, 1);
        if (searchResults.length > 0) {
          firstTrackEnriched = {
            ...curatedTracks[0],
            url: searchResults[0].url,
            albumArt: searchResults[0].albumArt || curatedTracks[0].albumArt,
          };
        }
      } catch (error) {
        console.warn(`Could not enrich first track: ${curatedTracks[0].title}`, error);
      }
      
      // Start playback immediately with first track
      const initialQueue = [firstTrackEnriched, ...curatedTracks.slice(1)];
      addToRecentlyPlayed(firstTrackEnriched);
      setState(prev => ({
        ...prev,
        currentTrack: firstTrackEnriched,
        queue: prev.shuffle ? shuffleArray(initialQueue) : initialQueue,
        originalQueue: initialQueue,
        currentTrackIndex: 0,
        isPlaying: true,
        currentTime: 0,
      }));
      
      // Success toast
      toast({ 
        title: "Playlist Ready!", 
        description: `${curatedTracks.length} handpicked ${mood} tracks`,
        duration: 3000
      });
      
      // Enrich remaining tracks in background (non-blocking)
      Promise.all(
        curatedTracks.slice(1).map(async (track, index) => {
          const originalIndex = index + 1; // Calculate once
          try {
            const searchResults = await searchMoodifyTracks(`${track.title} ${track.artist}`, 1);
            if (searchResults.length > 0) {
              return {
                ...track,
                url: searchResults[0].url,
                albumArt: searchResults[0].albumArt || track.albumArt,
                originalIndex,
              };
            }
            return { ...track, originalIndex };
          } catch (error) {
            console.warn(`Could not enrich track: ${track.title}`, error);
            return { ...track, originalIndex };
          }
        })
      ).then(enrichedRest => {
        // Update queue with enriched tracks without disrupting playback
        // No need to sort since array is already in order (index + 1 is sequential)
        setState(prev => {
          const fullEnrichedQueue = [firstTrackEnriched, ...enrichedRest];
          return {
            ...prev,
            queue: prev.shuffle ? prev.queue : fullEnrichedQueue,
            originalQueue: fullEnrichedQueue,
          };
        });
        console.log(`✅ Background enrichment complete for ${enrichedRest.length} tracks`);
      }).catch(err => {
        console.warn('Background enrichment had some errors:', err);
      });
      
    } catch (error) {
      console.error('Failed to load mood playlist:', error);
      toast({ 
        title: "Connection Error", 
        description: "Unable to load music. Check your internet connection.",
        variant: "destructive",
        duration: 5000
      });
    }
  }, [addToRecentlyPlayed]);

  const playPlaylist = useCallback((tracks: Track[], startIndex: number = 0) => {
    if (tracks.length > 0 && startIndex < tracks.length) {
      addToRecentlyPlayed(tracks[startIndex]);
      setState(prev => ({
        ...prev,
        currentTrack: tracks[startIndex],
        queue: prev.shuffle ? shuffleArray(tracks) : tracks,
        originalQueue: tracks,
        currentTrackIndex: startIndex,
        isPlaying: true,
        currentTime: 0,
      }));
    }
  }, [addToRecentlyPlayed]);

  const togglePlayPause = useCallback(() => {
    setState(prev => ({
      ...prev,
      isPlaying: !prev.isPlaying,
    }));
  }, []);

  const playNext = useCallback(() => {
    setState(prev => {
      const nextIndex = prev.currentTrackIndex + 1;
      if (nextIndex < prev.queue.length) {
        addToRecentlyPlayed(prev.queue[nextIndex]);
        return {
          ...prev,
          currentTrackIndex: nextIndex,
          currentTrack: prev.queue[nextIndex],
          currentTime: 0,
          isPlaying: true,
        };
      } else if (prev.repeat === 'all' && prev.queue.length > 0) {
        addToRecentlyPlayed(prev.queue[0]);
        return {
          ...prev,
          currentTrackIndex: 0,
          currentTrack: prev.queue[0],
          currentTime: 0,
          isPlaying: true,
        };
      }
      return prev;
    });
  }, [addToRecentlyPlayed]);

  const playPrevious = useCallback(() => {
    setState(prev => {
      if (prev.currentTime > 3) {
        return {
          ...prev,
          currentTime: 0,
        };
      }
      
      const prevIndex = prev.currentTrackIndex - 1;
      if (prevIndex >= 0) {
        addToRecentlyPlayed(prev.queue[prevIndex]);
        return {
          ...prev,
          currentTrackIndex: prevIndex,
          currentTrack: prev.queue[prevIndex],
          currentTime: 0,
          isPlaying: true,
        };
      }
      return prev;
    });
  }, [addToRecentlyPlayed]);

  const seekTo = useCallback((time: number) => {
    setState(prev => ({
      ...prev,
      currentTime: Math.max(0, Math.min(time, prev.duration)),
    }));
  }, []);

  const setVolume = useCallback((volume: number) => {
    setState(prev => ({
      ...prev,
      volume: Math.max(0, Math.min(100, volume)),
    }));
  }, []);

  const toggleShuffle = useCallback(() => {
    setState(prev => {
      const newShuffle = !prev.shuffle;
      let newQueue = prev.queue;
      
      if (newShuffle) {
        const currentTrack = prev.currentTrack;
        const otherTracks = prev.originalQueue.filter(t => t.id !== currentTrack?.id);
        const shuffled = shuffleArray(otherTracks);
        newQueue = currentTrack ? [currentTrack, ...shuffled] : shuffled;
      } else {
        newQueue = prev.originalQueue;
      }

      return {
        ...prev,
        shuffle: newShuffle,
        queue: newQueue,
        currentTrackIndex: prev.currentTrack ? newQueue.findIndex(t => t.id === prev.currentTrack?.id) : 0,
      };
    });
    // Use stateRef to avoid dependency on state
    toast({ title: stateRef.current.shuffle ? "Shuffle Off" : "Shuffle On" });
  }, []);

  const toggleRepeat = useCallback(() => {
    setState(prev => {
      const modes: Array<'off' | 'all' | 'one'> = ['off', 'all', 'one'];
      const currentIndex = modes.indexOf(prev.repeat);
      const nextRepeat = modes[(currentIndex + 1) % modes.length];
      return { ...prev, repeat: nextRepeat };
    });
    const repeatLabels = { off: 'Repeat Off', all: 'Repeat All', one: 'Repeat One' };
    // Use stateRef to avoid dependency on state
    const nextMode = ({ off: 'all', all: 'one', one: 'off' } as const)[stateRef.current.repeat];
    toast({ title: repeatLabels[nextMode] });
  }, []);

  const toggleFavorite = useCallback((track: Track) => {
    setState(prev => {
      const isFavorite = prev.favorites.some(f => f.id === track.id);
      const newFavorites = isFavorite
        ? prev.favorites.filter(f => f.id !== track.id)
        : [...prev.favorites, track];
      
      toast({ 
        title: isFavorite ? "Removed from Favorites" : "Added to Favorites",
        description: track.title 
      });
      
      return { ...prev, favorites: newFavorites };
    });
  }, []);

  const isFavorite = useCallback((trackId: string) => {
    return state.favorites.some(f => f.id === trackId);
  }, [state.favorites]);

  const createPlaylist = useCallback((name: string) => {
    const newPlaylist: Playlist = {
      id: Date.now().toString(),
      name,
      tracks: [],
      createdAt: new Date(),
    };
    setState(prev => ({
      ...prev,
      playlists: [...prev.playlists, newPlaylist],
    }));
    toast({ title: "Playlist Created", description: name });
  }, []);

  const deletePlaylist = useCallback((playlistId: string) => {
    setState(prev => ({
      ...prev,
      playlists: prev.playlists.filter(p => p.id !== playlistId),
    }));
    toast({ title: "Playlist Deleted" });
  }, []);

  const renamePlaylist = useCallback((playlistId: string, newName: string) => {
    setState(prev => ({
      ...prev,
      playlists: prev.playlists.map(p => 
        p.id === playlistId ? { ...p, name: newName } : p
      ),
    }));
    toast({ title: "Playlist Renamed", description: newName });
  }, []);

  const addToPlaylist = useCallback((playlistId: string, track: Track) => {
    setState(prev => ({
      ...prev,
      playlists: prev.playlists.map(p => 
        p.id === playlistId && !p.tracks.find(t => t.id === track.id)
          ? { ...p, tracks: [...p.tracks, track] }
          : p
      ),
    }));
    toast({ title: "Added to Playlist", description: track.title });
  }, []);

  const removeFromPlaylist = useCallback((playlistId: string, trackId: string) => {
    setState(prev => ({
      ...prev,
      playlists: prev.playlists.map(p => 
        p.id === playlistId
          ? { ...p, tracks: p.tracks.filter(t => t.id !== trackId) }
          : p
      ),
    }));
    toast({ title: "Removed from Playlist" });
  }, []);

  const playFromPlaylist = useCallback((playlist: Playlist, startIndex: number = 0) => {
    if (playlist.tracks.length > 0 && startIndex < playlist.tracks.length) {
      playPlaylist(playlist.tracks, startIndex);
    }
  }, [playPlaylist]);

  const exportPlaylist = useCallback((playlistId: string) => {
    const playlist = state.playlists.find(p => p.id === playlistId);
    if (!playlist) return;

    const data = JSON.stringify(playlist, null, 2);
    const blob = new Blob([data], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `${playlist.name}.json`;
    a.click();
    URL.revokeObjectURL(url);
    toast({ title: "Playlist Exported", description: playlist.name });
  }, [state.playlists]);

  const importPlaylist = useCallback((file: File) => {
    const reader = new FileReader();
    reader.onload = (e) => {
      try {
        const playlist = JSON.parse(e.target?.result as string);
        const newPlaylist: Playlist = {
          ...playlist,
          id: Date.now().toString(),
          createdAt: new Date(),
        };
        setState(prev => ({
          ...prev,
          playlists: [...prev.playlists, newPlaylist],
        }));
        toast({ title: "Playlist Imported", description: newPlaylist.name });
      } catch (error) {
        toast({ title: "Import Failed", description: "Invalid playlist file", variant: "destructive" });
      }
    };
    reader.readAsText(file);
  }, []);

  const clearHistory = useCallback(() => {
    setState(prev => ({ ...prev, history: [] }));
    toast({ title: "History Cleared" });
  }, []);

  const removeFromQueue = useCallback((index: number) => {
    setState(prev => {
      const newQueue = [...prev.queue];
      newQueue.splice(index, 1);
      
      let newIndex = prev.currentTrackIndex;
      if (index < prev.currentTrackIndex) {
        newIndex--;
      } else if (index === prev.currentTrackIndex && newQueue.length > 0) {
        newIndex = Math.min(newIndex, newQueue.length - 1);
      }

      return {
        ...prev,
        queue: newQueue,
        currentTrackIndex: newIndex,
        currentTrack: newQueue[newIndex] || null,
      };
    });
  }, []);

  const clearQueue = useCallback(() => {
    setState(prev => ({
      ...prev,
      queue: prev.currentTrack ? [prev.currentTrack] : [],
      currentTrackIndex: prev.currentTrack ? 0 : -1,
    }));
    toast({ title: "Queue Cleared" });
  }, []);

  const addToQueue = useCallback((track: Track) => {
    setState(prev => ({
      ...prev,
      queue: [...prev.queue, track],
    }));
    toast({ title: "Added to Queue", description: track.title });
  }, []);

  return {
    ...state,
    playTrack,
    playPlaylist,
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
  };
};
