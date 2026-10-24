import { useEffect, useState } from "react";
import { Track } from "@/data/mockMusic";
import { Card } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { ScrollArea } from "@/components/ui/scroll-area";
import { Sparkles, Play, Heart, Plus, Disc3, ListMusic } from "lucide-react";
import { DownloadButton } from "@/components/DownloadButton";
import { Artwork } from "@/components/Artwork";
import { presentTrack } from "@/presentation/providerPresentation";
import type { DiscoveryContext } from '@/listening/listeningTypes';
import { discoveryContextForLibrary } from '@/listening/discoveryAttribution';
import {
  fetchDiscoveryExplore,
  fetchDiscoveryHome,
  fetchDiscoveryCharts,
  fetchDiscoveryBrowse,
  toDiscoveryPlaybackRequest,
  type DiscoveryCatalogItem,
  type DiscoveryCatalogPage,
  type DiscoveryCatalogSection,
  type DiscoveryCollectionResult,
} from '@/services/moodifyMusicApi';

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

const uniqueTracks = (tracks: Track[]) => [...new Map(tracks.map((track) => [track.id, track])).values()];

const mergeCatalogPages = (current?: DiscoveryCatalogPage, incoming?: DiscoveryCatalogPage): DiscoveryCatalogPage | undefined => {
  if (!current) return incoming;
  if (!incoming) return current;
  const sections = [...current.sections];
  incoming.sections.forEach((section) => {
    const existing = sections.find((entry) => entry.id === section.id || entry.title === section.title);
    if (!existing) {
      sections.push(section);
      return;
    }
    existing.items = [...new Map([...existing.items, ...section.items].map((item) => [`${item.type}:${item.id}`, item])).values()];
  });
  return { ...current, sections };
};

const fallbackSection = (title: string, tracks: Track[]): DiscoveryCatalogSection => ({
  id: `fallback:${title.toLowerCase().replace(/\s+/g, '-')}`,
  title,
  layout: 'list',
  items: tracks.map((track) => ({ type: 'track', id: track.id, title: track.title, track })),
});

const itemLabel = (item: DiscoveryCatalogItem) => {
  if (item.type === 'artist') return 'Artist';
  if (item.type === 'album') return 'Album';
  if (item.type === 'playlist') return 'Playlist';
  if (item.type === 'video') return 'Video';
  if (item.type === 'podcast') return 'Podcast';
  return 'Browse';
};

const emptyPage = (surface: string): DiscoveryCollectionResult => ({
  tracks: [],
  entities: [],
  surface,
});

