import type { Track } from '@/data/mockMusic';
import type { DiscoveryPlaybackRequest, DiscoveryPlaybackPath } from '@/listening/discoveryAttribution';
import {
  buildDiscoveryPlaybackRequest,
  discoveryContextForLibrary,
  discoveryContextForPlaylist,
  discoveryContextForRecommendation,
} from '@/listening/discoveryAttribution';
import type { DiscoveryContext } from '@/listening/listeningTypes';
import type { HomeItem, HomeSectionId, MoodifyHomeModel } from './homeTypes';
import { sectionById, tracksFromItems } from './homePresentation';

const libraryIdForSection = (sectionId: HomeSectionId, item: HomeItem) => {
  if (item.source === 'listening-history') return 'history';
  if (sectionId === 'yourRotation') return 'recently-played';
  if (item.source === 'recently-played') return 'recently-played';
  return 'history';
};

const pathForOrigin = (origin: DiscoveryContext): DiscoveryPlaybackPath => {
  if (origin.type === 'recommendation') return 'recommendation';
  if (origin.type === 'vibe') return 'vibe';
  if (origin.type === 'scene') return 'scene';
  if (origin.type === 'search') return 'search';
  if (origin.type === 'playlist') return 'playlist';
  if (origin.type === 'explore') return 'explore';
  if (origin.type === 'related') return 'related';
  if (origin.type === 'up-next') return 'up-next';
  if (origin.type === 'artist') return 'artist';
  if (origin.type === 'album') return 'album';
  if (origin.type === 'home') return 'home';
  return 'library';
};

export const playbackForHomeItem = (
  model: MoodifyHomeModel,
  sectionId: HomeSectionId,
  item: HomeItem,
  options: { playlists?: Array<{ id: string; tracks: Track[] }> } = {},
): DiscoveryPlaybackRequest | null => {
  if (item.kind === 'playlist' && item.playlistId) {
    const playlist = options.playlists?.find((entry) => entry.id === item.playlistId);
    const tracks = playlist?.tracks || [];
    const track = tracks[0];
    if (!track) return null;
    return buildDiscoveryPlaybackRequest('playlist', track, tracks, discoveryContextForPlaylist(item.playlistId, 0));
  }

  if (item.kind === 'mix' || sectionId === 'moodifyMix') {
    const mixTracks = (item.queue && item.queue.length ? item.queue : tracksFromItems(sectionById(model, 'forYou')?.items || []));
    const track = mixTracks[0];
    if (!track) return null;
    const mix = model.moodifyMix;
    return buildDiscoveryPlaybackRequest(
      'recommendation',
      track,
      mixTracks,
      item.discoveryContext || discoveryContextForRecommendation(
        mix?.id || 'moodify-mix',
        0,
        mix?.strategy || 'continue-listening',
        mix ? [mix.source] : ['recommendation-engine-v1'],
      ),
    );
  }

  const track = item.track;
  if (!track) return null;
  const sectionTracks = tracksFromItems(sectionById(model, sectionId)?.items || []);
  const tracks = item.queue?.length ? item.queue : (sectionTracks.length ? sectionTracks : [track]);
  const position = Math.max(0, tracks.findIndex((entry) => entry.id === track.id));

  if (sectionId === 'continueListening' && item.discoveryContext) {
    return buildDiscoveryPlaybackRequest(
      pathForOrigin(item.discoveryContext),
      track,
      tracks,
      { ...item.discoveryContext, position },
    );
  }

  if (sectionId === 'forYou' || sectionId === 'freshFinds') {
    const mix = model.moodifyMix;
    const sources = item.provenance?.map((entry) => entry.source).filter(Boolean) || item.explanationKeys || [];
    return buildDiscoveryPlaybackRequest(
      'recommendation',
      track,
      tracks,
      discoveryContextForRecommendation(
        mix?.id || 'home-for-you',
        position,
        mix?.strategy || 'continue-listening',
        sources.length ? sources : ['recommendation-engine-v1'],
      ),
    );
  }

  return buildDiscoveryPlaybackRequest(
    'library',
    track,
    tracks,
    discoveryContextForLibrary(libraryIdForSection(sectionId, item), position),
  );
};
