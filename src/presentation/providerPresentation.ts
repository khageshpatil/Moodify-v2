import type { Track } from '@/data/mockMusic';

const PLACEHOLDER_LABELS = new Set([
  'artist',
  'album',
  'playlist',
  'track',
  'song',
  'video',
  'context',
  'search',
  'explore',
  'home',
  'youtube music',
  'youtubemusic',
  'unknown title',
  'unknown artist',
  'unknown album',
  '[object object]',
]);

const YOUTUBE_VIDEO_ID = /^[A-Za-z0-9_-]{11}$/;
const PROVIDER_BROWSE_ID = /^(UC|MPREb_|MPRE|MPR|VL|PL|RD|OLAK5uy_|FEmusic_|UC[-_])/i;

export const isPlaceholderLabel = (value?: string | null) => {
  const normalized = (value || '').trim().toLowerCase();
  return !normalized || PLACEHOLDER_LABELS.has(normalized);
};

export const looksLikeInternalId = (value?: string | null, ids: Array<string | undefined> = []) => {
  const text = (value || '').trim();
  if (!text) return true;
  if (ids.some((id) => id && id === text)) return true;
  if (text.startsWith('youtube-music:')) return true;
  if (PROVIDER_BROWSE_ID.test(text)) return true;
  if (YOUTUBE_VIDEO_ID.test(text) && !text.includes(' ')) return true;
  return false;
};

export const presentableTitle = (value?: string | null, ids: Array<string | undefined> = []) => {
  const text = typeof value === 'string' ? value.trim() : '';
  if (!text || isPlaceholderLabel(text) || looksLikeInternalId(text, ids)) return undefined;
  return text;
};

export const presentableName = (value?: string | null, ids: Array<string | undefined> = []) =>
  presentableTitle(value, ids);

export const presentableAlbum = (value?: string | null, ids: Array<string | undefined> = []) =>
  presentableTitle(value, ids);

const collectArtworkCandidates = (value: unknown, into: Array<{ url: string; width: number }>, depth = 0) => {
  if (value == null || depth > 6) return;
  if (typeof value === 'string') {
    into.push({ url: value, width: 0 });
    return;
  }
  if (Array.isArray(value)) {
    value.forEach((entry) => collectArtworkCandidates(entry, into, depth + 1));
    return;
  }
  if (typeof value !== 'object') return;
  const record = value as Record<string, unknown>;
  if (typeof record.url === 'string') into.push({ url: record.url, width: Number(record.width) || 0 });
  else collectArtworkCandidates(record.url, into, depth + 1);
  collectArtworkCandidates(record.thumbnail, into, depth + 1);
  collectArtworkCandidates(record.thumbnails, into, depth + 1);
  collectArtworkCandidates(record.contents, into, depth + 1);
  collectArtworkCandidates(record.sources, into, depth + 1);
  collectArtworkCandidates(record.artwork, into, depth + 1);
  collectArtworkCandidates(record.header, into, depth + 1);
  collectArtworkCandidates(record.image, into, depth + 1);
};

export const presentArtworkUrl = (...sources: unknown[]) => {
  const found: Array<{ url: string; width: number }> = [];
  sources.forEach((source) => collectArtworkCandidates(source, found));
  const urls = found
    .map((entry) => {
      let url = entry.url.trim();
      if (url.startsWith('//')) url = `https:${url}`;
      if (!/^https?:\/\//i.test(url)) return null;
      url = url.replace(/=w(\d+)-h(\d+)/, (match, width) => (
        Number(width) > 0 && Number(width) < 120 ? '=w226-h226' : match
      ));
      return { url, width: entry.width };
    })
    .filter((entry): entry is { url: string; width: number } => Boolean(entry));
  if (!urls.length) return undefined;
  const preferred = urls.filter((entry) => entry.width >= 120 && entry.width <= 640);
  const pool = preferred.length ? preferred : urls;
  return [...pool].sort((a, b) => b.width - a.width)[0].url;
};

export const isPresentableTrack = (track: Pick<Track, 'id' | 'title' | 'providerId'>) =>
  Boolean(presentableTitle(track.title, [track.id, track.providerId]));

export const presentTrack = <T extends Track>(track: T): T | null => {
  const title = presentableTitle(track.title, [track.id, track.providerId]);
  if (!title) return null;
  return {
    ...track,
    title,
    artist: presentableName(track.artist, [track.id, track.providerId, track.artistId]) || '',
    album: presentableAlbum(track.album, [track.albumId]) || '',
    albumArt: presentArtworkUrl(track.albumArt) || '',
  };
};

export const presentEntityTitle = (title: string | undefined, id: string) =>
  presentableTitle(title, [id]);
