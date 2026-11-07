import { Clock, Heart, Library as LibraryIcon, ListMusic, Play, SearchX, Settings, Shuffle, X } from 'lucide-react';
import { useEffect, useMemo, useRef, useState } from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import type { Track } from '@/data/mockMusic';
import {
  fetchDiscoveryAlbum, fetchDiscoveryArtist, fetchDiscoveryBrowse, fetchDiscoveryHome, fetchDiscoveryPlaylist, fetchDiscoverySearch,
  fetchSearchSuggestions, searchMoodifyTracks, type DiscoveryCatalogSection, type DiscoveryCollectionResult,
} from '@/services/moodifyMusicApi';
import { MOODS, greeting, moodById } from './moods';
import { usePlayer } from './PlayerContext';
import { Artwork, EmptyState, MediaCard, SectionHeader, SkeletonRows, TrackRow, browsePath } from './ui';

const NON_MUSIC = new Set(['video', 'podcast']);
const musicOnly = (section: DiscoveryCatalogSection): DiscoveryCatalogSection => ({
  ...section,
  items: section.items.filter((item) => !NON_MUSIC.has(item.type)),
});

/** Loads a collection; cancels when the inputs change or the screen unmounts. */
function useCollection(load: ((signal: AbortSignal) => Promise<DiscoveryCollectionResult>) | null, deps: unknown[]) {
  const [state, setState] = useState<{ data: DiscoveryCollectionResult | null; error: string | null; loading: boolean }>({ data: null, error: null, loading: !!load });
  useEffect(() => {
    if (!load) { setState({ data: null, error: null, loading: false }); return; }
    const controller = new AbortController();
    setState((s) => ({ ...s, loading: true, error: null }));
    load(controller.signal)
      .then((data) => setState({ data, error: null, loading: false }))
      .catch((err: unknown) => { if (!controller.signal.aborted) setState({ data: null, error: err instanceof Error ? err.message : 'Something went wrong', loading: false }); });
    return () => controller.abort();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps);
  return state;
}

function Sections({ sections, featureFirst = false }: { sections: DiscoveryCatalogSection[]; featureFirst?: boolean }) {
  const featuredId = featureFirst ? sections.find((s) => s.items.every((i) => !i.track && i.type !== 'artist'))?.id : undefined;
  return (
    <>
      {sections.map((section) => {
        const tracks = section.items.flatMap((item) => (item.track ? [item.track] : []));
        const isList = section.layout === 'list' && tracks.length >= section.items.length / 2;
        return (
          <section key={`${section.id}:${section.title}`} className="aura-section">
            <SectionHeader title={section.title} />
            {isList ? (
              <div className="aura-picks">{tracks.slice(0, 12).map((track) => <TrackRow key={track.id} track={track} queue={tracks} />)}</div>
            ) : (
              <div className={`aura-shelf ${section.id === featuredId ? 'aura-shelf-wide' : ''}`}>
                {section.items.map((item) => <MediaCard key={`${item.type}:${item.id}`} item={item} queue={tracks} wide={section.id === featuredId} />)}
              </div>
            )}
          </section>
        );
      })}
    </>
  );
}

function MoodPills() {
  return (
    <div className="aura-chips" style={{ marginTop: 'var(--space-lg)' }}>
      {MOODS.map((mood) => (
        <Link key={mood.id} to={`/mood/${mood.id}`} className="aura-mood"
          style={{ borderColor: `color-mix(in srgb, ${mood.hue} 50%, transparent)`, background: `color-mix(in srgb, ${mood.hue} 16%, transparent)` }}>
          <span aria-hidden>{mood.emoji}</span>{mood.label}
        </Link>
      ))}
    </div>
  );
}

export function HomeScreen() {
  const player = usePlayer();
  const home = useCollection((signal) => fetchDiscoveryHome(undefined, signal), []);
  const sections = useMemo(() => (home.data?.catalog?.sections ?? []).map(musicOnly).filter((s) => s.items.length > 0), [home.data]);
  const keepListening = player.recentlyPlayed.slice(0, 8);
  return (
    <div className="aura-page">
      <div className="aura-glow" />
      <div className="aura-row">
        <h1 className="type-headline-medium" style={{ margin: 0, flex: 1 }}>{greeting()}</h1>
        <Link to="/account" className="aura-icon-btn" aria-label="Settings and your Moodify code"><Settings size={22} /></Link>
      </div>
      <MoodPills />
      {keepListening.length > 0 && (
        <section className="aura-section">
          <SectionHeader title="Keep listening" />
          <div className="aura-picks">{keepListening.map((track) => <TrackRow key={track.id} track={track} queue={keepListening} />)}</div>
        </section>
      )}
      {home.loading && <div className="aura-section"><SkeletonRows /></div>}
      {home.error && !home.loading && (
        <EmptyState icon={<SearchX />} title="Couldn't load Home" message="Check that the Moodify music server is running, then try again." />
      )}
      <Sections sections={sections} featureFirst />
    </div>
  );
}

const FILTERS = [
  ['all', 'All'], ['song', 'Songs'], ['album', 'Albums'], ['artist', 'Artists'], ['playlist', 'Playlists'],
] as const;
type Filter = typeof FILTERS[number][0];

export function SearchScreen() {
  const [params, setParams] = useSearchParams();
  const submitted = params.get('q') ?? '';
  const filter = (params.get('type') as Filter) || 'all';
  const [text, setText] = useState(submitted);
  const [suggestions, setSuggestions] = useState<string[]>([]);
  const [focused, setFocused] = useState(false);
  const inputRef = useRef<HTMLInputElement>(null);
  const [recent, setRecent] = useState<string[]>(() => { try { return JSON.parse(localStorage.getItem('aura.recent') || '[]'); } catch { return []; } });

  useEffect(() => setText(submitted), [submitted]);
  useEffect(() => {
    if (!focused || text.trim().length < 2) { setSuggestions([]); return; }
    const controller = new AbortController();
    const timer = setTimeout(() => fetchSearchSuggestions(text.trim(), controller.signal).then(setSuggestions).catch(() => {}), 220);
    return () => { clearTimeout(timer); controller.abort(); };
  }, [text, focused]);

  const submit = (query: string, type: Filter = 'all') => {
    const q = query.trim();
    if (!q) return;
    const next = [q, ...recent.filter((r) => r.toLowerCase() !== q.toLowerCase())].slice(0, 10);
    setRecent(next);
    try { localStorage.setItem('aura.recent', JSON.stringify(next)); } catch { /* private mode */ }
    setParams(type === 'all' ? { q } : { q, type });
    inputRef.current?.blur();
  };

  const results = useCollection(submitted ? (signal) => fetchDiscoverySearch(submitted, undefined, signal, filter) : null, [submitted, filter]);
  const sections = (results.data?.catalog?.sections ?? []).map(musicOnly).filter((s) => s.items.length > 0);
  const tracks = [...new Map(sections.flatMap((s) => s.items.flatMap((i) => (i.track ? [i.track] : []))).map((t) => [t.id, t])).values()];
  const browseItems = sections.flatMap((s) => s.items.filter((i) => !i.track));

  return (
    <div className="aura-page">
      <div className="aura-glow" />
      <h1 className="type-display-small" style={{ margin: '0 0 var(--space-md)' }}>Search</h1>
      <form role="search" onSubmit={(e) => { e.preventDefault(); submit(text, filter); }} style={{ position: 'relative' }}>
        <input ref={inputRef} className="aura-input" type="search" value={text} placeholder="Songs, artists, or a feeling…" aria-label="Search"
          onChange={(e) => setText(e.target.value)} onFocus={(e) => { setFocused(true); e.currentTarget.setSelectionRange(e.currentTarget.value.length, e.currentTarget.value.length); }}
          onBlur={() => setTimeout(() => setFocused(false), 150)} />
        {focused && suggestions.length > 0 && (
          <div className="aura-card" style={{ position: 'absolute', zIndex: 5, top: 62, left: 0, right: 0, padding: 6 }} role="listbox">
            {suggestions.slice(0, 8).map((s) => (
              <button key={s} role="option" aria-selected={false} className="aura-track" style={{ minHeight: 44 }} onMouseDown={(e) => e.preventDefault()} onClick={() => submit(s)}>{s}</button>
            ))}
          </div>
        )}
      </form>

      {!submitted && (
        <>
          {recent.length > 0 && (
            <section className="aura-section">
              <SectionHeader title="Recent" action={<button className="aura-btn-text" onClick={() => { setRecent([]); localStorage.removeItem('aura.recent'); }}>Clear</button>} />
              <div className="aura-chips" style={{ flexWrap: 'wrap' }}>
                {recent.map((r) => <button key={r} className="aura-chip" onClick={() => submit(r)}><Clock size={14} style={{ marginRight: 6, verticalAlign: -2 }} />{r}</button>)}
              </div>
            </section>
          )}
          <section className="aura-section">
            <SectionHeader title="Moods" />
            <div className="aura-tiles">
              {MOODS.map((mood) => (
                <Link key={mood.id} to={`/mood/${mood.id}`} className="aura-tile" style={{ background: `linear-gradient(135deg, ${mood.hue}, color-mix(in srgb, ${mood.hue} 55%, black))` }}>
                  <span>{mood.label}</span>
                </Link>
              ))}
            </div>
          </section>
        </>
      )}

      {submitted && (
        <>
          <div className="aura-chips" style={{ marginTop: 'var(--space-md)' }}>
            {FILTERS.map(([id, label]) => (
              <button key={id} className="aura-chip" aria-pressed={filter === id} onClick={() => setParams(id === 'all' ? { q: submitted } : { q: submitted, type: id })}>{label}</button>
            ))}
          </div>
          <div style={{ marginTop: 'var(--space-lg)' }}>
            {results.loading && <SkeletonRows count={8} />}
            {!results.loading && results.error && <EmptyState icon={<SearchX />} title="Search failed" message={results.error} />}
            {!results.loading && !results.error && tracks.length === 0 && browseItems.length === 0 && (
              <EmptyState icon={<SearchX />} title={filter === 'all' ? 'No matches' : `No ${FILTERS.find(([id]) => id === filter)?.[1].toLowerCase()} for “${submitted}”`} message="Try another spelling, or All." />
            )}
            {!results.loading && (filter === 'all' || filter === 'song') && tracks.length > 0 && (
              <section>
                {filter === 'all' && <SectionHeader title="Songs" />}
                {tracks.map((track) => <TrackRow key={track.id} track={track} queue={tracks} />)}
              </section>
            )}
            {!results.loading && browseItems.length > 0 && filter !== 'song' && (
              <section className={filter === 'all' ? 'aura-section' : ''}>
                {browseItems.map((item) => {
                  const path = browsePath(item);
                  return path && (
                    <Link key={`${item.type}:${item.id}`} to={path} className="aura-track">
                      <Artwork src={item.artworkUrl} size={52} round={item.type === 'artist'} placeholder={item.id} />
                      <span className="aura-track-text">
                        <span className="type-title-small aura-ellipsis" style={{ display: 'block' }}>{item.title}</span>
                        <span className="type-body-small aura-muted" style={{ display: 'block', textTransform: 'capitalize' }}>{item.type}</span>
                      </span>
                    </Link>
                  );
                })}
              </section>
            )}
          </div>
        </>
      )}
    </div>
  );
}

/** Album, artist or playlist page. */
export function BrowseScreen() {
  const { kind = 'playlist', id = '' } = useParams();
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const player = usePlayer();
  const page = useCollection((signal) => {
    if (kind === 'artist') return fetchDiscoveryArtist(id, undefined, signal);
    if (kind === 'album') return fetchDiscoveryAlbum(id, undefined, signal);
    if (kind === 'playlist') return fetchDiscoveryPlaylist(id, undefined, signal);
    return fetchDiscoveryBrowse(id, params.get('params') ?? undefined, undefined, signal);
  }, [kind, id]);
  const sections = (page.data?.catalog?.sections ?? []).map(musicOnly).filter((s) => s.items.length > 0);
  const tracks = page.data?.tracks ?? [];
  const title = page.data?.catalog?.title || page.data?.entities?.[0]?.title || '';
  const hero = page.data?.entities?.[0]?.artworkUrl || tracks[0]?.albumArt;
  const listSection = sections.find((s) => s.items.some((i) => i.track));
  const otherSections = sections.filter((s) => s !== listSection);
  return (
    <div className="aura-page">
      <div className="aura-glow" />
      <button className="aura-icon-btn" aria-label="Back" onClick={() => navigate(-1)}>←</button>
      <div style={{ display: 'grid', justifyItems: 'center', gap: 12, textAlign: 'center', marginTop: 8 }}>
        <Artwork src={hero} size={232} radius="var(--radius-lg)" round={kind === 'artist'} placeholder={id} />
        <div className="type-label-small" style={{ color: 'var(--aura-primary)', textTransform: 'uppercase' }}>{kind}</div>
        <h1 className="type-headline-large" style={{ margin: 0 }}>{title}</h1>
        {tracks.length > 0 && (
          <div className="aura-row">
            <button className="aura-btn aura-btn-primary" onClick={() => player.playTrack(tracks[0], tracks)}><Play size={18} fill="currentColor" />Play</button>
            <button className="aura-btn aura-btn-tonal" onClick={() => { const s = [...tracks].sort(() => Math.random() - 0.5); player.playTrack(s[0], s); }}><Shuffle size={18} />Shuffle</button>
          </div>
        )}
      </div>
      {page.loading && <div className="aura-section"><SkeletonRows /></div>}
      {page.error && <EmptyState icon={<SearchX />} title="Couldn't open this page" message={page.error} />}
      {listSection && (
        <section className="aura-section">
          {sections.length > 1 && <SectionHeader title={listSection.title || 'Songs'} />}
          {(kind === 'artist' ? tracks.slice(0, 6) : tracks).map((track, index) => (
            <TrackRow key={track.id} track={track} queue={tracks} number={kind === 'album' ? index + 1 : undefined} />
          ))}
        </section>
      )}
      <Sections sections={otherSections} />
    </div>
  );
}

export function MoodScreen() {
  const { id } = useParams();
  const mood = moodById(id) ?? MOODS[0];
  const player = usePlayer();
  const [state, setState] = useState<{ tracks: Track[]; loading: boolean }>({ tracks: [], loading: true });
  useEffect(() => {
    const controller = new AbortController();
    setState({ tracks: [], loading: true });
    searchMoodifyTracks(mood.query, 30, controller.signal)
      .then((tracks) => setState({ tracks, loading: false }))
      .catch(() => { if (!controller.signal.aborted) setState({ tracks: [], loading: false }); });
    return () => controller.abort();
  }, [mood.query]);
  return (
    <div className="aura-page" style={{ ['--aura-glow' as string]: mood.hue }}>
      <div className="aura-glow" />
      <div style={{ display: 'grid', justifyItems: 'center', gap: 12, textAlign: 'center' }}>
        <div style={{ width: 196, height: 196, borderRadius: 'var(--radius-xl)', display: 'grid', placeItems: 'center', fontSize: 88, background: `linear-gradient(135deg, ${mood.hue}, color-mix(in srgb, ${mood.hue} 60%, black))` }}>{mood.emoji}</div>
        <div className="type-label-small" style={{ color: 'var(--aura-primary)' }}>MOOD</div>
        <h1 className="type-headline-large" style={{ margin: 0 }}>{mood.label}</h1>
        {state.tracks.length > 0 && (
          <div className="aura-row">
            <button className="aura-btn aura-btn-primary" onClick={() => player.playTrack(state.tracks[0], state.tracks)}><Play size={18} fill="currentColor" />Play</button>
            <button className="aura-btn aura-btn-tonal" onClick={() => { const s = [...state.tracks].sort(() => Math.random() - 0.5); player.playTrack(s[0], s); }}><Shuffle size={18} />Shuffle</button>
          </div>
        )}
      </div>
      <section className="aura-section">
        {state.loading ? <SkeletonRows /> : state.tracks.map((track) => <TrackRow key={track.id} track={track} queue={state.tracks} />)}
      </section>
    </div>
  );
}

export function LibraryScreen() {
  const player = usePlayer();
  const [view, setView] = useState<'liked' | 'recent' | string>('liked');
  const playlist = player.playlists.find((p) => p.id === view);
  const tracks = view === 'liked' ? player.favorites : view === 'recent' ? player.recentlyPlayed : playlist?.tracks ?? [];
  const tiles = [
    { id: 'liked', title: 'Liked songs', count: player.favorites.length, icon: <Heart size={22} />, hue: 'var(--mood-violet)' },
    { id: 'recent', title: 'Recently played', count: player.recentlyPlayed.length, icon: <Clock size={22} />, hue: 'var(--mood-amber)' },
  ];
  return (
    <div className="aura-page">
      <div className="aura-glow" />
      <div className="aura-row">
        <h1 className="type-display-small" style={{ margin: 0, flex: 1 }}>Your library</h1>
        <button className="aura-btn aura-btn-tonal" onClick={() => { const name = prompt('Playlist name'); if (name?.trim()) player.createPlaylist(name.trim()); }}>New playlist</button>
      </div>
      <div className="aura-tiles" style={{ marginTop: 'var(--space-lg)', gridTemplateColumns: 'repeat(auto-fill, minmax(200px, 1fr))' }}>
        {tiles.map((tile) => (
          <button key={tile.id} className="aura-card" aria-pressed={view === tile.id}
            style={{ textAlign: 'left', background: `linear-gradient(135deg, color-mix(in srgb, ${tile.hue} 30%, var(--aura-surface-high)), color-mix(in srgb, ${tile.hue} 10%, var(--aura-surface-high)))`, outline: view === tile.id ? '2px solid var(--aura-primary)' : 'none' }}
            onClick={() => setView(tile.id)}>
            <span style={{ color: tile.hue }}>{tile.icon}</span>
            <div className="type-title-medium" style={{ marginTop: 8 }}>{tile.title}</div>
            <div className="type-body-small aura-muted">{tile.count === 1 ? '1 song' : `${tile.count} songs`}</div>
          </button>
        ))}
        {player.playlists.map((p) => (
          <button key={p.id} className="aura-card" style={{ textAlign: 'left', outline: view === p.id ? '2px solid var(--aura-primary)' : 'none' }} onClick={() => setView(p.id)}>
            <ListMusic size={22} color="var(--aura-primary)" />
            <div className="type-title-medium aura-ellipsis" style={{ marginTop: 8 }}>{p.name}</div>
            <div className="type-body-small aura-muted">{p.tracks.length === 1 ? '1 song' : `${p.tracks.length} songs`}</div>
          </button>
        ))}
      </div>
      <section className="aura-section">
        {tracks.length === 0 ? (
          <EmptyState icon={<LibraryIcon />} title="Nothing here yet" message="Like songs with the heart, and they'll collect here." />
        ) : tracks.map((track) => <TrackRow key={track.id} track={track} queue={tracks} />)}
        {playlist && (
          <button className="aura-btn-text" style={{ marginTop: 12, color: 'var(--aura-error)' }} onClick={() => { player.deletePlaylist(playlist.id); setView('liked'); }}>
            <X size={14} /> Delete playlist
          </button>
        )}
      </section>
    </div>
  );
}
