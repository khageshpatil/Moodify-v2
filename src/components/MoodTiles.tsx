import { useState } from 'react';

// Soothing Japanese anime series GIF backgrounds for each mood
const moodChillImg = 'https://media2.giphy.com/media/v1.Y2lkPTc5MGI3NjExaGtsdjFibGptYzB4dmZxdGtvc3BtNGQ1NWJvem5vNjVxdHVvM2lidiZlcD12MV9pbnRlcm5hbF9naWZfYnlfaWQmY3Q9Zw/QaDOoqfYUy1lS/giphy.gif'; // Garden of Words - peaceful rain
const moodMelancholyImg = 'https://media2.giphy.com/media/v1.Y2lkPTc5MGI3NjExcHh5MHMzZTVxcXZ2YWdtZzFoYmh4aWhreGh1aXdrOG44ZGR2bGV0OCZlcD12MV9pbnRlcm5hbF9naWZfYnlfaWQmY3Q9Zw/Id71NFYfSBOKv2IexE/giphy.gif'; // Your Name - nostalgic
const moodWorkoutImg = 'https://media3.giphy.com/media/v1.Y2lkPTc5MGI3NjExY28wbHBsdnc2ZnZwYXlhcHZlc25qdGxreHV0djN4dmUxM2dza2o2bSZlcD12MV9pbnRlcm5hbF9naWZfYnlfaWQmY3Q9Zw/U8wCBLhkjNknS/giphy.gif'; // One Punch Man - energetic
const moodFocusImg = 'https://media.giphy.com/media/v1.Y2lkPTc5MGI3NjExc3U1M3Z0cW9pczBsaHJiaHRvcXkyZ3hjMXJhcG9rNzdkbXJ4OGk3YyZlcD12MV9naWZzX3NlYXJjaCZjdD1n/6XX4V0O8a0xdS/giphy.gif'; // Study anime aesthetic
const moodLoveImg = 'https://media.giphy.com/media/v1.Y2lkPWVjZjA1ZTQ3b3k3eDc5amFmbjB4MmZhNGViNGNjbnd1bGh0cWRhN21qdnZqcTc4aCZlcD12MV9naWZzX3JlbGF0ZWQmY3Q9Zw/l0HU49MXasr6juD0A/giphy.gif'; // Anime couple romantic
const moodPartyImg = 'https://media.giphy.com/media/v1.Y2lkPWVjZjA1ZTQ3M2ZzODR6b3lxamNvMnFpcTl0c3RvOGxxdTUyYXJwaTV2OHZrNnNkMCZlcD12MV9naWZzX3NlYXJjaCZjdD1n/4gsjHZMPXdlGo/giphy.gif'; // Anime celebration dance

interface Mood {
  id: string;
  name: string;
  emoji: string;
  bgImage: string;
  color: string;
  description: string;
}

interface MoodTilesProps {
  onMoodSelect: (mood: Mood) => void;
}

const moods: Mood[] = [
  {
    id: 'chill',
    name: 'Chill',
    emoji: '🌸',
    bgImage: moodChillImg,
    color: 'mood-chill',
    description: 'Peaceful vibes for relaxation'
  },
  {
    id: 'melancholy',
    name: 'Melancholy',
    emoji: '☁️',
    bgImage: moodMelancholyImg,
    color: 'mood-melancholy',
    description: 'Reflective and contemplative'
  },
  {
    id: 'workout',
    name: 'Workout',
    emoji: '🔥',
    bgImage: moodWorkoutImg,
    color: 'mood-workout',
    description: 'High energy for motivation'
  },
  {
    id: 'focus',
    name: 'Focus',
    emoji: '🚀',
    bgImage: moodFocusImg,
    color: 'mood-focus',
    description: 'Deep concentration mode'
  },
  {
    id: 'love',
    name: 'Love',
    emoji: '❤️',
    bgImage: moodLoveImg,
    color: 'mood-love',
    description: 'Romantic and heartfelt'
  },
  {
    id: 'party',
    name: 'Party',
    emoji: '🎉',
    bgImage: moodPartyImg,
    color: 'mood-party',
    description: 'Celebration and joy'
  },
];

