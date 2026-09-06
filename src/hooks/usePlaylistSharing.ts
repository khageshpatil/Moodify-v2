import { useState, useCallback } from 'react';
import { Track } from '@/data/mockMusic';
import { Playlist } from './useMusicPlayer';
import { toast } from './use-toast';
import {
  generateShareableURL,
  decodePlaylistFromURL,
  convertToFullPlaylist,
  canShareViaURL,
  estimateURLLength,
  MAX_TRACKS_FOR_URL_SHARE,
} from '@/utils/playlistSharing';

export interface SharedPlaylist extends Playlist {
  shareId: string;
  isPublic: boolean;
  sharedBy: {
    name: string;
    avatar?: string;
    identity?: {
      anonId: string;
      displayName: string;
      avatarSeed: string;
    };
  };
  sharedAt: Date;
  collaborators: string[];
  shareCount: number;
  likes: number;
}

export interface PlaylistShare {
  id: string;
  playlistId: string;
  shareCode: string;
  qrCode?: string;
  expiresAt?: Date;
  isPublic: boolean;
  allowCollaboration: boolean;
  password?: string;
  accessCount: number;
  createdAt: Date;
}

export interface CollaborationInvite {
  id: string;
  playlistId: string;
  invitedBy: string;
  invitedUser: string;
  permissions: 'view' | 'edit' | 'admin';
  status: 'pending' | 'accepted' | 'declined';
  createdAt: Date;
}

interface PlaylistSharingState {
  sharedPlaylists: SharedPlaylist[];
  activeShares: PlaylistShare[];
  collaborationInvites: CollaborationInvite[];
  discoveredPlaylists: SharedPlaylist[];
}

