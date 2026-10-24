import { ArrowRight, BarChart3, Compass, Disc3, Heart, Loader2, Pause, Play, Search, Sparkles } from 'lucide-react';
import type { Track } from '@/data/mockMusic';
import type { HistoryItem } from '@/hooks/useMusicPlayer';
import { IntentComposer } from './IntentComposer';
import type { ListeningSignals, DiscoveryContext } from '@/listening/listeningTypes';
import { discoveryContextForLibrary, discoveryContextForSearch } from '@/listening/discoveryAttribution';
import type { HomeItem, MoodifyHomeModel } from '@/home/homeTypes';
import { playbackForHomeItem } from '@/home/homePlayback';
import { sectionById } from '@/home/homePresentation';
import { presentTrack } from '@/presentation/providerPresentation';
import { Artwork } from './Artwork';
import { ProviderHomeRail } from './ProviderHomeRail';
import type { DiscoveryCollectionResult } from '@/services/moodifyMusicApi';
import type { CatalogSurface } from './DiscoverView';

export type HomeDestination = CatalogSurface | 'search';

interface ListeningHomeProps {
  recentlyPlayed: Track[];
  favorites: Track[];
  history: HistoryItem[];
  currentTrack: Track | null;
  isPlaying: boolean;
  playbackStatus?: 'idle' | 'loading' | 'ready' | 'playing' | 'paused' | 'ended' | 'failed';
  signals: ListeningSignals;
  onIntent: (intent: string, discoveryContext?: DiscoveryContext) => void;
  onVibeSelect: (vibeId: string) => void;
  onPlay: (track: Track, tracks?: Track[], discoveryContext?: DiscoveryContext) => void;
  onQueue: (track: Track) => void;
  homeModel?: MoodifyHomeModel;
  playlists?: Array<{ id: string; tracks: Track[] }>;
  providerHome?: DiscoveryCollectionResult | null;
  onOpenCatalog: (destination?: HomeDestination) => void;
}

const continueThreadNote = (item: HomeItem, playingNow: boolean) => {
  const leftover = Math.max(0, (item.queue?.length || 1) - 1);
  const origin = item.owner === 'mix' || item.discoveryContext?.type === 'recommendation'
    ? 'From Moodify Mix'
    : item.discoveryContext?.type === 'vibe'
      ? 'From a vibe'
      : item.discoveryContext?.type === 'search'
        ? 'From search'
        : playingNow
          ? 'Playing now'
          : 'Continue this thread';
  if (!leftover) return origin;
  return `${origin} • ${leftover} ${leftover === 1 ? 'song' : 'songs'} left`;
};

const greeting = () => {
  const hour = new Date().getHours();
  if (hour < 5) return 'Still awake';
  if (hour < 12) return 'Good morning';
  if (hour < 18) return 'Good afternoon';
  return 'Good evening';
};

const surfaceLabel: Record<CatalogSurface, string> = {
  explore: 'Explore catalog',
  charts: 'Open charts',
  'for-you': 'Open For you',
  moods: 'Browse moods',
};

