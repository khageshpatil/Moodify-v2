import { useEffect, useState } from "react";
import { Track } from "@/data/mockMusic";
import { ArrowLeft, BarChart3, Clock3, Compass, Disc3, Heart, Layers3, LoaderCircle, Play, Plus, Sparkles } from "lucide-react";
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
import { VibesCarousel } from './VibesCarousel';

export type CatalogSurface = 'explore' | 'charts' | 'for-you' | 'moods';

interface DiscoverViewProps {
  recentlyPlayed: Track[];
  favorites: Track[];
  onTrackSelect: (track: Track, tracks: Track[], discoveryContext?: DiscoveryContext) => void;
  onToggleFavorite: (track: Track) => void;
  onAddToQueue: (track: Track) => void;
  isFavorite: (trackId: string) => boolean;
  onVibeSelect: (vibeId: string) => void;
  initialSurface?: CatalogSurface;
  onSurfaceChange?: (surface: CatalogSurface) => void;
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
  onVibeSelect,
  initialSurface = 'explore',
  onSurfaceChange,
}: DiscoverViewProps) => {
  const [explorePage, setExplorePage] = useState<DiscoveryCollectionResult | null>(null);
  const [exploreCursor, setExploreCursor] = useState<string | undefined>();
  const [homePage, setHomePage] = useState<DiscoveryCollectionResult | null>(null);
  const [chartsPage, setChartsPage] = useState<DiscoveryCollectionResult | null>(null);
  const [browsePage, setBrowsePage] = useState<DiscoveryCollectionResult | null>(null);
  const [browseLoading, setBrowseLoading] = useState(false);
  const [loading, setLoading] = useState(false);
  const [exploreFailed, setExploreFailed] = useState(false);
  const [activeSurface, setActiveSurface] = useState<CatalogSurface>(initialSurface);

  useEffect(() => {
    setActiveSurface(initialSurface);
  }, [initialSurface]);

  const selectSurface = (surface: CatalogSurface) => {
    setActiveSurface(surface);
    onSurfaceChange?.(surface);
  };

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
    <article key={`${kind}-${track.id}-${index}`} className="catalog-track-row">
      <button type="button" className="catalog-track-main" onClick={() => playCollection(kind, tracks, index, onTrackSelect)}>
        <Artwork src={track.albumArt} alt="" className="catalog-track-art" fallback={<span className="catalog-art-placeholder"><Disc3 size={18} aria-hidden="true" /></span>} />
        <span className="catalog-track-copy">
          <strong>{track.title}</strong>
          <span>{track.artist || 'Unknown artist'}</span>
          <small>{track.album || 'YouTube Music'} · {formatDuration(track.duration || 0)}</small>
        </span>
      </button>
      <div className="catalog-track-actions">
        <button type="button" className="catalog-icon-button" aria-label={`Play ${track.title}`} onClick={() => playCollection(kind, tracks, index, onTrackSelect)}><Play size={16} aria-hidden="true" /></button>
        <button type="button" className={`catalog-icon-button${isFavorite(track.id) ? ' is-active' : ''}`} aria-label={`${isFavorite(track.id) ? 'Remove' : 'Save'} ${track.title}`} onClick={() => onToggleFavorite(track)}><Heart size={16} fill={isFavorite(track.id) ? 'currentColor' : 'none'} aria-hidden="true" /></button>
        <button type="button" className="catalog-icon-button" aria-label={`Add ${track.title} to queue`} onClick={() => onAddToQueue(track)}><Plus size={16} aria-hidden="true" /></button>
        <DownloadButton track={track} variant="ghost" size="icon" className="catalog-icon-button" />
      </div>
    </article>
  );

  const renderTrackCard = (track: Track, tracks: Track[], index: number, kind: 'explore' | 'home') => (
    <article key={`${kind}-card-${track.id}-${index}`} className="catalog-track-card">
      <button type="button" className="catalog-card-main" onClick={() => playCollection(kind, tracks, index, onTrackSelect)}>
        <Artwork src={track.albumArt} alt="" className="catalog-card-art" fallback={<span className="catalog-card-art catalog-art-placeholder"><Disc3 size={22} aria-hidden="true" /></span>} />
        <strong>{track.title}</strong>
        <span>{track.artist || 'Unknown artist'}</span>
      </button>
      <button type="button" className="catalog-card-play" aria-label={`Play ${track.title}`} onClick={() => playCollection(kind, tracks, index, onTrackSelect)}><Play size={15} fill="currentColor" aria-hidden="true" /></button>
    </article>
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

  const renderCatalogItem = (item: DiscoveryCatalogItem, sectionTracks: Track[], kind: 'explore' | 'home', layout: 'list' | 'card') => {
    const track = item.track ? presentTrack(item.track) : null;
    if (track) {
      const index = sectionTracks.findIndex((entry) => entry.id === track.id);
      return layout === 'list'
        ? renderTrack(track, sectionTracks, Math.max(index, 0), kind)
        : renderTrackCard(track, sectionTracks, Math.max(index, 0), kind);
    }
    return (
      <button type="button" key={`${item.type}-${item.id}`} onClick={() => void openBrowse(item)} disabled={!item.endpoint?.browseId || browseLoading} className="catalog-entity-card disabled:cursor-default">
        <Artwork src={item.artworkUrl} alt="" className="catalog-entity-art" fallback={<span className="catalog-entity-art catalog-art-placeholder"><Disc3 size={22} aria-hidden="true" /></span>} />
        <span className="catalog-entity-title">{item.title}</span>
        <span className="catalog-entity-type"><Disc3 size={13} aria-hidden="true" />{itemLabel(item)}</span>
      </button>
    );
  };

  const renderSurface = (title: string, page: DiscoveryCollectionResult | null, kind: 'explore' | 'home', allowMore = kind === 'explore') => {
    const tracks = page?.tracks || [];
    const sections = page?.catalog?.sections.filter((section) => section.items.length) || [];
    const visibleSections = sections.length ? sections : (tracks.length ? [fallbackSection(title, tracks)] : []);
    const hasContent = visibleSections.length > 0;
    return (
      <section className="catalog-surface" aria-labelledby={`surface-${title.toLowerCase().replace(/\s+/g, '-')}`}>
        <div className="catalog-surface-heading">
          <div>
            <span className="catalog-eyebrow"><Sparkles size={13} aria-hidden="true" /> Live catalog</span>
            <h2 id={`surface-${title.toLowerCase().replace(/\s+/g, '-')}`}>{title}</h2>
          </div>
          {page?.tracks?.length ? <span className="catalog-count">{page.tracks.length} songs</span> : null}
        </div>
        {!hasContent ? (
          loading ? (
            <div className="catalog-skeleton" aria-label={`Loading ${title}`}>
              {[1, 2, 3].map((item) => <span key={item} />)}
            </div>
          ) : (
            <div className="catalog-feedback">
              <Sparkles size={20} aria-hidden="true" />
              <p>{kind === 'explore' && exploreFailed ? "Couldn't load this right now" : 'Nothing from this surface yet'}</p>
            </div>
            )
        ) : (
          <div className="catalog-surface-content">
            {page?.catalog?.actions && page.catalog.actions.length > 0 && (
              <div className="catalog-actions" aria-label="Explore shortcuts">
                {page.catalog.actions.map((action) => (
                  <button type="button" className="catalog-action" key={`${action.type}-${action.id}`} onClick={() => void openBrowse(action)} disabled={!action.endpoint?.browseId || browseLoading}>
                    {action.title}
                  </button>
                ))}
              </div>
            )}
            {visibleSections.map((section, sectionIndex) => {
              const sectionTracks = uniqueTracks(section.items.map((item) => item.track ? presentTrack(item.track) : null).filter((track): track is Track => Boolean(track)));
              const isList = section.layout === 'list';
              const content = section.items.map((item) => isList
                ? renderCatalogItem(item, sectionTracks, kind, 'list')
                : renderCatalogItem(item, sectionTracks, kind, 'card'));
              return (
                <section key={section.id} className="catalog-section" aria-labelledby={`${section.id}-title`}>
                  <div className="catalog-section-heading">
                    <div><span className="catalog-section-index">{String(sectionIndex + 1).padStart(2, '0')}</span><h3 id={`${section.id}-title`}>{section.title}</h3></div>
                    <span>{section.items.length} picks</span>
                  </div>
                  {isList ? <div className="catalog-track-list">{content}</div> : <div className="catalog-rail">{content}</div>}
                </section>
              );
            })}
          </div>
        )}
        {allowMore && kind === 'explore' && exploreCursor && (
          <button type="button" className="catalog-more" onClick={async () => {
            const page = await fetchDiscoveryExplore(exploreCursor).catch(() => emptyPage('explore'));
            setExplorePage((current) => current ? { ...current, tracks: uniqueTracks([...current.tracks, ...page.tracks]), catalog: mergeCatalogPages(current.catalog, page.catalog) } : page);
            setExploreCursor(page.nextCursor);
          }} disabled={loading}>{loading ? <LoaderCircle size={15} className="spin" aria-hidden="true" /> : null} Load more</button>
        )}
      </section>
    );
  };

  return (
    <div className="catalog-discover">
      <div className="catalog-discover-header">
        <div>
          <span className="catalog-eyebrow"><Layers3 size={13} aria-hidden="true" /> Your catalog</span>
          <h1>Find the next song</h1>
          <p>Move between live shelves, charts, moods and your own listening trail.</p>
        </div>
        <div className="catalog-surface-tabs" role="tablist" aria-label="Catalog destinations">
          {([
            ['explore', 'Explore', Compass],
            ['charts', 'Charts', BarChart3],
            ['for-you', 'For you', Sparkles],
            ['moods', 'Moods', Heart],
          ] as const).map(([id, label, Icon]) => (
            <button
              key={id}
              type="button"
              role="tab"
              aria-selected={activeSurface === id}
              className={`catalog-surface-tab${activeSurface === id ? ' is-active' : ''}`}
              onClick={() => selectSurface(id)}
            >
              <Icon size={15} aria-hidden="true" />{label}
            </button>
          ))}
        </div>
      </div>
      {recentlyPlayed.length > 0 && (
        <section className="catalog-surface catalog-recent" aria-labelledby="recently-played-heading">
          <div className="catalog-surface-heading"><div><span className="catalog-eyebrow"><Clock3 size={13} aria-hidden="true" /> Your listening</span><h2 id="recently-played-heading">Recently played</h2></div></div>
          <div className="catalog-recent-grid">
            {recentlyPlayed.slice(0, 4).map((track) => (
              <button key={track.id} type="button" className="catalog-recent-item" onClick={() => onTrackSelect(track, recentlyPlayed, discoveryContextForLibrary('recently-played', recentlyPlayed.indexOf(track)))}>
                <Artwork src={track.albumArt} alt="" className="catalog-recent-art" fallback={<span className="catalog-recent-art catalog-art-placeholder"><Disc3 size={20} aria-hidden="true" /></span>} />
                <span><strong>{track.title}</strong><small>{track.artist || 'Unknown artist'}</small></span>
              </button>
            ))}
          </div>
        </section>
      )}
      {browsePage && (
        <div className="catalog-browse-backdrop">
          <button type="button" className="catalog-back" onClick={() => setBrowsePage(null)}><ArrowLeft size={15} aria-hidden="true" /> Back to Explore</button>
          {renderSurface(browsePage.catalog?.title || 'Browse', browsePage, 'explore', false)}
        </div>
      )}
      {activeSurface === 'explore' && renderSurface('Explore', explorePage, 'explore')}
      {activeSurface === 'charts' && renderSurface('Charts', chartsPage, 'home', false)}
      {activeSurface === 'for-you' && renderSurface('For you', homePage, 'home', false)}
      {activeSurface === 'moods' && (
        <section className="catalog-moods-destination" aria-labelledby="catalog-moods-heading">
          <div className="catalog-surface-heading">
            <div>
              <span className="catalog-eyebrow"><Heart size={13} aria-hidden="true" /> Moodify originals</span>
              <h2 id="catalog-moods-heading">Choose a world</h2>
            </div>
            <span className="catalog-count">8 worlds</span>
          </div>
          <p className="catalog-moods-copy">Mood is the shortcut. Each world resolves into a living provider-backed collection when you open it.</p>
          <VibesCarousel onVibeSelect={onVibeSelect} />
        </section>
      )}
    </div>
  );
};
