// JioSaavn API integration with fallback sample tracks
// Updated: saavn.dev is down due to DMCA, using new working endpoint
const JIOSAAVN_BASE_URL = 'https://saavn.sumit.co/api';

// Alternative API endpoints to try if main one fails
const ALTERNATIVE_APIS = [
  'https://jiosaavn-api-privatecvc.vercel.app/api',
  'https://jiosaavn-api.vercel.app/api',
  'https://saavn.me/api',
  'https://saavn.dev/api' // Keep old one as backup in case it comes back
];

export interface JioSaavnTrack {
  id: string;
  name: string;
  primaryArtists: string;
  album: {
    name: string;
  };
  image: Array<{
    quality: string;
    url: string;
  }>;
  downloadUrl: Array<{
    quality: string;
    url: string;
  }>;
  duration: string;
}

export interface SearchResult {
  data: {
    results: JioSaavnTrack[];
  };
}

export interface PlaylistResult {
  data: JioSaavnTrack[];
}

// Convert JioSaavn track to our Track interface
export const convertToTrack = (jiosaavnTrack: JioSaavnTrack) => ({
  id: jiosaavnTrack.id || `track-${Date.now()}`,
  title: jiosaavnTrack.name || 'Unknown Title',
  artist: jiosaavnTrack.primaryArtists || 'Unknown Artist',
  album: jiosaavnTrack.album?.name || 'Unknown Album',
  albumArt: jiosaavnTrack.image?.[2]?.url || jiosaavnTrack.image?.[1]?.url || jiosaavnTrack.image?.[0]?.url || '',
  duration: parseInt(jiosaavnTrack.duration) || 0,
  url: jiosaavnTrack.downloadUrl?.[4]?.url || jiosaavnTrack.downloadUrl?.[3]?.url || jiosaavnTrack.downloadUrl?.[2]?.url || jiosaavnTrack.downloadUrl?.[1]?.url || jiosaavnTrack.downloadUrl?.[0]?.url,
});

// Enhanced search with multiple API fallbacks
export const searchSongs = async (query: string, limit: number = 10) => {
  const allApis = [JIOSAAVN_BASE_URL, ...ALTERNATIVE_APIS];
  
  for (const apiUrl of allApis) {
    try {
      console.log(`🔍 Trying API: ${apiUrl}`);
      const response = await fetch(`${apiUrl}/search/songs?query=${encodeURIComponent(query)}&limit=${limit}`, {
        method: 'GET',
        headers: {
          'Accept': 'application/json',
          'Content-Type': 'application/json',
        },
        mode: 'cors',
      });
      
      if (!response.ok) {
        console.warn(`API ${apiUrl} returned ${response.status}`);
        continue;
      }
      
      const data: SearchResult = await response.json();
      if (data.data?.results?.length > 0) {
        console.log(`✅ Success with ${apiUrl}: ${data.data.results.length} results`);
        return data.data.results.map(convertToTrack);
      }
    } catch (error) {
      console.warn(`❌ API ${apiUrl} failed:`, error);
      continue;
    }
  }
  
  console.error('🚫 All JioSaavn APIs failed for query:', query);
  return [];
};

// Get trending songs with multiple API fallbacks
export const getTrendingSongs = async (limit: number = 20) => {
  const allApis = [JIOSAAVN_BASE_URL, ...ALTERNATIVE_APIS];
  
  for (const apiUrl of allApis) {
    try {
      console.log(`🔥 Trying trending API: ${apiUrl}`);
      const response = await fetch(`${apiUrl}/search/songs?query=trending&limit=${limit}`, {
        method: 'GET',
        headers: {
          'Accept': 'application/json',
          'Content-Type': 'application/json',
        },
        mode: 'cors',
      });
      
      if (!response.ok) {
        console.warn(`Trending API ${apiUrl} returned ${response.status}`);
        continue;
      }
      
      const data: SearchResult = await response.json();
      if (data.data?.results?.length > 0) {
        console.log(`✅ Trending success with ${apiUrl}: ${data.data.results.length} results`);
        return data.data.results.map(convertToTrack);
      }
  } catch (error) {
      console.warn(`❌ Trending API ${apiUrl} failed:`, error);
        continue;
      }
    }
    
  console.error('🚫 All trending APIs failed');
  return [];
};