export const ListeningHome = ({
  recentlyPlayed,
  favorites,
  history,
  currentTrack,
  isPlaying,
  playbackStatus,
  signals,
  onIntent,
  onPlay,
  homeModel,
  playlists = [],
  providerHome,
  onOpenCatalog,
}: ListeningHomeProps) => {
  const continueSection = homeModel ? sectionById(homeModel, 'continueListening') : undefined;
  const continueItem = continueSection?.visible ? continueSection.items.find((item) => item.track && presentTrack(item.track)) : undefined;
  const mixSection = homeModel ? sectionById(homeModel, 'moodifyMix') : undefined;
  const mixItem = mixSection?.visible ? mixSection.items[0] : undefined;
  const mixQueue = (mixItem?.queue || []).map((track) => presentTrack(track)).filter((track): track is Track => Boolean(track));
  const continueTrack = continueItem?.track ? presentTrack(continueItem.track) : null;
  const showMix = Boolean(mixSection?.visible && mixItem && mixQueue.length);
  const repeatArtist = Object.values(signals.artists).find((artist) => artist.artistPlayCount >= 2);
  const isFirstVisit = recentlyPlayed.length === 0 && favorites.length === 0 && history.length === 0 && playlists.length === 0;

  const playHome = (sectionId: 'continueListening' | 'moodifyMix', item: HomeItem) => {
    if (!homeModel) return;
    const request = playbackForHomeItem(homeModel, sectionId, item, { playlists });
    if (request) onPlay(request.track, request.tracks, request.discoveryContext);
  };

  const isContinueActive = Boolean(continueTrack && currentTrack?.id === continueTrack.id);
  const isContinuePlaying = isContinueActive && isPlaying;
  const isContinueLoading = isContinueActive && (playbackStatus === 'loading' || playbackStatus === 'idle');
  const isMixActive = Boolean(currentTrack && mixQueue.some((track) => track.id === currentTrack.id));
  const isMixPlaying = isMixActive && isPlaying;
  const isMixLoading = isMixActive && (playbackStatus === 'loading' || playbackStatus === 'idle');

  return (
    <div className="home-experience home-redesign">
      <section className="home-launchpad" aria-labelledby="home-heading">
        <div className="home-launchpad-main">
          <div className="home-status-line"><span>{greeting()}</span><span>{isFirstVisit ? 'A fresh start' : `${recentlyPlayed.length} recently played`}</span></div>
          <h1 id="home-heading">Find the sound for right now.</h1>
          <p className="home-lede">Start with a feeling, follow a live shelf, or let Moodify take you somewhere unexpected.</p>
          <IntentComposer onSubmit={onIntent} />
          <div className="home-quick-actions" aria-label="Catalog shortcuts">
            <button type="button" onClick={() => onOpenCatalog('search')}><Search size={17} aria-hidden="true" /><span><strong>Search music</strong><small>Find a track, artist or album</small></span></button>
            <button type="button" onClick={() => onOpenCatalog('moods')}><Heart size={17} aria-hidden="true" /><span><strong>Pick a mood</strong><small>Open the Moodify worlds</small></span></button>
            <button type="button" onClick={() => onOpenCatalog('charts')}><BarChart3 size={17} aria-hidden="true" /><span><strong>See what is moving</strong><small>Regional charts and trends</small></span></button>
          </div>
        </div>

        <aside className="home-launchpad-aside" aria-label="Listening status">
          {currentTrack ? (
            <button type="button" className="home-now-card" onClick={() => onPlay(currentTrack)}>
              <span className="home-card-label"><span className="home-live-dot" /> {isPlaying ? 'Playing now' : 'Ready when you are'}</span>
              <Artwork src={currentTrack.albumArt} alt="" fallback={<span className="home-art-placeholder"><Disc3 size={28} aria-hidden="true" /></span>} />
              <span className="home-now-copy"><strong>{currentTrack.title}</strong><small>{currentTrack.artist}</small></span>
              <span className="home-now-play" aria-hidden="true">{isPlaying ? <Pause size={17} fill="currentColor" /> : <Play size={17} fill="currentColor" />}</span>
            </button>
          ) : (
            <div className="home-welcome-card">
              <span className="home-card-label"><Sparkles size={14} aria-hidden="true" /> Moodify is ready</span>
              <strong>{isFirstVisit ? 'Your next favorite song starts here.' : 'Keep the thread going.'}</strong>
              <p>{isFirstVisit ? 'Use the shortcuts or describe the moment. We will turn it into a playable collection.' : 'Pick up a recent track or open For you for another live shelf.'}</p>
              <button type="button" onClick={() => onOpenCatalog(isFirstVisit ? 'moods' : 'for-you')}><span>{surfaceLabel[isFirstVisit ? 'moods' : 'for-you']}</span><ArrowRight size={15} aria-hidden="true" /></button>
            </div>
          )}

          <div className="home-library-status"><div><span className="home-card-label">Your library</span><strong>{favorites.length + playlists.length} saved items</strong></div><span className="home-library-mark" aria-hidden="true"><Compass size={18} /></span></div>
        </aside>
      </section>

      {providerHome && <ProviderHomeRail page={providerHome} onTrackSelect={onPlay} onOpenCatalog={() => onOpenCatalog('explore')} />}

      {recentlyPlayed.length > 0 && (
        <section className="home-recent-strip" aria-labelledby="home-recent-heading">
          <div className="home-section-heading"><div><span className="home-section-kicker">Your listening</span><h2 id="home-recent-heading">Pick up the thread</h2></div><button type="button" onClick={() => onOpenCatalog('for-you')}>View all <ArrowRight size={15} aria-hidden="true" /></button></div>
          <div className="home-recent-grid">
            {recentlyPlayed.slice(0, 4).map((track, index) => (
              <button key={`${track.id}-${index}`} type="button" className="home-recent-item" onClick={() => onPlay(track, recentlyPlayed, discoveryContextForLibrary('recently-played', index))}>
                <Artwork src={track.albumArt} alt="" fallback={<span className="home-art-placeholder"><Disc3 size={20} aria-hidden="true" /></span>} />
                <span><strong>{track.title}</strong><small>{track.artist}</small></span>
                <Play size={14} aria-hidden="true" />
              </button>
            ))}
          </div>
        </section>
      )}

      {(continueTrack && continueItem) || showMix ? (
        <section className="home-section listening-section" aria-labelledby="listening-heading">
          <div className="home-section-heading"><div><span className="home-section-kicker">Keep going</span><h2 id="listening-heading">Stay in the world</h2></div><span className="home-section-note">Pick up where you left off</span></div>
          <div className={`listening-pair${continueTrack && showMix ? '' : ' listening-pair-single'}`}>
            {continueTrack && continueItem && (
              <button className="continue-card listening-card" type="button" onClick={() => playHome('continueListening', continueItem)}>
                <span className="continue-art"><Artwork src={continueTrack.albumArt} alt="" fallback={<Disc3 size={34} aria-hidden="true" />} /></span>
                <span className="continue-copy"><strong>Continue</strong><span className="listening-track">{continueTrack.title}</span><span>{continueTrack.artist}</span><span className="listening-foot">{continueThreadNote(continueItem, isContinueActive && isPlaying)}</span></span>
                <span className="play-disc" aria-hidden="true">{isContinueLoading ? <Loader2 size={19} className="animate-spin" /> : isContinuePlaying ? <Pause size={19} fill="currentColor" /> : <Play size={19} fill="currentColor" />}</span>
              </button>
            )}
            {showMix && mixItem && (
              <button className="continue-card listening-card listening-card-mix" type="button" onClick={() => playHome('moodifyMix', mixItem)}>
                <span className="session-scene-wash" /><span className="continue-copy"><strong>Moodify Mix</strong><span className="listening-track">Keep listening with Moodify</span><span className="listening-foot">A personalized radio, just for you.</span></span>
                <span className="play-disc" aria-hidden="true">{isMixLoading ? <Loader2 size={19} className="animate-spin" /> : isMixPlaying ? <Pause size={19} fill="currentColor" /> : <Play size={19} fill="currentColor" />}</span>
              </button>
            )}
          </div>
        </section>
      ) : null}

      {isFirstVisit && (
        <section className="home-first-start" aria-labelledby="first-start-heading">
          <div><span className="home-section-kicker">Three ways in</span><h2 id="first-start-heading">Make the first listen yours.</h2><p>There is no setup ritual. Start wherever the moment takes you.</p></div>
          <div className="home-first-start-list"><button type="button" onClick={() => onOpenCatalog('moods')}><Heart size={16} aria-hidden="true" /><span>Choose a mood</span><ArrowRight size={15} aria-hidden="true" /></button><button type="button" onClick={() => onOpenCatalog('explore')}><Compass size={16} aria-hidden="true" /><span>Explore the catalog</span><ArrowRight size={15} aria-hidden="true" /></button></div>
        </section>
      )}

      {repeatArtist && (
        <section className="signal-note" aria-label="Listening signal"><span className="signal-mark">↻</span><span><strong>There is a thread here.</strong> You keep coming back to {repeatArtist.artist}.</span><button onClick={() => onIntent(`${repeatArtist.artist} songs`, discoveryContextForSearch(`${repeatArtist.artist} songs`, 0))}>Go deeper <ArrowRight size={15} aria-hidden="true" /></button></section>
      )}
    </div>
  );
};
