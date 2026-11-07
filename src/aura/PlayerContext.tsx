import { createContext, useContext, type ReactNode } from 'react';
import { useCanonicalMusicPlayer } from '@/hooks/useCanonicalMusicPlayer';

type Player = ReturnType<typeof useCanonicalMusicPlayer>;

const PlayerContext = createContext<Player | null>(null);

/** One playback engine for the whole Aura app. */
export function PlayerProvider({ children }: { children: ReactNode }) {
  const player = useCanonicalMusicPlayer();
  return <PlayerContext.Provider value={player}>{children}</PlayerContext.Provider>;
}

export function usePlayer(): Player {
  const player = useContext(PlayerContext);
  if (!player) throw new Error('usePlayer must be used inside <PlayerProvider>');
  return player;
}