// Enhanced fallback tracks with working audio URLs and mood-specific content
const getFallbackTracks = (mood: string): any[] => {
  const moodData: Record<string, { genre: string; artist: string; color: string }> = {
    chill: { genre: 'Ambient', artist: 'Chill Collective', color: 'blue' },
    melancholy: { genre: 'Indie', artist: 'Melancholy Moon', color: 'purple' },
    workout: { genre: 'Electronic', artist: 'Energy Boost', color: 'red' },
    focus: { genre: 'Instrumental', artist: 'Focus Flow', color: 'green' },
    love: { genre: 'Romantic', artist: 'Love Letters', color: 'pink' },
    party: { genre: 'Dance', artist: 'Party People', color: 'orange' }
  };

  const moodInfo = moodData[mood] || { genre: 'Popular', artist: 'Various Artists', color: 'blue' };
  
  // Using royalty-free audio URLs that work with CORS
  const workingAudioUrls = [
    'https://www.learningcontainer.com/wp-content/uploads/2020/02/Kalimba.mp3',
    'https://file-examples.com/storage/fe68c8777d66f447a9512b4/2017/11/file_example_MP3_700KB.mp3',
    'https://www.soundjay.com/misc/sounds/bell-ringing-05.wav', // Keep as backup
  ];

  const fallbackTracks = [
    {
      id: `${mood}-fallback-1`,
      title: `${mood.charAt(0).toUpperCase() + mood.slice(1)} Vibes`,
      artist: moodInfo.artist,
      album: `${mood.charAt(0).toUpperCase() + mood.slice(1)} Collection`,
      albumArt: `https://picsum.photos/300/300?random=${mood.length}&blur=1`,
      duration: 180,
      url: workingAudioUrls[0],
      genre: moodInfo.genre
    },
    {
      id: `${mood}-fallback-2`, 
      title: `${mood.charAt(0).toUpperCase() + mood.slice(1)} Dreams`,
      artist: 'Ambient Sounds',
      album: `${mood.charAt(0).toUpperCase() + mood.slice(1)} Experience`,
      albumArt: `https://picsum.photos/300/300?random=${mood.length + 1}&blur=1`,
      duration: 240,
      url: workingAudioUrls[1],
      genre: moodInfo.genre
    },
    {
      id: `${mood}-fallback-3`,
      title: `${mood.charAt(0).toUpperCase() + mood.slice(1)} Journey`,
      artist: 'Melody Maker', 
      album: `${mood.charAt(0).toUpperCase() + mood.slice(1)} Stories`,
      albumArt: `https://picsum.photos/300/300?random=${mood.length + 2}&blur=1`,
      duration: 195,
      url: workingAudioUrls[0], // Reuse working URL
      genre: moodInfo.genre
    },
    {
      id: `${mood}-fallback-4`,
      title: `Deep ${mood.charAt(0).toUpperCase() + mood.slice(1)}`,
      artist: moodInfo.artist,
      album: `${mood.charAt(0).toUpperCase() + mood.slice(1)} Essentials`,
      albumArt: `https://picsum.photos/300/300?random=${mood.length + 3}&blur=1`,
      duration: 210,
      url: workingAudioUrls[1],
      genre: moodInfo.genre
    },
    {
      id: `${mood}-fallback-5`,
      title: `${mood.charAt(0).toUpperCase() + mood.slice(1)} Waves`,
      artist: 'Sound Therapy',
      album: `${mood.charAt(0).toUpperCase() + mood.slice(1)} Therapy`,
      albumArt: `https://picsum.photos/300/300?random=${mood.length + 4}&blur=1`,
      duration: 165,
      url: workingAudioUrls[0],
      genre: moodInfo.genre
    }
  ];
  
  console.log(`🎵 Generated ${fallbackTracks.length} fallback tracks for mood: ${mood}`);
  return fallbackTracks;
};

