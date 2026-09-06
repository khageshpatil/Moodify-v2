import { HistoryItem } from "@/hooks/useMusicPlayer";
import { Track } from "@/data/mockMusic";
import { Card } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { ScrollArea } from "@/components/ui/scroll-area";
import { Clock, Trash2 } from "lucide-react";
import type { DiscoveryContext } from '@/listening/listeningTypes';
import { discoveryContextForLibrary } from '@/listening/discoveryAttribution';
import { Artwork } from './Artwork';
import { presentTrack } from '@/presentation/providerPresentation';

interface HistoryViewProps {
  history: HistoryItem[];
  onTrackSelect: (track: Track, tracks: Track[], discoveryContext?: DiscoveryContext) => void;
  onClearHistory: () => void;
}

export const HistoryView = ({
  history,
  onTrackSelect,
  onClearHistory,
}: HistoryViewProps) => {
  const formatDate = (date: Date) => {
    const now = new Date();
    const diff = now.getTime() - new Date(date).getTime();
    const minutes = Math.floor(diff / 60000);
    const hours = Math.floor(minutes / 60);
    const days = Math.floor(hours / 24);

    if (minutes < 60) return `${minutes}m ago`;
    if (hours < 24) return `${hours}h ago`;
    if (days < 7) return `${days}d ago`;
    return new Date(date).toLocaleDateString();
  };

  const formatDuration = (seconds: number) => {
    const mins = Math.floor(seconds / 60);
    const secs = Math.floor(seconds % 60);
    return `${mins}:${secs.toString().padStart(2, '0')}`;
  };

  return (
    <div className="w-full max-w-4xl mx-auto">
      <Card className="glass-card p-6">
        <div className="flex items-center justify-between mb-6">
          <div className="flex items-center gap-3">
            <Clock className="h-8 w-8 text-accent" />
            <div>
              <h2 className="text-2xl font-bold text-white">Listen History</h2>
              <p className="text-white/60">
                {history.length} {history.length === 1 ? 'song' : 'songs'}
              </p>
            </div>
          </div>
          {history.length > 0 && (
            <Button
              variant="outline"
              size="sm"
              onClick={onClearHistory}
              className="glass-button"
            >
              <Trash2 className="h-4 w-4 mr-2" />
              Clear History
            </Button>
          )}
        </div>

        <ScrollArea className="player-safe-scroll">
          {history.length === 0 ? (
            <div className="flex flex-col items-center justify-center py-12 text-white/60">
              <Clock className="h-16 w-16 mb-4 opacity-40" />
              <p className="text-lg">No history yet</p>
              <p className="text-sm mt-2">Finished tracks show up here</p>
            </div>
          ) : (
            <div className="space-y-2">
              {history.map((item, index) => {
                const shown = presentTrack(item.track);
                if (!shown) return null;
                return (
                <div
                  key={`${item.track.id}-${index}`}
                  className="glass-card p-4 flex items-center gap-4 transition-all hover:bg-white/10 cursor-pointer group"
                  onClick={() => onTrackSelect(item.track, history.map((entry) => entry.track), discoveryContextForLibrary('history', index))}
                >
                  <Artwork src={shown.albumArt} alt="" className="w-14 h-14 rounded object-cover" />

                  <div className="flex-1 min-w-0">
                    <p className="font-medium text-white truncate">
                      {shown.title}
                    </p>
                    {shown.artist ? (
                    <p className="text-sm text-white/60 truncate">
                      {shown.artist}
                    </p>
                    ) : null}
                  </div>

                  <div className="flex flex-col items-end gap-1">
                    <span className="text-xs text-white/60">
                      {formatDate(item.playedAt)}
                    </span>
                    <span className="text-xs text-white/40">
                      {formatDuration(shown.duration || 0)}
                    </span>
                  </div>
                </div>
                );
              })}
            </div>
          )}
        </ScrollArea>
      </Card>
    </div>
  );
};
