import { useEffect, useRef, useState } from 'react';
import { Search } from 'lucide-react';
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
      const page = await fetchDiscoverySearch(rawQuery.trim());
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
      const page = await fetchDiscoverySearch(activeQuery, nextCursor);
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

  const visibleTracks = (collection?.tracks.length ? collection.tracks : results).map((track) => presentTrack(track)).filter((track): track is Track => Boolean(track));
  const visibleEntities = (collection
    ? collection.entities.filter((entry) => entry.id !== openedEntity?.id)
    : entities
  ).filter((entry) => Boolean(entry.title));
  const playKind = collection?.surface === 'artist' || collection?.surface === 'album' || collection?.surface === 'playlist'
    ? collection.surface
    : 'search';
  const playId = openedEntity?.id || collection?.sourceId || activeQuery;
  const canLoadMore = Boolean(!collection && nextCursor) || Boolean(collection?.nextCursor);

  return (
    <div className="max-w-4xl mx-auto space-y-8 fade-in">
      <div className="flex gap-3 items-center">
        <div className="relative flex-1">
          <Search
            className="absolute left-6 top-1/2 transform -translate-y-1/2 h-5 w-5"
            style={{ color: 'var(--text-tertiary)' }}
          />
          <input
            type="text"
            placeholder="Search for songs, artists, albums..."
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            onKeyPress={handleKeyPress}
            className="search-input w-full pl-14 pr-6"
            style={{ fontSize: '1rem' }}
            aria-autocomplete="list"
          />
          {suggestions.length > 0 && (
            <div className="absolute left-0 right-0 z-10 mt-2 space-y-1" style={{ background: 'var(--surface-frost)', border: '1px solid var(--stroke-subtle)', borderRadius: 16, padding: 8 }}>
              {suggestions.map((suggestion) => (
                <button
                  key={suggestion}
                  type="button"
                  className="text-button w-full text-left px-3 py-2"
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
          className="btn-primary whitespace-nowrap"
          style={{ opacity: isSearching ? 0.6 : 1, cursor: isSearching ? 'wait' : 'pointer' }}
        >
          {isSearching ? 'Searching...' : 'Search'}
        </button>
      </div>

      {collection && (
        <button className="text-button" type="button" onClick={() => { setCollection(null); setCollectionLabel(''); setOpenedEntity(null); setCollectionError(false); }}>Back to search</button>
      )}

      {collection && (
        <div className="flex items-center gap-4">
          <div className="w-16 h-16 rounded-xl overflow-hidden flex-shrink-0" style={{ border: '1px solid var(--stroke-subtle)', background: 'var(--surface-frost)' }}>
            <Artwork src={openedEntity?.artworkUrl || visibleTracks[0]?.albumArt} alt="" className="w-full h-full object-cover" />
          </div>
          <div className="min-w-0 flex-1">
            <h3 className="font-medium truncate" style={{ color: 'var(--text-primary)' }}>{collectionLabel}</h3>
            {visibleTracks[0]?.artist && playKind === 'album' ? (
              <p className="text-sm truncate" style={{ color: 'var(--text-secondary)' }}>{visibleTracks[0].artist}</p>
            ) : null}
          </div>
          {visibleTracks.length > 0 && (
            <button type="button" className="btn-primary px-5 py-2 text-sm" onClick={() => playFrom(playKind, visibleTracks, 0, playId)}>Play</button>
          )}
        </div>
      )}

      {searchError && !collection && (
        <p className="text-sm" style={{ color: 'var(--text-secondary)' }}>Couldn't load those results</p>
      )}
      {collectionError && (
        <p className="text-sm" style={{ color: 'var(--text-secondary)' }}>Couldn't open that</p>
      )}
      {!isSearching && !searchError && !collection && activeQuery && visibleTracks.length === 0 && visibleEntities.length === 0 && (
        <p className="text-sm" style={{ color: 'var(--text-secondary)' }}>Nothing matched</p>
      )}

      {visibleEntities.length > 0 && (
        <div className="space-y-3">
          {visibleEntities.slice(0, collection ? 12 : 6).map((entity) => (
            <button
              key={`${entity.type}:${entity.id}`}
              type="button"
              className="list-item group w-full text-left"
              onClick={() => openEntity(entity)}
            >
              <div className="flex items-center gap-4">
                <div className="w-16 h-16 rounded-xl overflow-hidden flex-shrink-0" style={{ border: '1px solid var(--stroke-subtle)', background: 'var(--surface-frost)' }}>
                  <Artwork src={entity.artworkUrl} alt="" className="w-full h-full object-cover" />
                </div>
                <div className="flex-1 min-w-0">
                  <p className="font-medium truncate" style={{ color: 'var(--text-primary)' }}>{entity.title}</p>
                </div>
              </div>
            </button>
          ))}
        </div>
      )}

      {visibleTracks.length > 0 && (
        <div className="space-y-4 scale-in">
          {!collection && (
          <h3 className="text-sm uppercase tracking-wide" style={{ color: 'var(--text-tertiary)', fontWeight: 500, letterSpacing: '0.5px' }}>
            Search Results
          </h3>
          )}
          <div className="space-y-3 player-safe-scroll overflow-y-auto pr-2 custom-scrollbar">
            {visibleTracks.map((track, index) => (
              <div key={`${track.id}-${index}`} className="list-item group" style={{ animationDelay: `${index * 0.03}s` }}>
                <div className="flex items-center gap-4">
                  <button type="button" className="flex items-center gap-4 min-w-0 flex-1 text-left" onClick={() => playFrom(playKind, visibleTracks, index, playId)}>
                    <div className="relative flex-shrink-0">
                      <Artwork
                        src={track.albumArt}
                        alt=""
                        className="w-16 h-16 rounded-xl object-cover"
                        fallback={<div className="w-16 h-16 rounded-xl" style={{ border: '1px solid var(--stroke-subtle)', background: 'var(--surface-frost)' }} />}
                      />
                    </div>
                    <div className="flex-1 min-w-0">
                      <p className="font-medium truncate mb-1" style={{ color: 'var(--text-primary)', fontWeight: 400 }}>{track.title}</p>
                      {track.artist ? <p className="text-sm truncate mb-0.5" style={{ color: 'var(--text-secondary)', fontWeight: 300 }}>{track.artist}</p> : null}
                      {track.album && playKind !== 'album' ? <p className="text-xs truncate" style={{ color: 'var(--text-tertiary)', fontWeight: 300 }}>{track.album}</p> : null}
                    </div>
                  </button>
                  <div className="flex gap-2 flex-shrink-0">
                    <button onClick={() => playFrom(playKind, visibleTracks, index, playId)} className="btn-primary px-5 py-2 text-sm">Play</button>
                    <AddToPlaylistButton
                      track={track}
                      variant="icon"
                      playlists={playlists}
                      onAddToPlaylist={onAddToPlaylist}
                      onCreatePlaylist={onCreatePlaylist}
                    />
                  </div>
                </div>
              </div>
            ))}
          </div>
        </div>
      )}
      {canLoadMore && (
        <button
          type="button"
          className="btn-ghost"
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
