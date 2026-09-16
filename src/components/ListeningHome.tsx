import { ArrowRight, Disc3, Loader2, Pause, Play } from 'lucide-react';
import type { Track } from '@/data/mockMusic';
import type { HistoryItem } from '@/hooks/useMusicPlayer';
import { IntentComposer } from './IntentComposer';
import type { ListeningSignals } from '@/listening/listeningTypes';
import type { DiscoveryContext } from '@/listening/listeningTypes';
import { discoveryContextForSearch } from '@/listening/discoveryAttribution';
import type { HomeItem, MoodifyHomeModel } from '@/home/homeTypes';
import { playbackForHomeItem } from '@/home/homePlayback';
import { sectionById } from '@/home/homePresentation';
import { presentTrack } from '@/presentation/providerPresentation';
import { Artwork } from './Artwork';
import { VibesCarousel } from './VibesCarousel';
import homeAtmosphere from '@/assets/home-atmosphere.jpg';

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

export const ListeningHome = ({
  currentTrack,
  isPlaying,
  playbackStatus,
  signals,
  onIntent,
  onVibeSelect,
  onPlay,
  homeModel,
  playlists = [],
}: ListeningHomeProps) => {
  const continueSection = homeModel ? sectionById(homeModel, 'continueListening') : undefined;
  const continueItem = continueSection?.visible ? continueSection.items.find((item) => item.track && presentTrack(item.track)) : undefined;
  const mixSection = homeModel ? sectionById(homeModel, 'moodifyMix') : undefined;
  const mixItem = mixSection?.visible ? mixSection.items[0] : undefined;
  const mixQueue = (mixItem?.queue || []).map((track) => presentTrack(track)).filter((track): track is Track => Boolean(track));
  const continueTrack = continueItem?.track ? presentTrack(continueItem.track) : null;
  const showMix = Boolean(mixSection?.visible && mixItem && mixQueue.length);
  const repeatArtist = Object.values(signals.artists).find((artist) => artist.artistPlayCount >= 2);
  const playHome = (sectionId: 'continueListening' | 'moodifyMix', item: HomeItem) => {
    if (!homeModel) return;
    const request = playbackForHomeItem(homeModel, sectionId, item, { playlists });
    if (request) onPlay(request.track, request.tracks, request.discoveryContext);
  };

  const isContinueActive = Boolean(continueTrack && currentTrack?.id === continueTrack.id);
  const isContinuePlaying = isContinueActive && isPlaying;
  const isContinueLoading = isContinueActive && (playbackStatus === 'loading' || playbackStatus === 'idle');

  const isMixActive = Boolean(currentTrack && mixQueue.some((t) => t.id === currentTrack.id));
  const isMixPlaying = isMixActive && isPlaying;
  const isMixLoading = isMixActive && (playbackStatus === 'loading' || playbackStatus === 'idle');

  return (
    <div className="home-experience">
      <section className="night-signal-hero" aria-labelledby="moodify-hero-heading" style={{ backgroundImage: `linear-gradient(90deg, rgba(8, 11, 17, .95) 0%, rgba(8, 11, 17, .67) 54%, rgba(8, 11, 17, .18) 100%), url(${homeAtmosphere})` }}>
        <div className="hero-copy">
          <span className="hero-eyebrow"><span className="signal-pulse" aria-hidden="true" /> MOODIFY / NIGHT SIGNAL</span>
          <h1 id="moodify-hero-heading">Your soundtrack<br /><em>has a world.</em></h1>
          <p>Music for the feeling you cannot quite name. Enter a scene, and let the next song find you.</p>
        </div>
        <div className="hero-meta"><span>{greeting()}</span><span className="home-date">{new Intl.DateTimeFormat(undefined, { weekday: 'long', month: 'short', day: 'numeric' }).format(new Date())}</span></div>
      </section>
      <IntentComposer onSubmit={onIntent} />

      <section className="home-section session-section world-section" aria-labelledby="vibe-heading">
        <div className="section-heading"><div><span className="section-index">01</span><h2 id="vibe-heading">Choose a world</h2></div><span className="section-note">enter a feeling, not a genre</span></div>
        <VibesCarousel onVibeSelect={onVibeSelect} />
      </section>

      {(continueTrack && continueItem) || showMix ? (
        <section className="home-section listening-section" aria-labelledby="listening-heading">
          <div className="section-heading"><div><span className="section-index">02</span><h2 id="listening-heading">Stay in the world</h2></div><span className="section-note">pick up where you left off</span></div>
          <div className={`listening-pair${continueTrack && showMix ? '' : ' listening-pair-single'}`}>
            {continueTrack && continueItem && (
              <button className="continue-card listening-card" type="button" onClick={() => playHome('continueListening', continueItem)}>
                <span className="continue-art"><Artwork src={continueTrack.albumArt} alt="" fallback={<Disc3 size={34} aria-hidden="true" />} /></span>
                <span className="continue-copy">
                  <strong>Continue</strong>
                  <span className="listening-track">{continueTrack.title}</span>
                  <span>{continueTrack.artist}</span>
                  <span className="listening-foot">{continueThreadNote(continueItem, isContinueActive && isPlaying)}</span>
                </span>
                <span className="play-disc" aria-hidden="true">
                  {isContinueLoading ? (
                    <Loader2 size={19} className="animate-spin" />
                  ) : isContinuePlaying ? (
                    <Pause size={19} fill="currentColor" />
                  ) : (
                    <Play size={19} fill="currentColor" />
                  )}
                </span>
              </button>
            )}
            {showMix && mixItem && (
              <button className="continue-card listening-card listening-card-mix" type="button" onClick={() => playHome('moodifyMix', mixItem)}>
                <span className="session-scene-wash" />
                <span className="continue-copy">
                  <strong>Moodify Mix</strong>
                  <span className="listening-track">Keep listening with Moodify</span>
                  <span className="listening-foot">A personalized radio, just for you.</span>
                </span>
                <span className="play-disc" aria-hidden="true">
                  {isMixLoading ? (
                    <Loader2 size={19} className="animate-spin" />
                  ) : isMixPlaying ? (
                    <Pause size={19} fill="currentColor" />
                  ) : (
                    <Play size={19} fill="currentColor" />
                  )}
                </span>
              </button>
            )}
          </div>
        </section>
      ) : null}

      {repeatArtist && (
        <section className="signal-note" aria-label="Listening signal">
          <span className="signal-mark">↻</span><span><strong>There is a thread here.</strong> You keep coming back to {repeatArtist.artist}.</span><button onClick={() => onIntent(`${repeatArtist.artist} songs`, discoveryContextForSearch(`${repeatArtist.artist} songs`, 0))}>Go deeper <ArrowRight size={15} aria-hidden="true" /></button>
        </section>
      )}
    </div>
  );
};
