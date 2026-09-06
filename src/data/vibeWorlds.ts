import type { Track } from '@/data/mockMusic';
import truckWalaScene from '@/assets/vibe-truck-wala.jpg';
import deluxeSaloonScene from '@/assets/vibe-deluxe-saloon.jpg';
import ganpatiScene from '@/assets/vibe-ganpati.jpg';
import baarishScene from '@/assets/vibe-baarish.jpg';
import localScene from '@/assets/vibe-local.jpg';
import raatScene from '@/assets/vibe-raat.jpg';
import qawwaliScene from '@/assets/vibe-qawwali.jpg';
import gharScene from '@/assets/vibe-ghar.jpg';

export interface VibeWorld {
  id: string;
  title: string;
  playlistName: string;
  tagline: string;
  why: string;
  tags: string[];
  artwork: string;
  accent: string;
  atmosphere: string;
  artFocus: string;
  playlistQueries: string[];
}

export const vibeWorlds: VibeWorld[] = [
  {
    id: 'truck-wala',
    title: 'ट्रक वाला',
    playlistName: 'Highway Hours',
    tagline: 'Long roads, good songs.',
    why: 'For open highways, chai at the next stop, and songs that sound better after sunset.',
    tags: ['roadtrip', 'nostalgia', 'hindisongs', 'highway'],
    artwork: truckWalaScene,
    accent: '#e07a3d',
    atmosphere: 'rgba(224, 122, 61, .18)',
    artFocus: 'center center',
    playlistQueries: [
      'truck driver highway hindi songs playlist',
      'highway hindi songs playlist',
      'road trip hindi bollywood playlist',
    ],
  },
  {
    id: 'deluxe-saloon',
    title: 'Deluxe Saloon',
    playlistName: 'Cuts & Conversations',
    tagline: 'Stories, people, old songs.',
    why: 'For slow afternoons, chai on the crate, and songs that know the regulars by name.',
    tags: ['conversations', 'classics', 'neighbourhood', 'chai'],
    artwork: deluxeSaloonScene,
    accent: '#c4785a',
    atmosphere: 'rgba(196, 120, 90, .16)',
    artFocus: 'center center',
    playlistQueries: [
      'old hindi songs playlist',
      'classic bollywood songs playlist',
      'kishore kumar lata mangeshkar playlist',
    ],
  },
  {
    id: 'ganpati-bappa',
    title: 'गणपती बाप्पा मोरया',
    playlistName: 'Brighter Days',
    tagline: 'Celebration, nostalgia, togetherness.',
    why: 'For marigold light, shared work, and the kind of songs a whole street already knows.',
    tags: ['celebration', 'together', 'festival', 'morya'],
    artwork: ganpatiScene,
    accent: '#e2a23a',
    atmosphere: 'rgba(226, 162, 58, .16)',
    artFocus: 'center 48%',
    playlistQueries: [
      'ganpati bappa morya songs playlist',
      'ganesh chaturthi songs playlist',
      'ganpati aarti songs playlist',
    ],
  },
  {
    id: 'baarish',
    title: 'बारिश',
    playlistName: 'After the Rain',
    tagline: 'Wet streets, slow songs.',
    why: 'For the hour the rain finally settles and the city sounds like a radio in the next room.',
    tags: ['monsoon', 'rain', 'lateafternoon', 'hindi'],
    artwork: baarishScene,
    accent: '#7aa0c4',
    atmosphere: 'rgba(122, 160, 196, .16)',
    artFocus: 'center 42%',
    playlistQueries: [
      'monsoon hindi songs playlist',
      'barish romantic bollywood playlist',
      'rainy day hindi songs playlist',
    ],
  },
  {
    id: 'local-train',
    title: 'लोकल',
    playlistName: 'Next Stop',
    tagline: 'Doors open, city moving.',
    why: 'For the stretch between stations, when the wind is loud and the songs have to keep up.',
    tags: ['train', 'mumbai', 'commute', 'city'],
    artwork: localScene,
    accent: '#d4a24a',
    atmosphere: 'rgba(212, 162, 74, .16)',
    artFocus: 'center 46%',
    playlistQueries: [
      'mumbai local train songs playlist',
      'city life hindi songs playlist',
      'travel hindi bollywood playlist',
    ],
  },
  {
    id: 'raat-baki',
    title: 'रात अभी बाकी',
    playlistName: 'Still Awake',
    tagline: 'One more song after midnight.',
    why: 'For the terrace, the last cigarette of chai, and songs that do not ask you to sleep yet.',
    tags: ['latenight', 'insomnia', 'ghazal', 'quiet'],
    artwork: raatScene,
    accent: '#c48a6a',
    atmosphere: 'rgba(196, 138, 106, .16)',
    artFocus: 'center 40%',
    playlistQueries: [
      'late night hindi songs playlist',
      'sad night bollywood playlist',
      'ghazal night songs playlist',
    ],
  },
  {
    id: 'qawwali',
    title: 'कव्वाली',
    playlistName: 'Courtyard Voice',
    tagline: 'Lamps, voice, together.',
    why: 'For the hour a courtyard fills, and a voice is enough to hold the whole room.',
    tags: ['qawwali', 'sufi', 'courtyard', 'devotional'],
    artwork: qawwaliScene,
    accent: '#c9a24a',
    atmosphere: 'rgba(201, 162, 74, .16)',
    artFocus: 'center 50%',
    playlistQueries: [
      'qawwali songs playlist',
      'nusrat fateh ali khan playlist',
      'sufi hindi songs playlist',
    ],
  },
  {
    id: 'sunday-ghar',
    title: 'घर',
    playlistName: 'Sunday Nowhere',
    tagline: 'Fan, lunch, old radio.',
    why: 'For the afternoon you are not going anywhere, and the house already knows the songs.',
    tags: ['sunday', 'home', 'family', 'slow'],
    artwork: gharScene,
    accent: '#c47a4a',
    atmosphere: 'rgba(196, 122, 74, .16)',
    artFocus: 'center 48%',
    playlistQueries: [
      'family hindi songs playlist',
      'sunday afternoon bollywood playlist',
      'old radio hindi songs playlist',
    ],
  },
];

export const getVibeWorld = (id: string | undefined) => vibeWorlds.find((world) => world.id === id);

export const formatVibeDuration = (tracks: Track[]) => {
  const total = tracks.reduce((sum, track) => sum + (track.duration || 0), 0);
  const hours = Math.floor(total / 3600);
  const minutes = Math.round((total % 3600) / 60);
  if (hours <= 0) return `${minutes} min`;
  return `${hours} hr ${minutes} min`;
};
