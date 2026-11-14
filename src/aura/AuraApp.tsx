import { Home, Library, Search, Users } from 'lucide-react';
import { useEffect, useRef, useState } from 'react';
import { NavLink, Route, Routes, useLocation } from 'react-router-dom';
import './aura.css';
import { FullPlayer, MiniPlayer, type View } from './Player';
import { getCanonicalPlaybackEngine } from '@/playback/PlaybackEngine';
import { PlayerProvider, usePlayer } from './PlayerContext';
import { useTheme } from './theme';
import { TrackMenuProvider } from './TrackMenu';
import { PrivacyScreen } from './PrivacyScreen';
import { BrowseScreen, HomeScreen, LibraryScreen, MoodScreen, SearchScreen } from './screens';
import { RoomProvider } from './social/RoomProvider';
import { RoomScreen } from './social/RoomScreen';
import { ChatScreen } from './social/ChatScreen';
import { useLibrarySync } from './social/librarySync';
import { SocialProvider, useSocial } from './social/SocialProvider';
import { AccountScreen, FriendsScreen } from './social/SocialScreens';

const TABS = [
  { to: '/', label: 'Home', icon: Home, end: true },
  { to: '/search', label: 'Search', icon: Search, end: false },
  { to: '/library', label: 'Library', icon: Library, end: false },
  { to: '/friends', label: 'Friends', icon: Users, end: false },
];

const APK_URL = 'https://github.com/khageshpatil/Moodify-v2/releases/latest';
const NOTICE_KEY = 'aura.backgroundNotice';

/**
 * Phones pause the web player when Chrome is in the background or the screen is off.
 * The first time that happens, say why once, and where background listening works.
 */
function BackgroundPlayNotice() {
  const [show, setShow] = useState(false);
  useEffect(() => {
    const media = getCanonicalPlaybackEngine().getMedia();
    const onPause = () => { try { if (!localStorage.getItem(NOTICE_KEY)) setShow(true); } catch { setShow(true); } };
    media.addEventListener('systempause', onPause);
    return () => media.removeEventListener('systempause', onPause);
  }, []);
  if (!show) return null;
  const close = () => { try { localStorage.setItem(NOTICE_KEY, '1'); } catch { /* private mode */ } setShow(false); };
  const android = /Android/i.test(navigator.userAgent);
  return (
    <div className="aura-dialog-backdrop" onClick={close}>
      <div className="aura-dialog" role="dialog" aria-label="Music paused in the background" onClick={(e) => e.stopPropagation()}>
        <div className="type-title-large">Music paused while you were away</div>
        <div className="type-body-medium aura-muted">
          Phones pause music in the browser when Chrome is in the background or the screen is off. It carries on when you come back.
          {android ? ' For music with the screen off, use the Moodify app for Android.' : ''}
        </div>
        <div className="aura-row" style={{ justifyContent: 'flex-end' }}>
          <button className="aura-btn-text" onClick={close}>OK</button>
          {android && <a className="aura-btn aura-btn-primary" href={APK_URL} target="_blank" rel="noreferrer" onClick={close}>Get the app</a>}
        </div>
      </div>
    </div>
  );
}

/** Space / K play-pause, J / L ten seconds back / ahead, Shift+N / Shift+P next / previous. */
function usePlaybackKeys() {
  const player = usePlayer();
  const ref = useRef(player);
  ref.current = player;
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const el = e.target as HTMLElement | null;
      if (e.ctrlKey || e.metaKey || e.altKey || !ref.current.currentTrack) return;
      if (el && (el.isContentEditable || /^(INPUT|TEXTAREA|SELECT|BUTTON|A)$/.test(el.tagName))) return;
      const p = ref.current;
      const key = e.key.toLowerCase();
      if (key === ' ' || key === 'k') { e.preventDefault(); p.togglePlayPause(); }
      else if (key === 'j') p.seekTo(Math.max(0, p.currentTime - 10));
      else if (key === 'l') p.seekTo(p.currentTime + 10);
      else if (e.shiftKey && key === 'n') p.playNext();
      else if (e.shiftKey && key === 'p') p.playPrevious();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);
}

function Shell() {
  const [playerView, setPlayerView] = useState<View | null>(null);
  const setPlayerOpen = (open: boolean) => setPlayerView(open ? 'cover' : null);
  const location = useLocation();
  const { unreadTotal } = useSocial();
  useLibrarySync();
  const badge = (to: string) => (to === '/friends' && unreadTotal > 0
    ? <span className="aura-badge" aria-label={`${unreadTotal} unread`}>{unreadTotal > 99 ? '99+' : unreadTotal}</span> : null);
  useTheme();
  usePlaybackKeys();
  useEffect(() => { window.scrollTo({ top: 0 }); setPlayerView(null); }, [location.pathname]);
  return (
    <div className="aura aura-shell">
      <nav className="aura-sidebar" aria-label="Main">
        <div className="aura-brand"><span className="aura-brand-mark">♪</span><span className="type-title-large">Moodify</span></div>
        {TABS.map(({ to, label, icon: Icon, end }) => (
          <NavLink key={to} to={to} end={end} className="aura-sidenav-item"><Icon size={20} /><span style={{ flex: 1 }}>{label}</span>{badge(to)}</NavLink>
        ))}
      </nav>
      <main className="aura-main">
        <Routes>
          <Route path="/" element={<HomeScreen />} />
          <Route path="/search" element={<SearchScreen />} />
          <Route path="/library" element={<LibraryScreen />} />
          <Route path="/friends" element={<FriendsScreen />} />
          <Route path="/chat/:code" element={<ChatScreen />} />
          <Route path="/account" element={<AccountScreen />} />
          <Route path="/privacy" element={<PrivacyScreen />} />
          <Route path="/room" element={<RoomScreen />} />
          <Route path="/room/:id" element={<RoomScreen />} />
          <Route path="/mood/:id" element={<MoodScreen />} />
          <Route path="/browse/:kind/:id" element={<BrowseScreen />} />
        </Routes>
      </main>
      <MiniPlayer onOpen={(view) => setPlayerView(view ?? 'cover')} />
      <nav className="aura-bottomnav" aria-label="Main">
        {TABS.map(({ to, label, icon: Icon, end }) => (
          <NavLink key={to} to={to} end={end} className="aura-tab"><span className="aura-tab-icon"><Icon size={22} />{badge(to)}</span><span className="type-label-small">{label}</span></NavLink>
        ))}
      </nav>
      <BackgroundPlayNotice />
      {playerView && <FullPlayer initialView={playerView} onClose={() => setPlayerOpen(false)} />}
    </div>
  );
}

/** The redesigned Moodify web app (matches the Android "Aura" design). */
export default function AuraApp() {
  return (
    <PlayerProvider>
      <SocialProvider>
        <RoomProvider>
          <TrackMenuProvider>
            <Shell />
          </TrackMenuProvider>
        </RoomProvider>
      </SocialProvider>
    </PlayerProvider>
  );
}
