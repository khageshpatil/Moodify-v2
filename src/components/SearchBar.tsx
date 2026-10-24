import { useEffect, useRef, useState } from 'react';
import { Play, Search } from 'lucide-react';
import {
  fetchDiscoveryAlbum,
  fetchDiscoveryArtist,
  fetchDiscoveryPlaylist,
  fetchDiscoverySearch,
  fetchSearchSuggestions,
  toDiscoveryPlaybackRequest,
  type DiscoveryCollectionResult,
  type DiscoveryEntity,
} from '@/services/moodifyMusicApi';
import { Track } from '@/data/mockMusic';
import { Playlist } from '@/hooks/useMusicPlayer';
import { AddToPlaylistButton } from './AddToPlaylistButton';
import { Artwork } from './Artwork';
import type { DiscoveryContext } from '@/listening/listeningTypes';
import { presentTrack } from '@/presentation/providerPresentation';

interface SearchBarProps {
  onTrackSelect: (track: Track, tracks: Track[], discoveryContext?: DiscoveryContext) => void;
  playlists: Playlist[];
  onAddToPlaylist: (playlistId: string, track: Track) => void;
  onCreatePlaylist: (name: string) => void;
}

type SearchType = 'all' | 'song' | 'video' | 'album' | 'artist' | 'playlist';

const mergeEntities = (current: DiscoveryEntity[], incoming: DiscoveryEntity[]) => (
  [...new Map([...current, ...incoming].map((entry) => [`${entry.type}:${entry.id}`, entry])).values()]
);

