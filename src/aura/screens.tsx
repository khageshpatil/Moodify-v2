import { ArrowLeft, Clock, Heart, Library as LibraryIcon, ListMusic, Pencil, Play, RotateCw, Search as SearchIcon, SearchX, Send, Settings, Shuffle, Trash2 } from 'lucide-react';
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
import { SendPlaylistDialog } from './social/SocialScreens';
import { TextDialog, type TrackMenuExtra } from './TrackMenu';

const NON_MUSIC = new Set(['video', 'podcast']);
const musicOnly = (section: DiscoveryCatalogSection): DiscoveryCatalogSection => ({
  ...section,
  items: section.items.filter((item) => !NON_MUSIC.has(item.type)),
});

/** Loads a collection; cancels when the inputs change or the screen unmounts. */
function useCollection(load: ((signal: AbortSignal) => Promise<DiscoveryCollectionResult>) | null, deps: unknown[]) {
  const [state, setState] = useState<{ data: DiscoveryCollectionResult | null; error: string | null; loading: boolean; slow: boolean }>({ data: null, error: null, loading: !!load, slow: false });
  const [attempt, setAttempt] = useState(0);
  useEffect(() => {
    if (!load) { setState({ data: null, error: null, loading: false, slow: false }); return; }
    const controller = new AbortController();
    setState((s) => ({ ...s, loading: true, error: null, slow: false }));
    // The free music server sleeps when idle; say so instead of looking stuck.
    const slowTimer = setTimeout(() => setState((s) => (s.loading ? { ...s, slow: true } : s)), 5000);
    load(controller.signal)
      .then((data) => setState({ data, error: null, loading: false, slow: false }))
      .catch((err: unknown) => { if (!controller.signal.aborted) setState({ data: null, error: err instanceof Error ? err.message : 'Something went wrong', loading: false, slow: false }); })
      .finally(() => clearTimeout(slowTimer));
    return () => { controller.abort(); clearTimeout(slowTimer); };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [...deps, attempt]);
  return { ...state, retry: () => setAttempt((n) => n + 1) };
}

/** Skeletons, a "waking up" note when it's slow, or an error with Retry. */
function LoadState({ state, rows = 6, what }: { state: { loading: boolean; slow: boolean; error: string | null; retry: () => void }; rows?: number; what: string }) {
  if (state.loading) {
    return (
      <div className="aura-section">
        {state.slow && <div className="aura-banner" style={{ marginBottom: 16 }}>Waking up the music server… the first load after a quiet spell can take up to a minute.</div>}
        <SkeletonRows count={rows} />
      </div>
    );
  }
  if (!state.error) return null;
  return (
    <EmptyState icon={<SearchX />} title={`Couldn't load ${what}`} message="The music server didn't answer. Check your connection and try again."
      action={<button className="aura-btn aura-btn-primary" onClick={state.retry}><RotateCw size={18} />Try again</button>} />
  );
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
              <div className="aura-picks">{tracks.slice(0, 12).map((track) => <TrackRow key={track.id} track={track} queue={tracks} compact />)}</div>
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
          <div className="aura-picks">{keepListening.map((track) => <TrackRow key={track.id} track={track} queue={keepListening} compact />)}</div>
        </section>
      )}
      <LoadState state={home} what="Home" />
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
      <form role="search" onSubmit={(e) => { e.preventDefault(); submit(text, filter); }} style={{ position: 'relative', zIndex: 20 }}>
        <SearchIcon size={20} className="aura-input-icon" aria-hidden />
        <input ref={inputRef} className="aura-input aura-input-search" type="search" value={text} placeholder="Songs, artists, or a feeling…" aria-label="Search"
          onChange={(e) => setText(e.target.value)} onFocus={(e) => { setFocused(true); e.currentTarget.setSelectionRange(e.currentTarget.value.length, e.currentTarget.value.length); }}
          onBlur={() => setTimeout(() => setFocused(false), 150)} />
        {focused && suggestions.length > 0 && (
          <div className="aura-suggest" role="listbox" aria-label="Suggestions">
            {suggestions.slice(0, 8).map((s) => (
              <button key={s} type="button" role="option" aria-selected={false} className="aura-suggest-item" onMouseDown={(e) => e.preventDefault()} onClick={() => submit(s)}>
                <SearchIcon size={16} aria-hidden /><span className="aura-ellipsis">{s}</span>
              </button>
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
                {recent.map((r) => <button key={r} className="aura-chip" onClick={() => submit(r)}><Clock size={14} />{r}</button>)}
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
            <LoadState state={results} rows={8} what="results" />
            {!results.loading && !results.error && tracks.length === 0 && browseItems.length === 0 && (
              <EmptyState icon={<SearchX />} title={filter === 'all' ? 'No matches' : `No ${FILTERS.find(([id]) => id === filter)?.[1].toLowerCase()} for “${submitted}”`} message="Try another spelling, or All." />
            )}
            {!results.loading && (filter === 'all' || filter === 'song') && tracks.length > 0 && (
              <section>
                {filter === 'all' && <SectionHeader title="Songs" action={tracks.length > 6 ? <button className="aura-btn-text" onClick={() => setParams({ q: submitted, type: 'song' })}>See all</button> : undefined} />}
                {(filter === 'all' ? tracks.slice(0, 6) : tracks).map((track) => <TrackRow key={track.id} track={track} queue={tracks} />)}
              </section>
            )}
            {!results.loading && filter !== 'song' && (['artist', 'album', 'playlist'] as const).map((type) => {
              const items = browseItems.filter((item) => item.type === type && browsePath(item));
              if (!items.length) return null;
              const title = { artist: 'Artists', album: 'Albums', playlist: 'Playlists' }[type];
              return (
                <section key={type} className="aura-section">
                  {filter === 'all' && <SectionHeader title={title} />}
                  <div className={filter === 'all' ? 'aura-shelf' : 'aura-grid'}>
                    {items.map((item) => <MediaCard key={`${item.type}:${item.id}`} item={item} queue={[]} />)}
                  </div>
                </section>
              );
            })}
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
      <button className="aura-icon-btn" aria-label="Back" onClick={() => navigate(-1)}><ArrowLeft size={22} /></button>
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
      <LoadState state={page} what="this page" />
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
    fetchDiscoverySearch(mood.query, undefined, controller.signal, 'song')
      .then((result) => {
        const all = (result.catalog?.sections ?? []).flatMap((section) => section.items.flatMap((item) => (item.track ? [item.track] : [])));
        const songs = [...new Map(all.map((t) => [t.id, t])).values()].filter((t) => !t.duration || t.duration < 12 * 60);
        return songs.length ? songs : searchMoodifyTracks(mood.query, 30, controller.signal);
      })
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
  const [dialog, setDialog] = useState<'new' | 'rename' | 'send' | null>(null);
  const playlist = player.playlists.find((p) => p.id === view);
  const tracks = view === 'liked' ? player.favorites : view === 'recent' ? player.recentlyPlayed : playlist?.tracks ?? [];
  const title = view === 'liked' ? 'Liked songs' : view === 'recent' ? 'Recently played' : playlist?.name ?? '';
  const tiles = [
    { id: 'liked', title: 'Liked songs', count: player.favorites.length, icon: <Heart size={22} />, hue: 'var(--mood-violet)' },
    { id: 'recent', title: 'Recently played', count: player.recentlyPlayed.length, icon: <Clock size={22} />, hue: 'var(--mood-amber)' },
  ];
  const extrasFor = (track: Track): TrackMenuExtra[] => (playlist
    ? [{ label: 'Remove from this playlist', danger: true, run: () => player.removeFromPlaylist(playlist.id, track.id) }]
    : []);
  const shuffled = () => { const s = [...tracks].sort(() => Math.random() - 0.5); player.playTrack(s[0], s); };
  return (
    <div className="aura-page">
      <div className="aura-glow" />
      <div className="aura-row" style={{ flexWrap: 'wrap' }}>
        <h1 className="type-display-small" style={{ margin: 0, flex: 1 }}>Your library</h1>
        <button className="aura-btn aura-btn-tonal" onClick={() => setDialog('new')}><ListMusic size={18} />New playlist</button>
      </div>
      <div className="aura-tiles" style={{ marginTop: 'var(--space-lg)', gridTemplateColumns: 'repeat(auto-fill, minmax(180px, 1fr))' }}>
        {tiles.map((tile) => (
          <button key={tile.id} className="aura-card aura-lib-tile" aria-pressed={view === tile.id}
            style={{ background: `linear-gradient(135deg, color-mix(in srgb, ${tile.hue} 30%, var(--aura-surface-high)), color-mix(in srgb, ${tile.hue} 10%, var(--aura-surface-high)))` }}
            onClick={() => setView(tile.id)}>
            <span style={{ color: tile.hue }}>{tile.icon}</span>
            <div className="type-title-medium" style={{ marginTop: 8 }}>{tile.title}</div>
            <div className="type-body-small aura-muted">{tile.count === 1 ? '1 song' : `${tile.count} songs`}</div>
          </button>
        ))}
        {player.playlists.map((p) => (
          <button key={p.id} className="aura-card aura-lib-tile" aria-pressed={view === p.id} onClick={() => setView(p.id)}>
            {p.tracks[0]?.albumArt ? <Artwork src={p.tracks[0].albumArt} size={40} placeholder={p.id} /> : <ListMusic size={22} color="var(--aura-primary)" />}
            <div className="type-title-medium aura-ellipsis" style={{ marginTop: 8 }}>{p.name}</div>
            <div className="type-body-small aura-muted">{p.tracks.length === 1 ? '1 song' : `${p.tracks.length} songs`}</div>
          </button>
        ))}
      </div>
      <section className="aura-section">
        <div className="aura-row" style={{ flexWrap: 'wrap', marginBottom: 'var(--space-md)' }}>
          <h2 className="type-title-large" style={{ margin: 0, flex: 1, minWidth: 160 }}>{title}</h2>
          {tracks.length > 0 && <button className="aura-btn aura-btn-primary" onClick={() => player.playTrack(tracks[0], tracks)}><Play size={18} fill="currentColor" />Play</button>}
          {tracks.length > 1 && <button className="aura-btn aura-btn-tonal" onClick={shuffled}><Shuffle size={18} />Shuffle</button>}
          {playlist && (
            <>
              {playlist.tracks.length > 0 && <button className="aura-icon-btn" aria-label="Send to a friend" title="Send to a friend" onClick={() => setDialog('send')}><Send size={20} /></button>}
              <button className="aura-icon-btn" aria-label="Rename playlist" title="Rename" onClick={() => setDialog('rename')}><Pencil size={20} /></button>
              <button className="aura-icon-btn" aria-label="Delete playlist" title="Delete" style={{ color: 'var(--aura-error)' }}
                onClick={() => { if (confirm(`Delete "${playlist.name}"?`)) { player.deletePlaylist(playlist.id); setView('liked'); } }}><Trash2 size={20} /></button>
            </>
          )}
        </div>
        {tracks.length === 0 ? (
          <EmptyState icon={<LibraryIcon />} title="Nothing here yet"
            message={playlist ? 'Add songs with ⋮ › Add to playlist on any song.' : view === 'liked' ? "Like songs with the heart, and they'll collect here." : 'Songs you play show up here.'} />
        ) : tracks.map((track) => <TrackRow key={track.id} track={track} queue={tracks} menuExtras={extrasFor(track)} />)}
      </section>
      {dialog === 'new' && <TextDialog title="New playlist" label="Playlist name" confirm="Create" onClose={() => setDialog(null)} onSubmit={(name) => { player.createPlaylist(name); setDialog(null); }} />}
      {dialog === 'rename' && playlist && <TextDialog title="Rename playlist" label="Playlist name" initial={playlist.name} confirm="Save" onClose={() => setDialog(null)} onSubmit={(name) => { player.renamePlaylist(playlist.id, name); setDialog(null); }} />}
      {dialog === 'send' && playlist && <SendPlaylistDialog name={playlist.name} tracks={playlist.tracks} onClose={() => setDialog(null)} />}
    </div>
  );
}
