import { useState, useEffect } from "react";
import { Track } from "@/data/mockMusic";
import { Card } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { ScrollArea } from "@/components/ui/scroll-area";
import { Sparkles, Play, Heart, Plus } from "lucide-react";
import { DownloadButton } from "@/components/DownloadButton";
import { Artwork } from "@/components/Artwork";
import { presentTrack } from "@/presentation/providerPresentation";
import type { DiscoveryContext } from '@/listening/listeningTypes';
import { discoveryContextForLibrary } from '@/listening/discoveryAttribution';
import { fetchDiscoveryExplore, fetchDiscoveryHome, toDiscoveryPlaybackRequest } from '@/services/moodifyMusicApi';

interface DiscoverViewProps {
  recentlyPlayed: Track[];
  favorites: Track[];
  onTrackSelect: (track: Track, tracks: Track[], discoveryContext?: DiscoveryContext) => void;
  onToggleFavorite: (track: Track) => void;
  onAddToQueue: (track: Track) => void;
  isFavorite: (trackId: string) => boolean;
}

const playCollection = (
  kind: 'explore' | 'home',
  tracks: Track[],
  index: number,
  onTrackSelect: DiscoverViewProps['onTrackSelect'],
) => {
  const request = toDiscoveryPlaybackRequest(kind, tracks, { id: kind === 'explore' ? 'moodify-discover' : undefined, position: index });
  if (request) onTrackSelect(request.track, request.tracks, request.discoveryContext);
};

export const DiscoverView = ({
  recentlyPlayed,
  favorites,
  onTrackSelect,
  onToggleFavorite,
  onAddToQueue,
  isFavorite,
}: DiscoverViewProps) => {
  const [explore, setExplore] = useState<Track[]>([]);
  const [exploreCursor, setExploreCursor] = useState<string | undefined>();
  const [home, setHome] = useState<Track[]>([]);
  const [loading, setLoading] = useState(false);

  const [exploreFailed, setExploreFailed] = useState(false);

  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    setExploreFailed(false);
    Promise.all([
      fetchDiscoveryExplore(undefined, controller.signal).catch(() => {
        setExploreFailed(true);
        return { tracks: [] as Track[], nextCursor: undefined };
      }),
      fetchDiscoveryHome(undefined, controller.signal).catch(() => ({ tracks: [] as Track[] })),
    ]).then(([explorePage, homePage]) => {
      setExplore(explorePage.tracks);
      setExploreCursor(explorePage.nextCursor);
      setHome(homePage.tracks);
    }).finally(() => setLoading(false));
    return () => controller.abort();
  }, []);

  const formatDuration = (seconds: number) => {
    const mins = Math.floor(seconds / 60);
    const secs = Math.floor(seconds % 60);
    return `${mins}:${secs.toString().padStart(2, '0')}`;
  };

  const renderShelf = (title: string, tracks: Track[], kind: 'explore' | 'home') => {
    const visible = tracks.map((track) => presentTrack(track)).filter((track): track is Track => Boolean(track));
    return (
    <Card className="glass-card p-6">
      <h2 className="text-2xl font-bold text-white mb-4 flex items-center gap-2">
        <Sparkles className="h-6 w-6 text-accent" />
        {title}
      </h2>
      {visible.length === 0 ? (
        <div className="flex flex-col items-center justify-center py-12 text-white/60">
          <Sparkles className="h-16 w-16 mb-4 opacity-40" />
          <p className="text-lg">{loading ? 'Loading…' : (kind === 'explore' && exploreFailed) ? "Couldn't load this right now" : 'Nothing from this surface yet'}</p>
        </div>
      ) : (
        <ScrollArea className="player-safe-scroll">
          <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
            {visible.map((track, index) => (
              <div key={`${kind}-${track.id}-${index}`} className="glass-card p-4 flex items-center gap-4 transition-all hover:bg-white/10 group">
                {track.albumArt && (
                  <Artwork src={track.albumArt} alt="" className="w-16 h-16 rounded object-cover" />
                )}
                <div className="flex-1 min-w-0">
                  <p className="font-medium text-white truncate">{track.title}</p>
                  <p className="text-sm text-white/60 truncate">{track.artist}</p>
                  <p className="text-xs text-white/40">{formatDuration(track.duration || 0)}</p>
                </div>
                <div className="flex gap-2">
                  <Button variant="ghost" size="icon" onClick={() => playCollection(kind, visible, index, onTrackSelect)} className="text-white hover:text-accent">
                    <Play className="h-4 w-4" />
                  </Button>
                  <Button variant="ghost" size="icon" onClick={() => onToggleFavorite(track)} className={isFavorite(track.id) ? "text-accent" : "text-white hover:text-accent"}>
                    <Heart className={`h-4 w-4 ${isFavorite(track.id) ? 'fill-accent' : ''}`} />
                  </Button>
                  <Button variant="ghost" size="icon" onClick={() => onAddToQueue(track)} className="text-white hover:text-accent">
                    <Plus className="h-4 w-4" />
                  </Button>
                  <DownloadButton track={track} variant="ghost" size="icon" className="text-white hover:text-accent" />
                </div>
              </div>
            ))}
          </div>
        </ScrollArea>
      )}
      {kind === 'explore' && exploreCursor && (
        <Button
          variant="ghost"
          className="mt-4 text-white"
          onClick={async () => {
            const page = await fetchDiscoveryExplore(exploreCursor).catch(() => ({ tracks: [] as Track[], nextCursor: undefined }));
            setExplore((current) => [...current, ...page.tracks]);
            setExploreCursor(page.nextCursor);
          }}
        >
          More
        </Button>
      )}
    </Card>
    );
  };

  return (
    <div className="w-full max-w-6xl mx-auto space-y-6">
      {recentlyPlayed.length > 0 && (
        <Card className="glass-card p-6">
          <h2 className="text-2xl font-bold text-white mb-4 flex items-center gap-2">
            <Clock className="h-6 w-6" />
            Recently Played
          </h2>
          <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
            {recentlyPlayed.slice(0, 4).map((track) => (
              <div
                key={track.id}
                className="glass-card p-3 cursor-pointer transition-all hover:scale-105 group"
                onClick={() => onTrackSelect(track, recentlyPlayed, discoveryContextForLibrary('recently-played', recentlyPlayed.indexOf(track)))}
              >
                {track.albumArt && (
                  <Artwork src={track.albumArt} alt="" className="w-full aspect-square rounded mb-2 object-cover" />
                )}
                <p className="font-medium text-white text-sm truncate">{track.title}</p>
                <p className="text-xs text-white/60 truncate">{track.artist}</p>
              </div>
            ))}
          </div>
        </Card>
      )}
      {renderShelf('Explore', explore, 'explore')}
      {home.length > 0 && renderShelf('From YouTube', home, 'home')}
    </div>
  );
};

const Clock = ({ className }: { className?: string }) => (
  <svg className={className} fill="none" viewBox="0 0 24 24" stroke="currentColor">
    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z" />
  </svg>
);
