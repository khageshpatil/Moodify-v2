import { ArrowRight, Disc3, Play, Sparkles } from 'lucide-react';
import type { Track } from '@/data/mockMusic';
import type { DiscoveryContext } from '@/listening/listeningTypes';
import { toDiscoveryPlaybackRequest, type DiscoveryCollectionResult, type DiscoveryCatalogItem } from '@/services/moodifyMusicApi';
import { Artwork } from './Artwork';
import { presentTrack } from '@/presentation/providerPresentation';

interface ProviderHomeRailProps {
  page: DiscoveryCollectionResult;
  onTrackSelect: (track: Track, tracks: Track[], discoveryContext?: DiscoveryContext) => void;
  onOpenCatalog: () => void;
}

const trackFromItem = (item: DiscoveryCatalogItem) => item.track ? presentTrack(item.track) : null;

const playTracks = (
  tracks: Track[],
  index: number,
  onTrackSelect: ProviderHomeRailProps['onTrackSelect'],
) => {
  const request = toDiscoveryPlaybackRequest('home', tracks, { id: 'FEmusic_home', position: index });
  if (request) onTrackSelect(request.track, request.tracks, request.discoveryContext);
};

export const ProviderHomeRail = ({ page, onTrackSelect, onOpenCatalog }: ProviderHomeRailProps) => {
  const sections = page.catalog?.sections.filter((section) => section.items.length).slice(0, 4) || [];
  const fallbackTracks = page.tracks.map(presentTrack).filter((track): track is Track => Boolean(track)).slice(0, 8);

  if (!sections.length && !fallbackTracks.length) return null;

  return (
    <section className="provider-home" aria-labelledby="provider-home-heading">
      <div className="provider-home-heading">
        <div>
          <span className="catalog-eyebrow"><Sparkles size={13} aria-hidden="true" /> Live catalog</span>
          <h2 id="provider-home-heading">For the way you feel today</h2>
          <p>Fresh shelves from YouTube Music, shaped around your world.</p>
        </div>
        <button type="button" className="provider-home-link" onClick={onOpenCatalog}>
          Open catalog <ArrowRight size={15} aria-hidden="true" />
        </button>
      </div>

      <div className="provider-home-sections">
        {(sections.length ? sections : [{ id: 'fallback-home', title: 'Recommended songs', layout: 'carousel' as const, items: fallbackTracks.map((track) => ({ type: 'track' as const, id: track.id, title: track.title, track })) }]).map((section) => {
          const sectionTracks = section.items.map(trackFromItem).filter((track): track is Track => Boolean(track));
          return (
            <div className="provider-home-section" key={section.id}>
              <div className="provider-home-section-heading">
                <h3>{section.title}</h3>
                <span>{section.items.length} picks</span>
              </div>
              <div className="provider-home-rail">
                {section.items.slice(0, 8).map((item) => {
                  const track = trackFromItem(item);
                  if (!track) return (
                    <button type="button" className="provider-home-entity" key={`${item.type}:${item.id}`} onClick={onOpenCatalog} aria-label={`Open ${item.title} in the catalog`}>
                      <Artwork src={item.artworkUrl} alt="" fallback={<Disc3 size={23} aria-hidden="true" />} />
                      <strong>{item.title}</strong>
                      <small>{item.type}</small>
                    </button>
                  );
                  const index = Math.max(0, sectionTracks.findIndex((entry) => entry.id === track.id));
                  return (
                    <article className="provider-home-card" key={`${item.type}:${item.id}`}>
                      <button type="button" className="provider-home-card-main" onClick={() => playTracks(sectionTracks, index, onTrackSelect)}>
                        <Artwork src={track.albumArt} alt="" fallback={<span className="provider-home-art-placeholder"><Disc3 size={23} aria-hidden="true" /></span>} />
                        <strong>{track.title}</strong>
                        <span>{track.artist || 'Unknown artist'}</span>
                      </button>
                      <button type="button" className="provider-home-card-play" aria-label={`Play ${track.title}`} onClick={() => playTracks(sectionTracks, index, onTrackSelect)}>
                        <Play size={15} fill="currentColor" aria-hidden="true" />
                      </button>
                    </article>
                  );
                })}
              </div>
            </div>
          );
        })}
      </div>
    </section>
  );
};
