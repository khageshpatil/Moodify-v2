import { Home, Library, Search, Users } from 'lucide-react';
import { useEffect, useState } from 'react';
import { NavLink, Route, Routes, useLocation } from 'react-router-dom';
import './aura.css';
import { FullPlayer, MiniPlayer } from './Player';
import { PlayerProvider } from './PlayerContext';
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

/** Follows Settings › Appearance on Android: system / light / dark (dark by default). */
function useTheme() {
  useEffect(() => {
    const saved = localStorage.getItem('aura.theme') || 'dark';
    document.documentElement.dataset.theme = saved;
  }, []);
}

function Shell() {
  const [playerOpen, setPlayerOpen] = useState(false);
  const location = useLocation();
  const { unreadTotal } = useSocial();
  useLibrarySync();
  const badge = (to: string) => (to === '/friends' && unreadTotal > 0
    ? <span className="aura-badge" aria-label={`${unreadTotal} unread`}>{unreadTotal > 99 ? '99+' : unreadTotal}</span> : null);
  useTheme();
  useEffect(() => { window.scrollTo({ top: 0 }); setPlayerOpen(false); }, [location.pathname]);
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
          <Route path="/room" element={<RoomScreen />} />
          <Route path="/room/:id" element={<RoomScreen />} />
          <Route path="/mood/:id" element={<MoodScreen />} />
          <Route path="/browse/:kind/:id" element={<BrowseScreen />} />
        </Routes>
      </main>
      <MiniPlayer onOpen={() => setPlayerOpen(true)} />
      <nav className="aura-bottomnav" aria-label="Main">
        {TABS.map(({ to, label, icon: Icon, end }) => (
          <NavLink key={to} to={to} end={end} className="aura-tab"><span className="aura-tab-icon"><Icon size={22} />{badge(to)}</span><span className="type-label-small">{label}</span></NavLink>
        ))}
      </nav>
      {playerOpen && <FullPlayer onClose={() => setPlayerOpen(false)} />}
    </div>
  );
}

/** The redesigned Moodify web app (matches the Android "Aura" design). */
export default function AuraApp() {
  return (
    <PlayerProvider>
      <SocialProvider>
        <RoomProvider>
          <Shell />
        </RoomProvider>
      </SocialProvider>
    </PlayerProvider>
  );
}
