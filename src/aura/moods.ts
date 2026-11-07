/** Moodify's moods: the same list (and queries) as Android's Moods.kt. */
export interface MoodOption {
  id: string;
  label: string;
  query: string;
  emoji: string;
  /** CSS variable from tokens.css. */
  hue: string;
}

export const MOODS: MoodOption[] = [
  { id: 'chill', label: 'Chill', query: 'chill vibes', emoji: '🌿', hue: 'var(--mood-teal)' },
  { id: 'focus', label: 'Focus', query: 'deep focus music', emoji: '🧠', hue: 'var(--mood-blue)' },
  { id: 'energy', label: 'Energy', query: 'high energy hits', emoji: '⚡', hue: 'var(--mood-amber)' },
  { id: 'romance', label: 'Romance', query: 'romantic songs', emoji: '💗', hue: 'var(--mood-pink)' },
  { id: 'party', label: 'Party', query: 'party anthems', emoji: '🎉', hue: 'var(--mood-violet)' },
  { id: 'workout', label: 'Workout', query: 'workout music', emoji: '🏋️', hue: 'var(--mood-coral)' },
  { id: 'roadtrip', label: 'Road trip', query: 'road trip songs', emoji: '🚗', hue: 'var(--mood-green)' },
  { id: 'heartbreak', label: 'Heartbreak', query: 'sad songs', emoji: '💔', hue: 'var(--mood-bronze)' },
  { id: 'sleep', label: 'Sleep', query: 'sleep music', emoji: '🌙', hue: 'var(--mood-blue)' },
];

export const moodById = (id: string | undefined) => MOODS.find((mood) => mood.id === id);

export const greeting = (hour = new Date().getHours()) =>
  hour < 5 ? 'Still up' : hour < 12 ? 'Good morning' : hour < 17 ? 'Good afternoon' : 'Good evening';
