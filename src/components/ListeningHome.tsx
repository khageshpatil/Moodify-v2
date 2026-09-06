import { ArrowRight, Disc3, Play } from 'lucide-react';
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

interface ListeningHomeProps {
  recentlyPlayed: Track[];
  favorites: Track[];
  history: HistoryItem[];
  currentTrack: Track | null;
  isPlaying: boolean;
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

  return (
    <div className="home-experience">
      <div className="home-topline"><span>{greeting()}</span><span className="home-date">{new Intl.DateTimeFormat(undefined, { weekday: 'long', month: 'short', day: 'numeric' }).format(new Date())}</span></div>
      <IntentComposer onSubmit={onIntent} />

      <section className="home-section session-section" aria-labelledby="vibe-heading">
        <div className="section-heading"><div><span className="section-index">01</span><h2 id="vibe-heading">Vibes</h2></div><span className="section-note">destinations you can choose</span></div>
        <VibesCarousel onVibeSelect={onVibeSelect} />
      </section>

      {(continueTrack && continueItem) || showMix ? (
        <section className="home-section listening-section" aria-labelledby="listening-heading">
          <div className="section-heading"><div><span className="section-index">02</span><h2 id="listening-heading">Your listening</h2></div><span className="section-note">pick up where you left off</span></div>
          <div className={`listening-pair${continueTrack && showMix ? '' : ' listening-pair-single'}`}>
            {continueTrack && continueItem && (
              <button className="continue-card listening-card" type="button" onClick={() => playHome('continueListening', continueItem)}>
                <span className="continue-art"><Artwork src={continueTrack.albumArt} alt="" fallback={<Disc3 size={34} aria-hidden="true" />} /></span>
                <span className="continue-copy">
                  <strong>Continue</strong>
                  <span className="listening-track">{continueTrack.title}</span>
                  <span>{continueTrack.artist}</span>
                  <span className="listening-foot">{continueThreadNote(continueItem, currentTrack?.id === continueTrack.id && isPlaying)}</span>
                </span>
                <span className="play-disc" aria-hidden="true"><Play size={19} fill="currentColor" /></span>
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
                <span className="play-disc" aria-hidden="true"><Play size={19} fill="currentColor" /></span>
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
