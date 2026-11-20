import { useSyncExternalStore } from 'react';
import { getCanonicalPlaybackEngine } from '@/playback/PlaybackEngine';

/**
 * The song position in seconds, updated 4x a second. Only the few components that show it
 * (seek bar, progress line) subscribe, so the rest of the app doesn't re-render while music plays.
 */
export function usePlaybackPosition(): number {
  const engine = getCanonicalPlaybackEngine();
  return useSyncExternalStore(
    (onChange) => { const off = engine.subscribe(() => onChange()); return () => { off(); }; },
    () => engine.getSnapshot().currentTime,
  );
}