export const DiscoverView = ({
  recentlyPlayed,
  favorites: _favorites,
  onTrackSelect,
  onToggleFavorite,
  onAddToQueue,
  isFavorite,
}: DiscoverViewProps) => {
  const [explorePage, setExplorePage] = useState<DiscoveryCollectionResult | null>(null);
  const [exploreCursor, setExploreCursor] = useState<string | undefined>();
  const [homePage, setHomePage] = useState<DiscoveryCollectionResult | null>(null);
  const [chartsPage, setChartsPage] = useState<DiscoveryCollectionResult | null>(null);
  const [browsePage, setBrowsePage] = useState<DiscoveryCollectionResult | null>(null);
  const [browseLoading, setBrowseLoading] = useState(false);
  const [loading, setLoading] = useState(false);
  const [exploreFailed, setExploreFailed] = useState(false);

  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    setExploreFailed(false);
    Promise.all([
      fetchDiscoveryExplore(undefined, controller.signal).catch(() => {
        setExploreFailed(true);
        return emptyPage('explore');
      }),
      fetchDiscoveryHome(undefined, controller.signal).catch(() => emptyPage('home')),
      fetchDiscoveryCharts(undefined, controller.signal).catch(() => emptyPage('charts')),
    ]).then(([nextExplore, nextHome, nextCharts]) => {
      if (controller.signal.aborted) return;
      setExplorePage(nextExplore);
      setExploreCursor(nextExplore.nextCursor);
      setHomePage(nextHome);
      setChartsPage(nextCharts);
    }).finally(() => {
      if (!controller.signal.aborted) setLoading(false);
    });
    return () => controller.abort();
  }, []);

  const formatDuration = (seconds: number) => {
    const mins = Math.floor(seconds / 60);
    const secs = Math.floor(seconds % 60);
    return `${mins}:${secs.toString().padStart(2, '0')}`;
  };

  const renderTrack = (track: Track, tracks: Track[], index: number, kind: 'explore' | 'home') => (
    <div key={`${kind}-${track.id}-${index}`} className="glass-card p-4 flex items-center gap-4 transition-all hover:bg-white/10 group">
      {track.albumArt && <Artwork src={track.albumArt} alt="" className="w-16 h-16 rounded object-cover" />}
      <div className="flex-1 min-w-0">
        <p className="font-medium text-white truncate">{track.title}</p>
        <p className="text-sm text-white/60 truncate">{track.artist}</p>
        <p className="text-xs text-white/40">{formatDuration(track.duration || 0)}</p>
      </div>
      <div className="flex gap-2">
        <Button variant="ghost" size="icon" aria-label={`Play ${track.title}`} onClick={() => playCollection(kind, tracks, index, onTrackSelect)} className="text-white hover:text-accent">
          <Play className="h-4 w-4" />
        </Button>
        <Button variant="ghost" size="icon" aria-label={`${isFavorite(track.id) ? 'Remove' : 'Save'} ${track.title}`} onClick={() => onToggleFavorite(track)} className={isFavorite(track.id) ? "text-accent" : "text-white hover:text-accent"}>
          <Heart className={`h-4 w-4 ${isFavorite(track.id) ? 'fill-accent' : ''}`} />
        </Button>
        <Button variant="ghost" size="icon" aria-label={`Add ${track.title} to queue`} onClick={() => onAddToQueue(track)} className="text-white hover:text-accent">
          <Plus className="h-4 w-4" />
        </Button>
        <DownloadButton track={track} variant="ghost" size="icon" className="text-white hover:text-accent" />
      </div>
    </div>
  );

  const openBrowse = async (item: DiscoveryCatalogItem) => {
    if (!item.endpoint?.browseId) return;
    setBrowseLoading(true);
    try {
      setBrowsePage(await fetchDiscoveryBrowse(item.endpoint.browseId, item.endpoint.params));
    } finally {
      setBrowseLoading(false);
    }
  };

  const renderCatalogItem = (item: DiscoveryCatalogItem, sectionTracks: Track[], kind: 'explore' | 'home') => {
    const track = item.track ? presentTrack(item.track) : null;
    if (track) {
      const index = sectionTracks.findIndex((entry) => entry.id === track.id);
      return renderTrack(track, sectionTracks, Math.max(index, 0), kind);
    }
    return (
      <button type="button" key={`${item.type}-${item.id}`} onClick={() => void openBrowse(item)} disabled={!item.endpoint?.browseId || browseLoading} className="glass-card p-3 min-w-[150px] max-w-[190px] text-left disabled:cursor-default">
        <Artwork src={item.artworkUrl} alt="" className="w-full aspect-square rounded object-cover mb-3" />
        <p className="font-medium text-white truncate">{item.title}</p>
        <p className="text-xs text-white/50 mt-1 flex items-center gap-1"><Disc3 className="h-3 w-3" />{itemLabel(item)}</p>
      </button>
    );
  };

  const renderSurface = (title: string, page: DiscoveryCollectionResult | null, kind: 'explore' | 'home', allowMore = kind === 'explore') => {
    const tracks = page?.tracks || [];
    const sections = page?.catalog?.sections.filter((section) => section.items.length) || [];
    const visibleSections = sections.length ? sections : (tracks.length ? [fallbackSection(title, tracks)] : []);
    const hasContent = visibleSections.length > 0;
    return (
      <Card className="glass-card p-6">
        <h2 className="text-2xl font-bold text-white mb-5 flex items-center gap-2"><Sparkles className="h-6 w-6 text-accent" />{title}</h2>
        {!hasContent ? (
          <div className="flex flex-col items-center justify-center py-12 text-white/60">
            <Sparkles className="h-16 w-16 mb-4 opacity-40" />
            <p className="text-lg">{loading ? 'Loading…' : kind === 'explore' && exploreFailed ? "Couldn't load this right now" : 'Nothing from this surface yet'}</p>
          </div>
        ) : (
          <div className="space-y-7">
            {page?.catalog?.actions && page.catalog.actions.length > 0 && (
              <div className="flex gap-2 overflow-x-auto pb-1" aria-label="Explore shortcuts">
                {page.catalog.actions.map((action) => (
                  <Button key={`${action.type}-${action.id}`} variant="outline" className="whitespace-nowrap" onClick={() => void openBrowse(action)} disabled={!action.endpoint?.browseId || browseLoading}>
                    {action.title}
                  </Button>
                ))}
              </div>
            )}
            {visibleSections.map((section) => {
              const sectionTracks = uniqueTracks(section.items.map((item) => item.track ? presentTrack(item.track) : null).filter((track): track is Track => Boolean(track)));
              const isList = section.layout === 'list' || sectionTracks.length > 0;
              return (
                <section key={section.id} aria-labelledby={`${section.id}-title`}>
                  <div className="flex items-center gap-2 mb-3"><ListMusic className="h-4 w-4 text-accent" /><h3 id={`${section.id}-title`} className="text-lg font-semibold text-white">{section.title}</h3></div>
                  {isList ? (
                    <div className="grid grid-cols-1 md:grid-cols-2 gap-3">{section.items.map((item) => renderCatalogItem(item, sectionTracks, kind))}</div>
                  ) : (
                    <ScrollArea className="player-safe-scroll"><div className="flex gap-4 pb-2">{section.items.map((item) => renderCatalogItem(item, sectionTracks, kind))}</div></ScrollArea>
                  )}
                </section>
              );
            })}
          </div>
        )}
        {allowMore && kind === 'explore' && exploreCursor && (
          <Button variant="ghost" className="mt-5 text-white" onClick={async () => {
            const page = await fetchDiscoveryExplore(exploreCursor).catch(() => emptyPage('explore'));
            setExplorePage((current) => current ? { ...current, tracks: uniqueTracks([...current.tracks, ...page.tracks]), catalog: mergeCatalogPages(current.catalog, page.catalog) } : page);
            setExploreCursor(page.nextCursor);
          }}>More</Button>
        )}
      </Card>
    );
  };

  return (
    <div className="w-full max-w-6xl mx-auto space-y-6">
      {recentlyPlayed.length > 0 && (
        <Card className="glass-card p-6">
          <h2 className="text-2xl font-bold text-white mb-4 flex items-center gap-2"><Clock className="h-6 w-6" />Recently Played</h2>
          <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
            {recentlyPlayed.slice(0, 4).map((track) => (
              <div key={track.id} className="glass-card p-3 cursor-pointer transition-all hover:scale-105 group" onClick={() => onTrackSelect(track, recentlyPlayed, discoveryContextForLibrary('recently-played', recentlyPlayed.indexOf(track)))}>
                {track.albumArt && <Artwork src={track.albumArt} alt="" className="w-full aspect-square rounded mb-2 object-cover" />}
                <p className="font-medium text-white text-sm truncate">{track.title}</p><p className="text-xs text-white/60 truncate">{track.artist}</p>
              </div>
            ))}
          </div>
        </Card>
      )}
      {browsePage && (
        <div>
          <Button variant="ghost" className="mb-3 text-white" onClick={() => setBrowsePage(null)}>← Back to Explore</Button>
          {renderSurface(browsePage.catalog?.title || 'Browse', browsePage, 'explore', false)}
        </div>
      )}
      {renderSurface('Explore', explorePage, 'explore')}
      {renderSurface('Charts', chartsPage, 'home', false)}
      {homePage && renderSurface('For you', homePage, 'home')}
    </div>
  );
};

const Clock = ({ className }: { className?: string }) => (
  <svg className={className} fill="none" viewBox="0 0 24 24" stroke="currentColor" aria-hidden="true"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z" /></svg>
);