const STORAGE_KEYS = {
  SHARED_PLAYLISTS: 'moodify_shared_playlists',
  ACTIVE_SHARES: 'moodify_active_shares',
  COLLABORATION_INVITES: 'moodify_collaboration_invites',
  DISCOVERED_PLAYLISTS: 'moodify_discovered_playlists',
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

export const usePlaylistSharing = (currentUser: { 
  name: string; 
  avatar?: string; 
  identity?: { anonId: string; displayName: string; avatarSeed: string } | null;
}) => {
  const [state, setState] = useState<PlaylistSharingState>({
    sharedPlaylists: loadFromStorage(STORAGE_KEYS.SHARED_PLAYLISTS, []),
    activeShares: loadFromStorage(STORAGE_KEYS.ACTIVE_SHARES, []),
    collaborationInvites: loadFromStorage(STORAGE_KEYS.COLLABORATION_INVITES, []),
    discoveredPlaylists: loadFromStorage(STORAGE_KEYS.DISCOVERED_PLAYLISTS, []),
  });

  // Generate unique share code
  const generateShareCode = useCallback(() => {
    return Math.random().toString(36).substring(2, 8).toUpperCase();
  }, []);

  // Generate QR code data URL
  const generateQRCode = useCallback(async (shareUrl: string): Promise<string> => {
    // In a real app, you'd use a QR code library like qrcode
    // For now, return a placeholder
    return `https://api.qrserver.com/v1/create-qr-code/?size=200x200&data=${encodeURIComponent(shareUrl)}`;
  }, []);

  // Create shareable link for playlist
  const createPlaylistShare = useCallback(async (
    playlist: Playlist,
    options: {
      isPublic?: boolean;
      allowCollaboration?: boolean;
      expiresIn?: number; // hours
      password?: string;
    } = {}
  ): Promise<PlaylistShare> => {
    const shareCode = generateShareCode();
    const shareId = `share_${Date.now()}`;
    
    const share: PlaylistShare = {
      id: shareId,
      playlistId: playlist.id,
      shareCode,
      isPublic: options.isPublic ?? true,
      allowCollaboration: options.allowCollaboration ?? false,
      password: options.password,
      expiresAt: options.expiresIn ? new Date(Date.now() + options.expiresIn * 60 * 60 * 1000) : undefined,
      accessCount: 0,
      createdAt: new Date(),
    };

    // Generate QR code
    const shareUrl = `${window.location.origin}/shared/${shareCode}`;
    share.qrCode = await generateQRCode(shareUrl);

    setState(prev => {
      const newState = {
        ...prev,
        activeShares: [...prev.activeShares, share],
      };
      saveToStorage(STORAGE_KEYS.ACTIVE_SHARES, newState.activeShares);
      return newState;
    });

    // If public, add to shared playlists
    if (options.isPublic) {
      const sharedPlaylist: SharedPlaylist = {
        ...playlist,
        shareId: shareId,
        isPublic: true,
        sharedBy: {
          name: currentUser.identity?.displayName || currentUser.name,
          avatar: currentUser.avatar,
          identity: currentUser.identity || undefined,
        },
        sharedAt: new Date(),
        collaborators: [],
        shareCount: 0,
        likes: 0,
      };

      setState(prev => {
        const newState = {
          ...prev,
          sharedPlaylists: [...prev.sharedPlaylists, sharedPlaylist],
        };
        saveToStorage(STORAGE_KEYS.SHARED_PLAYLISTS, newState.sharedPlaylists);
        return newState;
      });
    }

    toast({
      title: 'Playlist Shared!',
      description: `Share code: ${shareCode}`,
    });

    return share;
  }, [generateShareCode, generateQRCode, currentUser]);

  // Access shared playlist by code
  const accessSharedPlaylist = useCallback(async (shareCode: string, password?: string): Promise<SharedPlaylist | null> => {
    // In a real app, this would make an API call
    // For now, simulate with local storage
    
    const share = state.activeShares.find(s => s.shareCode === shareCode);
    if (!share) {
      toast({
        title: 'Invalid Share Code',
        description: 'The playlist share code is not valid or has expired.',
        variant: 'destructive',
      });
      return null;
    }

    // Check if expired
    if (share.expiresAt && new Date() > share.expiresAt) {
      toast({
        title: 'Share Expired',
        description: 'This playlist share has expired.',
        variant: 'destructive',
      });
      return null;
    }

    // Check password
    if (share.password && share.password !== password) {
      toast({
        title: 'Incorrect Password',
        description: 'Please enter the correct password.',
        variant: 'destructive',
      });
      return null;
    }

    // Find the shared playlist
    const sharedPlaylist = state.sharedPlaylists.find(p => p.shareId === share.id);
    if (!sharedPlaylist) {
      toast({
        title: 'Playlist Not Found',
        description: 'The shared playlist could not be found.',
        variant: 'destructive',
      });
      return null;
    }

    // Increment access count
    setState(prev => {
      const updatedShares = prev.activeShares.map(s => 
        s.id === share.id ? { ...s, accessCount: s.accessCount + 1 } : s
      );
      const newState = { ...prev, activeShares: updatedShares };
      saveToStorage(STORAGE_KEYS.ACTIVE_SHARES, newState.activeShares);
      return newState;
    });

    // Add to discovered playlists if not already there
    setState(prev => {
      const exists = prev.discoveredPlaylists.some(p => p.shareId === sharedPlaylist.shareId);
      if (!exists) {
        const newState = {
          ...prev,
          discoveredPlaylists: [...prev.discoveredPlaylists, sharedPlaylist],
        };
        saveToStorage(STORAGE_KEYS.DISCOVERED_PLAYLISTS, newState.discoveredPlaylists);
        return newState;
      }
      return prev;
    });

    toast({
      title: 'Playlist Accessed!',
      description: `Found "${sharedPlaylist.name}" by ${sharedPlaylist.sharedBy.name}`,
    });

    return sharedPlaylist;
  }, [state.activeShares, state.sharedPlaylists]);

  // Invite collaborator to playlist
  const inviteCollaborator = useCallback((
    playlistId: string,
    invitedUser: string,
    permissions: 'view' | 'edit' | 'admin' = 'edit'
  ) => {
    const invite: CollaborationInvite = {
      id: `invite_${Date.now()}`,
      playlistId,
      invitedBy: currentUser.name,
      invitedUser,
      permissions,
      status: 'pending',
      createdAt: new Date(),
    };

    setState(prev => {
      const newState = {
        ...prev,
        collaborationInvites: [...prev.collaborationInvites, invite],
      };
      saveToStorage(STORAGE_KEYS.COLLABORATION_INVITES, newState.collaborationInvites);
      return newState;
    });

    toast({
      title: 'Invitation Sent!',
      description: `Invited ${invitedUser} to collaborate on your playlist.`,
    });

    return invite;
  }, [currentUser.name]);

  // Accept collaboration invite
  const acceptCollaborationInvite = useCallback((inviteId: string) => {
    setState(prev => {
      const updatedInvites = prev.collaborationInvites.map(invite =>
        invite.id === inviteId ? { ...invite, status: 'accepted' as const } : invite
      );
      
      const acceptedInvite = updatedInvites.find(i => i.id === inviteId);
      if (acceptedInvite) {
        // Add user as collaborator to the playlist
        const updatedSharedPlaylists = prev.sharedPlaylists.map(playlist =>
          playlist.id === acceptedInvite.playlistId
            ? { ...playlist, collaborators: [...playlist.collaborators, currentUser.name] }
            : playlist
        );
        
        const newState = {
          ...prev,
          collaborationInvites: updatedInvites,
          sharedPlaylists: updatedSharedPlaylists,
        };
        
        saveToStorage(STORAGE_KEYS.COLLABORATION_INVITES, newState.collaborationInvites);
        saveToStorage(STORAGE_KEYS.SHARED_PLAYLISTS, newState.sharedPlaylists);
        return newState;
      }
      
      return prev;
    });

    toast({
      title: 'Invitation Accepted!',
      description: 'You can now collaborate on this playlist.',
    });
  }, [currentUser.name]);

  // Like a shared playlist
  const likePlaylist = useCallback((shareId: string) => {
    setState(prev => {
      const updatedSharedPlaylists = prev.sharedPlaylists.map(playlist =>
        playlist.shareId === shareId
          ? { ...playlist, likes: playlist.likes + 1 }
          : playlist
      );
      
      const newState = { ...prev, sharedPlaylists: updatedSharedPlaylists };
      saveToStorage(STORAGE_KEYS.SHARED_PLAYLISTS, newState.sharedPlaylists);
      return newState;
    });

    toast({
      title: 'Playlist Liked!',
      description: 'Thanks for the love! ❤️',
    });
  }, []);

  // Get sharing stats for a playlist
  const getPlaylistStats = useCallback((playlistId: string) => {
    const shares = state.activeShares.filter(s => s.playlistId === playlistId);
    const totalAccess = shares.reduce((sum, share) => sum + share.accessCount, 0);
    const sharedPlaylist = state.sharedPlaylists.find(p => p.id === playlistId);
    
    return {
      shareCount: shares.length,
      totalAccess,
      likes: sharedPlaylist?.likes || 0,
      collaborators: sharedPlaylist?.collaborators.length || 0,
    };
  }, [state.activeShares, state.sharedPlaylists]);

  // Generate social media share text
  const generateSocialShareText = useCallback((playlist: Playlist, shareCode: string) => {
    const shareUrl = `${window.location.origin}/shared/${shareCode}`;
    return {
      twitter: `🎵 Check out my "${playlist.name}" playlist on MoodiFy KP! ${playlist.tracks.length} amazing tracks waiting for you. ${shareUrl} #MoodiFyKP #Music`,
      facebook: `I just shared my "${playlist.name}" playlist on MoodiFy KP! 🎵 It has ${playlist.tracks.length} incredible songs. Come listen with me!`,
      whatsapp: `🎵 Hey! I created this awesome "${playlist.name}" playlist on MoodiFy KP with ${playlist.tracks.length} songs. Check it out: ${shareUrl}`,
      instagram: `🎵 New playlist alert! "${playlist.name}" is live on MoodiFy KP ✨ ${playlist.tracks.length} tracks of pure vibes 🎶 Link in bio!`,
    };
  }, []);

  // Create URL-encoded shareable link (works without backend!)
  const createURLShare = useCallback(async (playlist: Playlist, password?: string): Promise<{ success: boolean; url?: string; qrCode?: string; message: string; isEncrypted?: boolean }> => {
    // Validate playlist can be shared via URL
    const validation = canShareViaURL(playlist);
    
    if (!validation.canShare) {
      toast({
        title: 'Cannot Share via URL',
        description: validation.message,
        variant: 'destructive',
      });
      return {
        success: false,
        message: validation.message,
      };
    }

    try {
      // Generate shareable URL (with optional encryption)
      const shareUrl = await generateShareableURL(playlist, window.location.origin, password);
      
      // Generate QR code
      const qrCodeUrl = `https://api.qrserver.com/v1/create-qr-code/?size=300x300&data=${encodeURIComponent(shareUrl)}`;
      
      const urlLength = await estimateURLLength(playlist, password);
      const isEncrypted = !!password;
      
      toast({
        title: isEncrypted ? '🔒 Encrypted Share Link Created!' : '✅ Share Link Created!',
        description: isEncrypted 
          ? `Password-protected link for "${playlist.name}" (${playlist.tracks.length} tracks, ~${urlLength} chars)`
          : `Anyone with this link can import your playlist (${playlist.tracks.length} tracks, ~${urlLength} chars)`,
      });

      return {
        success: true,
        url: shareUrl,
        qrCode: qrCodeUrl,
        message: `Shareable link created for "${playlist.name}"`,
        isEncrypted,
      };
    } catch (error) {
      const errorMessage = error instanceof Error ? error.message : 'Failed to create share link';
      toast({
        title: 'Share Failed',
        description: errorMessage,
        variant: 'destructive',
      });
      return {
        success: false,
        message: errorMessage,
      };
    }
  }, []);

  // Import playlist from URL-encoded data
  const importFromURLShare = useCallback(async (encodedData: string, password?: string): Promise<Playlist | null> => {
    try {
      // Decode the playlist (with optional password)
      const compactPlaylist = await decodePlaylistFromURL(encodedData, password);
      
      if (!compactPlaylist) {
        toast({
          title: 'Import Failed',
          description: 'Invalid or corrupted playlist link',
          variant: 'destructive',
        });
        return null;
      }

      // Convert to full playlist format
      const playlist = convertToFullPlaylist(compactPlaylist);
      
      toast({
        title: '✅ Playlist Imported!',
        description: `"${playlist.name}" with ${playlist.tracks.length} tracks added to your library`,
      });

      return playlist;
    } catch (error) {
      // Check if password is required
      if (error instanceof Error && error.message.includes('PASSWORD_REQUIRED')) {
        toast({
          title: '🔒 Password Required',
          description: 'This playlist is password-protected. Please enter the password to import.',
          variant: 'destructive',
        });
        // Return a special error that the UI can handle
        throw new Error('PASSWORD_REQUIRED');
      }
      
      const errorMessage = error instanceof Error ? error.message : 'Failed to import playlist';
      toast({
        title: 'Import Failed',
        description: errorMessage,
        variant: 'destructive',
      });
      return null;
    }
  }, []);

  // Export functions
  return {
    state,
    createPlaylistShare,
    accessSharedPlaylist,
    inviteCollaborator,
    acceptCollaborationInvite,
    likePlaylist,
    getPlaylistStats,
    generateSocialShareText,
    createURLShare,
    importFromURLShare,
    canShareViaURL,
    MAX_TRACKS_FOR_URL_SHARE,
  };
};