const MoodTiles = ({ onMoodSelect }: MoodTilesProps) => {
  const [hoveredMood, setHoveredMood] = useState<string | null>(null);

  return (
    <div className="w-full max-w-7xl mx-auto">
      {/* Header Section */}
      <div className="text-center mb-16 fade-in">
        <h2 
          className="text-3xl md:text-4xl font-medium mb-3"
          style={{
            color: 'var(--text-primary)',
            fontWeight: 400,
            letterSpacing: '0.4px'
          }}
        >
          What's your mood today?
        </h2>
        <p 
          className="text-base md:text-lg"
          style={{
            color: 'var(--text-secondary)',
            fontWeight: 300
          }}
        >
          Select a vibe and let the music flow through your soul
        </p>
      </div>

      {/* Cinematic Mood Grid */}
      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-8">
        {moods.map((mood, index) => (
          <div
            key={mood.id}
            className="mood-tile group relative h-64 md:h-72 lg:h-80 cursor-pointer scale-in"
            style={{
              animationDelay: `${index * 0.08}s`
            }}
            onClick={() => onMoodSelect(mood)}
            onMouseEnter={() => setHoveredMood(mood.id)}
            onMouseLeave={() => setHoveredMood(null)}
          >
            {/* Background Image */}
            <div 
              className="mood-tile-image absolute inset-0 rounded-3xl overflow-hidden"
              style={{
                backgroundImage: `url(${mood.bgImage})`,
                backgroundSize: 'cover',
                backgroundPosition: 'center',
              }}
            >
              {/* Subtle Dark Overlay for Text Readability */}
            <div 
                className="absolute inset-0"
                style={{
                  background: 'linear-gradient(180deg, rgba(11, 15, 25, 0.1) 0%, rgba(11, 15, 25, 0.5) 100%)'
                }}
              />
            </div>

            {/* Subtle Border (No Blur) */}
            <div 
              className="absolute inset-0 rounded-3xl"
              style={{
                border: '1px solid rgba(255, 255, 255, 0.1)',
                pointerEvents: 'none',
              }}
            />

            {/* Content Layer */}
            <div className="relative h-full flex flex-col justify-between p-8 z-10">
              {/* Emoji Badge */}
              <div className="self-end">
                <div 
                  className="w-14 h-14 rounded-2xl flex items-center justify-center transition-all duration-500 group-hover:scale-110"
                  style={{
                    background: 'var(--surface-frost-hover)',
                    backdropFilter: 'var(--blur-medium)',
                    border: '1px solid var(--stroke-medium)',
                  }}
                >
                  <span className="text-3xl">{mood.emoji}</span>
                </div>
              </div>

              {/* Mood Info */}
              <div className="space-y-3">
                <h3 
                  className="text-3xl md:text-4xl transition-all duration-300"
                  style={{
                    color: 'var(--text-primary)',
                    fontWeight: 500,
                    letterSpacing: '0.3px'
                  }}
                >
                  {mood.name}
                </h3>
                <p 
                  className="text-sm md:text-base transition-all duration-300"
                  style={{
                    color: 'var(--text-secondary)',
                    fontWeight: 300
                  }}
                >
                  {mood.description}
                </p>
              </div>
            </div>

            {/* Ambient Glow on Hover */}
                  <div
              className="absolute inset-0 rounded-3xl pointer-events-none transition-opacity duration-500"
                    style={{
                opacity: hoveredMood === mood.id ? 0.6 : 0,
                background: 'radial-gradient(circle at 50% 80%, var(--accent-glow), transparent 60%)',
                    }}
                  />
          </div>
        ))}
      </div>

      {/* Footer Hint */}
      <div className="text-center mt-16 fade-in" style={{ animationDelay: '0.5s' }}>
        <p 
          className="text-sm"
          style={{
            color: 'var(--text-tertiary)',
            fontWeight: 300
          }}
        >
          Each mood brings a unique sonic journey crafted just for you
        </p>
      </div>
    </div>
  );
};

export default MoodTiles;