// Enhanced mood-based song loading with better fallback strategy
export const getSongsByMood = async (mood: string, limit: number = 15) => {
  console.log(`🎯 Loading ${mood} mood playlist...`);
  
  const moodQueries: Record<string, string[]> = {
    chill: ['chill out', 'relaxing music', 'ambient', 'lofi hip hop', 'peaceful'],
    melancholy: ['sad songs', 'emotional music', 'melancholy', 'heartbreak', 'indie sad'],
    workout: ['workout music', 'gym motivation', 'high energy', 'pump up', 'fitness'],
    focus: ['study music', 'concentration', 'instrumental focus', 'productivity', 'ambient work'],
    love: ['romantic songs', 'love ballads', 'valentine music', 'romance', 'love hits'],
    party: ['party hits', 'dance music', 'celebration songs', 'upbeat party', 'club music']
  };

  const queries = moodQueries[mood] || ['popular music', 'trending songs'];
  
  // Start with fallback tracks immediately for better UX
  const fallbackTracks = getFallbackTracks(mood);
  
  try {
    console.log(`🔍 Searching APIs for ${mood} music...`);
    
    // Try to get real music from APIs with timeout
    const searchPromises = queries.slice(0, 2).map(query => 
      Promise.race([
        searchSongs(query, Math.ceil(limit / 2)),
        new Promise<any[]>((_, reject) => 
          setTimeout(() => reject(new Error('Search timeout')), 5000)
        )
      ])
    );
    
    const results = await Promise.allSettled(searchPromises);
    const successfulResults = results
      .filter((result): result is PromiseFulfilledResult<any[]> => result.status === 'fulfilled')
      .map(result => result.value)
      .flat();
    
    // Deduplicate and filter valid tracks
    const uniqueTracks = successfulResults.filter((track, index, self) => 
      track && track.id && track.title && 
      index === self.findIndex(t => t.id === track.id)
    );
    
    // Filter tracks with working URLs
    const tracksWithUrls = uniqueTracks.filter(t => t.url && t.url.length > 10);
    
    if (tracksWithUrls.length >= 3) {
      console.log(`✅ Found ${tracksWithUrls.length} real tracks for ${mood}`);
      // Mix real tracks with some fallback tracks for variety
      const mixedTracks = [
        ...tracksWithUrls.slice(0, Math.floor(limit * 0.7)),
        ...fallbackTracks.slice(0, Math.ceil(limit * 0.3))
      ];
      return mixedTracks.slice(0, limit);
    }
    
    // If we have some real tracks but not enough, mix with fallbacks
    if (tracksWithUrls.length > 0) {
      console.log(`⚠️ Only found ${tracksWithUrls.length} real tracks, mixing with fallbacks`);
      const mixedTracks = [
        ...tracksWithUrls,
        ...fallbackTracks.slice(0, limit - tracksWithUrls.length)
      ];
      return mixedTracks.slice(0, limit);
    }
    
    // No real tracks found, use enhanced fallbacks
    console.log(`🎵 Using enhanced fallback tracks for ${mood}`);
    return fallbackTracks.slice(0, limit);
    
  } catch (error) {
    console.error(`❌ Failed to load ${mood} playlist:`, error);
    console.log(`🎵 Using fallback tracks for ${mood}`);
    return fallbackTracks.slice(0, limit);
  }
};

// Get song details by ID with multiple API fallbacks
export const getSongById = async (id: string) => {
  const allApis = [JIOSAAVN_BASE_URL, ...ALTERNATIVE_APIS];
  
  for (const apiUrl of allApis) {
    try {
      console.log(`🎵 Trying song API: ${apiUrl}`);
      const response = await fetch(`${apiUrl}/songs?ids=${id}`, {
        method: 'GET',
        headers: {
          'Accept': 'application/json',
          'Content-Type': 'application/json',
        },
        mode: 'cors',
      });
      
      if (!response.ok) {
        console.warn(`Song API ${apiUrl} returned ${response.status}`);
        continue;
      }
      
      const data: { data: JioSaavnTrack[] } = await response.json();
      if (data.data?.[0]) {
        console.log(`✅ Song success with ${apiUrl}`);
        return convertToTrack(data.data[0]);
      }
  } catch (error) {
      console.warn(`❌ Song API ${apiUrl} failed:`, error);
      continue;
    }
  }
  
  console.error('🚫 All song APIs failed for ID:', id);
  return null;
};