export const SearchBar = ({ onTrackSelect, playlists, onAddToPlaylist, onCreatePlaylist }: SearchBarProps) => {
  const [query, setQuery] = useState('');
  const [suggestions, setSuggestions] = useState<string[]>([]);
  const [results, setResults] = useState<Track[]>([]);
  const [entities, setEntities] = useState<DiscoveryEntity[]>([]);
  const [nextCursor, setNextCursor] = useState<string | undefined>();
  const [collection, setCollection] = useState<DiscoveryCollectionResult | null>(null);
  const [collectionLabel, setCollectionLabel] = useState('');
  const [isSearching, setIsSearching] = useState(false);
  const [activeQuery, setActiveQuery] = useState('');
  const [searchType, setSearchType] = useState<SearchType>('all');
  const [searchError, setSearchError] = useState(false);
  const [collectionError, setCollectionError] = useState(false);
  const [openedEntity, setOpenedEntity] = useState<DiscoveryEntity | null>(null);
  const suggestRequest = useRef<AbortController | null>(null);

  const applySearchPage = (page: DiscoveryCollectionResult, append: boolean) => {
    setResults((current) => append ? [...current, ...page.tracks] : page.tracks);
    setEntities((current) => (append ? mergeEntities(current, page.entities) : page.entities.filter((entry) => Boolean(entry.title)).slice(0, 8)));
    setNextCursor(page.nextCursor);
  };

  const handleSearch = async (rawQuery = query) => {
    if (!rawQuery.trim()) return;
    setIsSearching(true);
    setSuggestions([]);
    setCollection(null);
    setCollectionLabel('');
    setOpenedEntity(null);
    setSearchError(false);
    setCollectionError(false);
    try {
      const page = await fetchDiscoverySearch(rawQuery.trim(), undefined, undefined, searchType);
      setActiveQuery(rawQuery.trim());
      applySearchPage(page, false);
    } catch {
      setSearchError(true);
      setResults([]);
      setEntities([]);
    } finally {
      setIsSearching(false);
    }
  };

  useEffect(() => {
    suggestRequest.current?.abort();
    const next = query.trim();
    if (next.length < 2 || next === activeQuery) {
      setSuggestions([]);
      return;
    }
    const controller = new AbortController();
    suggestRequest.current = controller;
    const timer = window.setTimeout(async () => {
      try {
        const nextSuggestions = await fetchSearchSuggestions(next, controller.signal);
        if (!controller.signal.aborted) setSuggestions(nextSuggestions);
      } catch {
        if (!controller.signal.aborted) setSuggestions([]);
      }
    }, 220);
    return () => {
      window.clearTimeout(timer);
      controller.abort();
    };
  }, [query, activeQuery]);

  const handleLoadMore = async () => {
    if (!activeQuery || !nextCursor) return;
    setIsSearching(true);
    try {
      const page = await fetchDiscoverySearch(activeQuery, nextCursor, undefined, searchType);
      applySearchPage(page, true);
    } finally {
      setIsSearching(false);
    }
  };

  const openEntity = async (entity: DiscoveryEntity) => {
    setIsSearching(true);
    setCollectionError(false);
    setSuggestions([]);
    try {
      const page = entity.type === 'artist'
        ? await fetchDiscoveryArtist(entity.id)
        : entity.type === 'album'
          ? await fetchDiscoveryAlbum(entity.id)
          : await fetchDiscoveryPlaylist(entity.id);
      setCollection(page);
      setCollectionLabel(entity.title);
      setOpenedEntity(entity);
    } catch {
      setCollectionError(true);
    } finally {
      setIsSearching(false);
    }
  };

  const playFrom = (kind: 'search' | 'artist' | 'album' | 'playlist', tracks: Track[], index: number, id: string) => {
    const request = toDiscoveryPlaybackRequest(kind, tracks, { id, position: index });
    if (request) onTrackSelect(request.track, request.tracks, request.discoveryContext);
  };

  const handleKeyPress = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter') handleSearch();
  };

  const selectSearchType = async (nextType: SearchType) => {
    setSearchType(nextType);
    if (!activeQuery) return;
    setIsSearching(true);
    setSearchError(false);
    try {
      const page = await fetchDiscoverySearch(activeQuery, undefined, undefined, nextType);
      applySearchPage(page, false);
    } catch {
      setSearchError(true);
      setResults([]);
      setEntities([]);
    } finally {
      setIsSearching(false);
    }
  };

  const visibleTracks = (collection?.tracks.length ? collection.tracks : results).map((track) => presentTrack(track)).filter((track): track is Track => Boolean(track));
  const visibleEntities = (collection
    ? collection.entities.filter((entry) => entry.id !== openedEntity?.id)
    : entities
  ).filter((entry) => Boolean(entry.title));
  const collectionSections = collection?.catalog?.sections.filter((section) => section.items.length) || [];
  const playKind = collection?.surface === 'artist' || collection?.surface === 'album' || collection?.surface === 'playlist'
    ? collection.surface
    : 'search';
  const playId = openedEntity?.id || collection?.sourceId || activeQuery;
  const canLoadMore = Boolean(!collection && nextCursor) || Boolean(collection?.nextCursor);

  return (
    <div className="catalog-search fade-in">
      <div className="catalog-search-form">
        <div className="catalog-search-field">
          <Search
            className="catalog-search-icon"
            aria-hidden="true"
          />
          <input
            type="text"
            placeholder="Search for songs, artists, albums..."
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            onKeyPress={handleKeyPress}
            className="catalog-search-input"
            aria-autocomplete="list"
          />
          {suggestions.length > 0 && (
            <div className="catalog-suggestions">
              {suggestions.map((suggestion) => (
                <button
                  key={suggestion}
                  type="button"
                  className="catalog-suggestion"
                  onMouseDown={(event) => event.preventDefault()}
                  onClick={() => {
                    setQuery(suggestion);
                    void handleSearch(suggestion);
                  }}
                >
                  {suggestion}
                </button>
              ))}
            </div>
          )}
        </div>
        <button
          onClick={() => void handleSearch()}
          disabled={isSearching}
          className="catalog-search-submit"
        >
          {isSearching ? 'Searching...' : 'Search'}
        </button>
      </div>

      <div className="catalog-filter-tabs" role="tablist" aria-label="Search result type">
        {([
          ['all', 'All'],
          ['song', 'Songs'],
          ['video', 'Videos'],
          ['album', 'Albums'],
          ['artist', 'Artists'],
          ['playlist', 'Playlists'],
        ] as Array<[SearchType, string]>).map(([type, label]) => (
          <button
            key={type}
            type="button"
            role="tab"
            aria-selected={searchType === type}
            onClick={() => void selectSearchType(type)}
            className={`catalog-filter-tab${searchType === type ? ' is-active' : ''}`}
          >
            {label}
          </button>
        ))}
      </div>

      {collection && (
        <button className="catalog-back-button" type="button" onClick={() => { setCollection(null); setCollectionLabel(''); setOpenedEntity(null); setCollectionError(false); }}>← Back to search</button>
      )}

      {collection && (
        <div className="catalog-collection-header">
          <div className="catalog-collection-art">
            <Artwork src={openedEntity?.artworkUrl || visibleTracks[0]?.albumArt} alt="" fallback={<Search size={22} aria-hidden="true" />} />
          </div>
          <div className="catalog-collection-copy">
            <span className="catalog-eyebrow">Collection</span>
            <h3>{collectionLabel}</h3>
            {visibleTracks[0]?.artist && playKind === 'album' ? (
              <p>{visibleTracks[0].artist}</p>
            ) : null}
          </div>
          {visibleTracks.length > 0 && (
            <button type="button" className="catalog-search-play" onClick={() => playFrom(playKind, visibleTracks, 0, playId)}><Play size={15} fill="currentColor" aria-hidden="true" /> Play</button>
          )}
        </div>
      )}

      {collection && collectionSections.length > 0 && (
        <div className="catalog-meta-pills" aria-label="Catalog sections">
          {collectionSections.map((section) => (
            <span key={section.id} className="catalog-meta-pill">
              {section.title} · {section.items.length}
            </span>
          ))}
        </div>
      )}

      {searchError && !collection && (
        <p className="catalog-feedback">Couldn't load those results</p>
      )}
      {collectionError && (
        <p className="catalog-feedback">Couldn't open that</p>
      )}
      {!isSearching && !searchError && !collection && activeQuery && visibleTracks.length === 0 && visibleEntities.length === 0 && (
        <p className="catalog-feedback">Nothing matched</p>
      )}

      {visibleEntities.length > 0 && (
        <div className="catalog-entity-results">
          {visibleEntities.slice(0, collection ? 12 : 6).map((entity) => (
            <button
              key={`${entity.type}:${entity.id}`}
              type="button"
              className="catalog-entity-result"
              onClick={() => openEntity(entity)}
            >
              <Artwork src={entity.artworkUrl} alt="" className="catalog-entity-result-art" fallback={<span className="catalog-entity-result-art catalog-art-placeholder"><Search size={18} aria-hidden="true" /></span>} />
              <span className="catalog-entity-result-copy"><strong>{entity.title}</strong><small>{entity.type}</small></span>
              <span className="catalog-entity-result-arrow" aria-hidden="true">→</span>
            </button>
          ))}
        </div>
      )}

      {visibleTracks.length > 0 && (
        <div className="catalog-search-results scale-in">
          {!collection && (
          <div className="catalog-results-heading"><span className="catalog-eyebrow">Results</span><span>{visibleTracks.length} tracks</span></div>
          )}
          <div className="catalog-search-track-list player-safe-scroll overflow-y-auto custom-scrollbar">
            {visibleTracks.map((track, index) => (
              <article key={`${track.id}-${index}`} className="catalog-search-track" style={{ animationDelay: `${index * 0.03}s` }}>
                  <button type="button" className="catalog-track-main" onClick={() => playFrom(playKind, visibleTracks, index, playId)}>
                      <Artwork
                        src={track.albumArt}
                        alt=""
                        className="catalog-track-art"
                        fallback={<span className="catalog-track-art catalog-art-placeholder"><Search size={18} aria-hidden="true" /></span>}
                      />
                    <span className="catalog-track-copy"><strong>{track.title}</strong><span>{track.artist || 'Unknown artist'}</span><small>{track.album || 'YouTube Music'}</small></span>
                  </button>
                  <div className="catalog-track-actions">
                    <button type="button" className="catalog-icon-button" aria-label={`Play ${track.title}`} onClick={() => playFrom(playKind, visibleTracks, index, playId)}><Play size={16} aria-hidden="true" /></button>
                    <AddToPlaylistButton
                      track={track}
                      variant="icon"
                      playlists={playlists}
                      onAddToPlaylist={onAddToPlaylist}
                      onCreatePlaylist={onCreatePlaylist}
                    />
                  </div>
              </article>
            ))}
          </div>
        </div>
      )}
      {canLoadMore && (
        <button
          type="button"
          className="catalog-more"
          onClick={collection ? async () => {
            if (!collection.nextCursor) return;
            setIsSearching(true);
            try {
              const page = collection.surface === 'artist'
                ? await fetchDiscoveryArtist(collection.sourceId || playId, collection.nextCursor)
                : collection.surface === 'album'
                  ? await fetchDiscoveryAlbum(collection.sourceId || playId, collection.nextCursor)
                  : await fetchDiscoveryPlaylist(collection.sourceId || playId, collection.nextCursor);
              setCollection({
                ...page,
                tracks: [...collection.tracks, ...page.tracks],
                entities: mergeEntities(collection.entities, page.entities),
              });
            } finally {
              setIsSearching(false);
            }
          } : handleLoadMore}
          disabled={isSearching}
        >
          More
        </button>
      )}
    </div>
  );
};
