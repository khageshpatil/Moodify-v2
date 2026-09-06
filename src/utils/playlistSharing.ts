import LZString from 'lz-string';
import { Playlist } from '@/hooks/useMusicPlayer';
import { Track } from '@/data/mockMusic';
import { encryptWithPassword, decryptWithPassword } from './encryption';

export const MAX_TRACKS_FOR_URL_SHARE = 20;

export interface CompactPlaylist {
  name: string;
  tracks: CompactTrack[];
  createdAt: number;
}

export interface CompactTrack {
  id: string;
  title: string;
  artist: string;
  album?: string;
  duration?: number;
}

/**
 * Compress and encode playlist data for URL sharing (with optional password protection)
 * @param playlist - The playlist to share
 * @param password - Optional password for encryption
 * @returns Encoded string that can be used in URL
 */
export const encodePlaylistForURL = async (playlist: Playlist, password?: string): Promise<string> => {
  // Validate track count
  if (playlist.tracks.length > MAX_TRACKS_FOR_URL_SHARE) {
    throw new Error(`Playlist has ${playlist.tracks.length} tracks. Maximum ${MAX_TRACKS_FOR_URL_SHARE} tracks allowed for URL sharing.`);
  }

  // Create compact version (remove unnecessary data)
  const compactPlaylist: CompactPlaylist = {
    name: playlist.name,
    tracks: playlist.tracks.map(track => ({
      id: track.id,
      title: track.title,
      artist: track.artist,
      album: track.album,
      duration: track.duration,
    })),
    createdAt: Date.now(),
  };

  // Convert to JSON
  const json = JSON.stringify(compactPlaylist);
  
  let dataToCompress = json;
  
  // Encrypt if password provided
  if (password) {
    const encrypted = await encryptWithPassword(json, password);
    dataToCompress = `ENC:${encrypted}`; // Mark as encrypted
  }
  
  // Compress
  const compressed = LZString.compressToEncodedURIComponent(dataToCompress);

  return compressed;
};

/**
 * Decode and decompress playlist data from URL (with optional password decryption)
 * @param encoded - The encoded playlist string from URL
 * @param password - Optional password if playlist is encrypted
 * @returns Decoded playlist object
 */
export const decodePlaylistFromURL = async (encoded: string, password?: string): Promise<CompactPlaylist | null> => {
  try {
    // Decompress
    const decompressed = LZString.decompressFromEncodedURIComponent(encoded);
    
    if (!decompressed) {
      console.error('Failed to decompress playlist data');
      return null;
    }

    let json = decompressed;
    
    // Check if encrypted
    if (decompressed.startsWith('ENC:')) {
      const encryptedData = decompressed.substring(4); // Remove 'ENC:' prefix
      
      if (!password) {
        throw new Error('PASSWORD_REQUIRED');
      }
      
      // Decrypt
      json = await decryptWithPassword(encryptedData, password);
    }

    // Parse JSON
    const playlist: CompactPlaylist = JSON.parse(json);

    // Validate structure
    if (!playlist.name || !Array.isArray(playlist.tracks)) {
      console.error('Invalid playlist structure');
      return null;
    }

    // Validate track count
    if (playlist.tracks.length > MAX_TRACKS_FOR_URL_SHARE) {
      console.error(`Playlist exceeds maximum track limit (${MAX_TRACKS_FOR_URL_SHARE})`);
      return null;
    }

    return playlist;
  } catch (error) {
    if (error instanceof Error && error.message === 'PASSWORD_REQUIRED') {
      throw error;
    }
    console.error('Failed to decode playlist:', error);
    return null;
  }
};

/**
 * Generate shareable URL for a playlist
 * @param playlist - The playlist to share
 * @param baseUrl - Base URL of the application (e.g., window.location.origin)
 * @param password - Optional password for encryption
 * @returns Complete shareable URL
 */
export const generateShareableURL = async (playlist: Playlist, baseUrl: string, password?: string): Promise<string> => {
  const encoded = await encodePlaylistForURL(playlist, password);
  return `${baseUrl}/#/shared/${encoded}`;
};

/**
 * Convert compact playlist to full playlist format
 * @param compactPlaylist - The compact playlist from URL
 * @returns Full playlist object that can be imported
 */
export const convertToFullPlaylist = (compactPlaylist: CompactPlaylist): Playlist => {
  const tracks: Track[] = compactPlaylist.tracks.map(track => ({
    id: track.id,
    title: track.title,
    artist: track.artist,
    album: track.album || 'Unknown Album',
    albumArt: '', // Filled when the track is resolved by the active provider
    duration: track.duration || 0,
    url: '', // Resolved through the active playback provider when needed
  }));

  return {
    id: `imported_${Date.now()}`,
    name: compactPlaylist.name,
    tracks,
    createdAt: new Date(compactPlaylist.createdAt),
  };
};

/**
 * Calculate approximate URL length for a playlist
 * @param playlist - The playlist to check
 * @param password - Optional password if using encryption
 * @returns Estimated URL length in characters
 */
export const estimateURLLength = async (playlist: Playlist, password?: string): Promise<number> => {
  try {
    const encoded = await encodePlaylistForURL(playlist, password);
    const baseLength = 50; // Approximate base URL length
    return baseLength + encoded.length;
  } catch {
    return 0;
  }
};

/**
 * Check if encoded data is encrypted
 * @param encoded - The encoded playlist string
 * @returns True if encrypted
 */
export const isPlaylistEncrypted = (encoded: string): boolean => {
  try {
    const decompressed = LZString.decompressFromEncodedURIComponent(encoded);
    return decompressed?.startsWith('ENC:') || false;
  } catch {
    return false;
  }
};

/**
 * Check if playlist is suitable for URL sharing
 * @param playlist - The playlist to check
 * @returns Object with validation result and message
 */
export const canShareViaURL = (playlist: Playlist): { canShare: boolean; message: string } => {
  if (playlist.tracks.length === 0) {
    return {
      canShare: false,
      message: 'Playlist is empty',
    };
  }

  if (playlist.tracks.length > MAX_TRACKS_FOR_URL_SHARE) {
    return {
      canShare: false,
      message: `Playlist has ${playlist.tracks.length} tracks. Maximum ${MAX_TRACKS_FOR_URL_SHARE} tracks allowed for URL sharing. Use JSON export for larger playlists.`,
    };
  }

  // Note: Can't check URL length synchronously anymore due to encryption
  // Will validate during share creation instead

  return {
    canShare: true,
    message: 'Playlist ready to share',
  };
};
