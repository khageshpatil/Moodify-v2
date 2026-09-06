import { useState, useCallback, useEffect } from 'react';
import { Track } from '@/data/mockMusic';
import { SharedPlaylist } from './usePlaylistSharing';

// Anonymous user identity stored locally
export interface AnonymousUser {
  id: string;
  displayName: string;
  avatar: string;
  createdAt: Date;
  sessionCount: number;
  favoriteGenres: string[];
}

// Discovered content from other users
export interface DiscoveredContent {
  id: string;
  type: 'playlist' | 'track' | 'mood_session';
  name: string;
  description?: string;
  tracks: Track[];
  sharedBy: string; // Anonymous display name
  sharedAt: Date;
  likes: number;
  accessCount: number;
  tags: string[];
}

// Temporary listening session
export interface ListeningSession {
  id: string;
  name: string;
  hostName: string;
  currentTrack?: Track;
  playlist: Track[];
  participants: string[];
  isPublic: boolean;
  createdAt: Date;
  mood?: string;
}

interface LocalSocialState {
  user: AnonymousUser;
  discoveredContent: DiscoveredContent[];
  activeListeningSessions: ListeningSession[];
  recentConnections: string[];
  trendingContent: DiscoveredContent[];
}

const STORAGE_KEYS = {
  ANONYMOUS_USER: 'moodify_anonymous_user',
  DISCOVERED_CONTENT: 'moodify_discovered_content',
  RECENT_CONNECTIONS: 'moodify_recent_connections',
};

