import { useEffect, useState } from 'react';
import type { Track } from '@/data/mockMusic';
import { presentTrack } from '@/presentation/providerPresentation';
import { fetchDiscoveryRelated, fetchDiscoveryUpNext } from '@/services/moodifyMusicApi';

const presentSidecarTracks = (tracks: Track[], currentId?: string) => {
  const seen = new Set<string>();
  return tracks
    .map((track) => presentTrack(track))
    .filter((track): track is Track => Boolean(track) && track.id !== currentId)
    .filter((track) => {
      if (seen.has(track.id)) return false;
      seen.add(track.id);
      return true;
    });
};

export const useDiscoverySidecar = (track: Track | null) => {
  const [related, setRelated] = useState<Track[]>([]);
  const [upNext, setUpNext] = useState<Track[]>([]);

  useEffect(() => {
    const id = track?.providerId || track?.id;
    if (!id) {
      setRelated([]);
      setUpNext([]);
      return;
    }
    const controller = new AbortController();
    Promise.all([
      fetchDiscoveryRelated(id, undefined, controller.signal).catch(() => ({ tracks: [] as Track[] })),
      fetchDiscoveryUpNext(id, undefined, controller.signal).catch(() => ({ tracks: [] as Track[] })),
    ]).then(([relatedResult, upNextResult]) => {
      if (controller.signal.aborted) return;
      setRelated(presentSidecarTracks(relatedResult.tracks, track?.id));
      setUpNext(presentSidecarTracks(upNextResult.tracks, track?.id));
    });
    return () => controller.abort();
  }, [track?.id, track?.providerId]);

  return { related, upNext };
};
