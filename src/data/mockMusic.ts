// Mock music data for demo purposes
// In production, search results come from the Moodify YouTube Music service.

export interface Track {
  id: string;
  title: string;
  artist: string;
  album: string;
  albumArt: string;
  duration: number;
  url?: string;
  provider?: string;
  providerId?: string;
  artistId?: string;
  albumId?: string;
}

export interface Playlist {
  id: string;
  name: string;
  description: string;
  tracks: Track[];
  moodId: string;
}

// Generate placeholder album art URLs
const generateAlbumArt = (seed: string) => 
  `https://picsum.photos/seed/${seed}/400/400`;

export const mockPlaylists: Record<string, Playlist> = {
  chill: {
    id: 'chill-playlist',
    name: 'Cherry Blossom Dreams',
    description: 'Peaceful melodies for serene moments',
    moodId: 'chill',
    tracks: [
      {
        id: 'chill-1',
        title: 'Sakura Whispers',
        artist: 'Ambient Collective',
        album: 'Spring Reverie',
        albumArt: generateAlbumArt('chill1'),
        duration: 245,
      },
      {
        id: 'chill-2',
        title: 'Mountain Breeze',
        artist: 'Zen Garden',
        album: 'Nature\'s Symphony',
        albumArt: generateAlbumArt('chill2'),
        duration: 198,
      },
      {
        id: 'chill-3',
        title: 'Moonlit Path',
        artist: 'Nocturne',
        album: 'Midnight Reflections',
        albumArt: generateAlbumArt('chill3'),
        duration: 267,
      },
      {
        id: 'chill-4',
        title: 'Gentle Rain',
        artist: 'Atmospheric',
        album: 'Weather Sounds',
        albumArt: generateAlbumArt('chill4'),
        duration: 312,
      },
    ],
  },
  melancholy: {
    id: 'melancholy-playlist',
    name: 'Rainy Day Reflections',
    description: 'Contemplative tunes for thoughtful moments',
    moodId: 'melancholy',
    tracks: [
      {
        id: 'melancholy-1',
        title: 'Empty Streets',
        artist: 'Solitude',
        album: 'Urban Soliloquy',
        albumArt: generateAlbumArt('melancholy1'),
        duration: 223,
      },
      {
        id: 'melancholy-2',
        title: 'Fading Memories',
        artist: 'Nostalgia',
        album: 'Yesterday\'s Echo',
        albumArt: generateAlbumArt('melancholy2'),
        duration: 189,
      },
      {
        id: 'melancholy-3',
        title: 'Window Panes',
        artist: 'Introspection',
        album: 'Inner Dialogues',
        albumArt: generateAlbumArt('melancholy3'),
        duration: 256,
      },
    ],
  },
  workout: {
    id: 'workout-playlist',
    name: 'Fire & Fury',
    description: 'High-energy beats to fuel your passion',
    moodId: 'workout',
    tracks: [
      {
        id: 'workout-1',
        title: 'Unstoppable Force',
        artist: 'Power Drive',
        album: 'Maximum Energy',
        albumArt: generateAlbumArt('workout1'),
        duration: 203,
      },
      {
        id: 'workout-2',
        title: 'Burning Desire',
        artist: 'Adrenaline Rush',
        album: 'Beast Mode',
        albumArt: generateAlbumArt('workout2'),
        duration: 187,
      },
      {
        id: 'workout-3',
        title: 'Thunder Strike',
        artist: 'Lightning Bolt',
        album: 'Storm Chaser',
        albumArt: generateAlbumArt('workout3'),
        duration: 195,
      },
    ],
  },
  focus: {
    id: 'focus-playlist',
    name: 'Deep Work Flow',
    description: 'Concentration-enhancing soundscapes',
    moodId: 'focus',
    tracks: [
      {
        id: 'focus-1',
        title: 'Neural Pathways',
        artist: 'Cognitive',
        album: 'Mind Palace',
        albumArt: generateAlbumArt('focus1'),
        duration: 432,
      },
      {
        id: 'focus-2',
        title: 'Binary Dreams',
        artist: 'Algorithm',
        album: 'Code Symphony',
        albumArt: generateAlbumArt('focus2'),
        duration: 378,
      },
      {
        id: 'focus-3',
        title: 'Quantum Leap',
        artist: 'Particle Physics',
        album: 'String Theory',
        albumArt: generateAlbumArt('focus3'),
        duration: 423,
      },
    ],
  },
  love: {
    id: 'love-playlist',
    name: 'Starlit Romance',
    description: 'Heartfelt melodies for tender moments',
    moodId: 'love',
    tracks: [
      {
        id: 'love-1',
        title: 'Eternal Embrace',
        artist: 'Romantic Soul',
        album: 'Love Letters',
        albumArt: generateAlbumArt('love1'),
        duration: 278,
      },
      {
        id: 'love-2',
        title: 'Dancing Stars',
        artist: 'Celestial Love',
        album: 'Cosmic Romance',
        albumArt: generateAlbumArt('love2'),
        duration: 234,
      },
      {
        id: 'love-3',
        title: 'Whispered Promises',
        artist: 'Tender Hearts',
        album: 'Sweet Serenades',
        albumArt: generateAlbumArt('love3'),
        duration: 289,
      },
    ],
  },
  party: {
    id: 'party-playlist',
    name: 'Gentle Celebration',
    description: 'Uplifting vibes for joyful moments',
    moodId: 'party',
    tracks: [
      {
        id: 'party-1',
        title: 'Sparkling Nights',
        artist: 'Celebration',
        album: 'Joy Unlimited',
        albumArt: generateAlbumArt('party1'),
        duration: 212,
      },
      {
        id: 'party-2',
        title: 'Golden Hour',
        artist: 'Sunshine Collective',
        album: 'Happy Days',
        albumArt: generateAlbumArt('party2'),
        duration: 198,
      },
      {
        id: 'party-3',
        title: 'Confetti Dreams',
        artist: 'Festive Spirit',
        album: 'Good Vibes Only',
        albumArt: generateAlbumArt('party3'),
        duration: 223,
      },
    ],
  },
};

export const getAllTracks = (): Track[] => {
  return Object.values(mockPlaylists).flatMap(playlist => playlist.tracks);
};