// Generate anonymous identity
const generateAnonymousUser = (): AnonymousUser => {
  const adjectives = ['Musical', 'Melodic', 'Rhythmic', 'Harmonic', 'Sonic', 'Acoustic', 'Vibrant', 'Dynamic'];
  const nouns = ['Explorer', 'Listener', 'Curator', 'Dreamer', 'Wanderer', 'Creator', 'Seeker', 'Lover'];
  const colors = ['#FF6B6B', '#4ECDC4', '#45B7D1', '#96CEB4', '#FFEAA7', '#DDA0DD', '#98D8C8', '#F7DC6F'];
  
  const adjective = adjectives[Math.floor(Math.random() * adjectives.length)];
  const noun = nouns[Math.floor(Math.random() * nouns.length)];
  const color = colors[Math.floor(Math.random() * colors.length)];
  
  return {
    id: `anon_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`,
    displayName: `${adjective} ${noun}`,
    avatar: `https://ui-avatars.com/api/?name=${adjective}+${noun}&background=${color.slice(1)}&color=fff&size=128`,
    createdAt: new Date(),
    sessionCount: 0,
    favoriteGenres: [],
  };
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

export const useLocalSocialFeatures = () => {
  const [state, setState] = useState<LocalSocialState>(() => {
    const user = loadFromStorage(STORAGE_KEYS.ANONYMOUS_USER, null) || generateAnonymousUser();
    return {
      user,
      discoveredContent: loadFromStorage(STORAGE_KEYS.DISCOVERED_CONTENT, []),
      activeListeningSessions: [],
      recentConnections: loadFromStorage(STORAGE_KEYS.RECENT_CONNECTIONS, []),
      trendingContent: [],
    };
  });

  // Save user to storage when it changes
  useEffect(() => {
    saveToStorage(STORAGE_KEYS.ANONYMOUS_USER, state.user);
  }, [state.user]);

  // Save discovered content
  useEffect(() => {
    saveToStorage(STORAGE_KEYS.DISCOVERED_CONTENT, state.discoveredContent);
  }, [state.discoveredContent]);

  // Generate mock trending content
  const generateTrendingContent = useCallback(() => {
    const mockContent: DiscoveredContent[] = [
      {
        id: 'trending_1',
        type: 'playlist',
        name: 'Chill Vibes Collection',
        description: 'Perfect for late night coding sessions',
        tracks: [],
        sharedBy: 'Melodic Explorer',
        sharedAt: new Date(Date.now() - 2 * 60 * 60 * 1000), // 2 hours ago
        likes: 47,
        accessCount: 156,
        tags: ['chill', 'ambient', 'focus'],
      },
      {
        id: 'trending_2',
        type: 'playlist',
        name: 'Workout Energy Boost',
        description: 'High energy tracks to power your workout',
        tracks: [],
        sharedBy: 'Dynamic Creator',
        sharedAt: new Date(Date.now() - 5 * 60 * 60 * 1000), // 5 hours ago
        likes: 32,
        accessCount: 89,
        tags: ['workout', 'energy', 'motivation'],
      },
      {
        id: 'trending_3',
        type: 'mood_session',
        name: 'Melancholy Evening',
        description: 'Shared listening session for contemplative moods',
        tracks: [],
        sharedBy: 'Harmonic Dreamer',
        sharedAt: new Date(Date.now() - 1 * 60 * 60 * 1000), // 1 hour ago
        likes: 23,
        accessCount: 45,
        tags: ['melancholy', 'evening', 'contemplative'],
      },
    ];

    setState(prev => ({ ...prev, trendingContent: mockContent }));
  }, []);

  // Add discovered content when accessing shared playlists
  const addDiscoveredContent = useCallback((content: Omit<DiscoveredContent, 'id' | 'sharedAt' | 'likes' | 'accessCount'>) => {
    const newContent: DiscoveredContent = {
      ...content,
      id: `discovered_${Date.now()}`,
      sharedAt: new Date(),
      likes: 0,
      accessCount: 1,
    };

    setState(prev => {
      const updated = [newContent, ...prev.discoveredContent].slice(0, 50); // Keep last 50
      return { ...prev, discoveredContent: updated };
    });
  }, []);

  // Like discovered content
  const likeContent = useCallback((contentId: string) => {
    setState(prev => ({
      ...prev,
      discoveredContent: prev.discoveredContent.map(content =>
        content.id === contentId
          ? { ...content, likes: content.likes + 1 }
          : content
      ),
      trendingContent: prev.trendingContent.map(content =>
        content.id === contentId
          ? { ...content, likes: content.likes + 1 }
          : content
      ),
    }));
  }, []);

  // Update user profile
  const updateUserProfile = useCallback((updates: Partial<AnonymousUser>) => {
    setState(prev => ({
      ...prev,
      user: { ...prev.user, ...updates },
    }));
  }, []);

  // Add recent connection
  const addRecentConnection = useCallback((connectionName: string) => {
    setState(prev => {
      const updated = [connectionName, ...prev.recentConnections.filter(c => c !== connectionName)].slice(0, 10);
      saveToStorage(STORAGE_KEYS.RECENT_CONNECTIONS, updated);
      return { ...prev, recentConnections: updated };
    });
  }, []);

  // Generate recommendations based on user's listening history
  const generateRecommendations = useCallback((userPlaylists: any[], userFavorites: Track[]) => {
    // Simple recommendation algorithm based on user's favorite genres
    const userGenres = state.user.favoriteGenres;
    const recommendations = state.trendingContent.filter(content =>
      content.tags.some(tag => userGenres.includes(tag))
    );

    return recommendations.slice(0, 5);
  }, [state.user.favoriteGenres, state.trendingContent]);

  // Create anonymous listening session
  const createListeningSession = useCallback((playlist: Track[], mood?: string, isPublic: boolean = true) => {
    const session: ListeningSession = {
      id: `session_${Date.now()}`,
      name: `${state.user.displayName}'s ${mood || 'Music'} Session`,
      hostName: state.user.displayName,
      playlist,
      participants: [state.user.displayName],
      isPublic,
      createdAt: new Date(),
      mood,
    };

    setState(prev => ({
      ...prev,
      activeListeningSessions: [session, ...prev.activeListeningSessions.slice(0, 9)],
    }));

    return session;
  }, [state.user.displayName]);

  // Get mock active sessions
  const getMockActiveSessions = useCallback((): ListeningSession[] => {
    return [
      {
        id: 'session_1',
        name: 'Acoustic Explorer\'s Chill Session',
        hostName: 'Acoustic Explorer',
        participants: ['Acoustic Explorer', 'Melodic Wanderer'],
        isPublic: true,
        createdAt: new Date(Date.now() - 30 * 60 * 1000),
        mood: 'chill',
        playlist: [],
      },
      {
        id: 'session_2',
        name: 'Vibrant Creator\'s Workout Vibes',
        hostName: 'Vibrant Creator',
        participants: ['Vibrant Creator', 'Dynamic Seeker', 'Rhythmic Lover'],
        isPublic: true,
        createdAt: new Date(Date.now() - 15 * 60 * 1000),
        mood: 'workout',
        playlist: [],
      },
    ];
  }, []);

  // Initialize trending content
  useEffect(() => {
    generateTrendingContent();
  }, [generateTrendingContent]);

  return {
    user: state.user,
    discoveredContent: state.discoveredContent,
    trendingContent: state.trendingContent,
    recentConnections: state.recentConnections,
    activeSessions: getMockActiveSessions(),
    
    // Actions
    addDiscoveredContent,
    likeContent,
    updateUserProfile,
    addRecentConnection,
    generateRecommendations,
    createListeningSession,
    generateTrendingContent,
  };
};
