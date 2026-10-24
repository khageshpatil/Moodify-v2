const asList = (value) => {
  if (!value) return [];
  if (Array.isArray(value)) return value;
  if (typeof value.toArray === 'function') return value.toArray();
  if (typeof value.length === 'number' || typeof value[Symbol.iterator] === 'function') {
    try { return Array.from(value); } catch { return []; }
  }
  return [];
};

const toText = (value, depth = 0) => {
  if (value == null || depth > 4) return '';
  if (typeof value === 'string' || typeof value === 'number') return String(value).trim();
  if (Array.isArray(value)) return value.map((entry) => toText(entry, depth + 1)).filter(Boolean).join(' ').trim();
  if (typeof value !== 'object') return '';
  if (typeof value.text === 'string') return value.text.trim();
  if (typeof value.simpleText === 'string') return value.simpleText.trim();
  if (Array.isArray(value.runs)) return toText(value.runs, depth + 1);
  if (typeof value.toString === 'function') {
    const result = value.toString();
    if (result && result !== '[object Object]') return result.trim();
  }
  return '';
};

const endpointPayload = (value) => value?.payload || value?.endpoint?.payload || value?.navigationEndpoint?.payload || value?.on_tap?.payload || value?.onTap?.payload || value;

export const toBrowseEndpoint = (...values) => {
  for (const value of values) {
    if (!value) continue;
    const payload = endpointPayload(value);
    const browseId = payload?.browseId || payload?.browse_id || value?.browseId || value?.browse_id;
    if (!browseId) continue;
    const params = payload?.params || value?.params;
    return { browseId, ...(params ? { params } : {}) };
  }
  return undefined;
};

const toEndpoint = (...values) => {
  for (const value of values) {
    const endpoint = toBrowseEndpoint(value);
    if (endpoint) return endpoint;
    const payload = endpointPayload(value);
    const videoId = payload?.videoId || payload?.video_id;
    if (videoId) return { videoId };
  }
  return undefined;
};

const itemId = (item) => item?.id || item?.providerId || item?.video_id || item?.videoId || item?.endpoint?.payload?.browseId || item?.endpoint?.payload?.videoId || '';

const itemKind = (item) => {
  if (item?.item_type === 'artist' || item?.item_type === 'library_artist') return 'artist';
  if (item?.item_type === 'album') return 'album';
  if (item?.item_type === 'playlist' || item?.item_type === 'community_playlist') return 'playlist';
  if (item?.item_type === 'song') return 'track';
  if (item?.item_type === 'video') return 'video';
  if (item?.item_type === 'podcast_show') return 'podcast';
  if (item?.item_type === 'browse' || item?.button_text) return 'browse';
  if (item?.duration?.seconds || item?.video_id || item?.videoId) return 'track';
  return undefined;
};

const itemTitle = (item) => toText(item?.title || item?.name || item?.button_text || item?.label || item?.header?.title);

const findArtwork = (value, depth = 0) => {
  if (!value || depth > 5) return undefined;
  if (typeof value === 'string' && /^https?:\/\//i.test(value)) return value;
  if (Array.isArray(value)) return value.map((entry) => findArtwork(entry, depth + 1)).find(Boolean);
  if (typeof value !== 'object') return undefined;
  if (typeof value.url === 'string' && /^https?:\/\//i.test(value.url)) return value.url;
  for (const key of ['artwork', 'thumbnail', 'thumbnails', 'contents', 'sources', 'image', 'header']) {
    const found = findArtwork(value[key], depth + 1);
    if (found) return found;
  }
  return undefined;
};

const itemArtwork = (item) => findArtwork(item?.artworkUrl || item?.artwork || item?.thumbnail || item?.thumbnails);

const metadataFor = (item) => Object.fromEntries([
  ['year', item?.year],
  ['itemCount', item?.item_count],
  ['subscribers', item?.subscribers],
  ['displayStyle', item?.display_style],
].filter(([, value]) => value != null && value !== ''));

export const makeCatalogItem = (item, normalizeTrack) => {
  const kind = itemKind(item);
  const title = itemTitle(item);
  const id = itemId(item);
  const endpoint = toEndpoint(item?.endpoint, item?.on_tap, item?.onTap);
  if (!title && !id) return null;
  if (kind === 'track' && normalizeTrack) {
    const track = normalizeTrack(item);
    return track ? { type: 'track', id: track.id, title: track.title, track } : null;
  }
  if (kind === 'video' && normalizeTrack) {
    const track = normalizeTrack(item);
    return track ? { type: 'video', id: track.id, title: track.title, track } : null;
  }
  if (kind && ['artist', 'album', 'playlist', 'podcast'].includes(kind)) {
    return {
      type: kind,
      id,
      title: title || id,
      ...(itemArtwork(item) ? { artworkUrl: itemArtwork(item) } : {}),
      ...(endpoint ? { endpoint } : {}),
      metadata: metadataFor(item),
    };
  }
  if (endpoint) return { type: 'browse', id: id || endpoint.browseId || endpoint.videoId, title: title || id, endpoint };
  return null;
};

const sectionContents = (section) => {
  if (Array.isArray(section)) return section;
  if (section?.contents) return asList(section.contents);
  if (section?.items) return asList(section.items);
  return [];
};

const sectionTitle = (section, index) => toText(section?.title || section?.header?.title || section?.header?.title_text) || `Section ${index + 1}`;

const layoutFor = (section, items) => {
  const type = String(section?.type || '').toLowerCase();
  if (type.includes('playlist') || type.includes('responsive') || type.includes('music shelf')) return 'list';
  if (type.includes('taste') || type.includes('grid')) return 'grid';
  if (items.some((item) => item.type === 'track' || item.type === 'video')) return 'carousel';
  return 'grid';
};

export const makeCatalogSection = (section, index, normalizeTrack) => {
  const items = sectionContents(section)
    .map((item) => makeCatalogItem(item, normalizeTrack))
    .filter(Boolean);
  if (!items.length && !sectionTitle(section, index)) return null;
  const endpoint = toEndpoint(section?.endpoint, section?.header?.more_content?.endpoint, section?.header?.moreContentButton?.endpoint);
  return {
    id: `${section?.type || 'section'}:${index}`,
    title: sectionTitle(section, index),
    layout: layoutFor(section, items),
    items,
    ...(endpoint ? { endpoint } : {}),
    ...(section?.continuation ? { continuation: section.continuation } : {}),
  };
};

export const makeCatalogPage = ({ surface, title, sections = [], actions = [], primaryItems = [], normalizeTrack }) => {
  const normalizedSections = [];
  if (primaryItems.length) {
    const primary = makeCatalogSection({ type: 'MusicShelf', title: surface === 'album' ? 'Songs' : 'Tracks', contents: primaryItems }, 0, normalizeTrack);
    if (primary) normalizedSections.push(primary);
  }
  asList(sections).forEach((section, index) => {
    const normalized = makeCatalogSection(section, index + normalizedSections.length, normalizeTrack);
    if (normalized && normalized.items.length) normalizedSections.push(normalized);
  });
  const normalizedActions = asList(actions).map((action, index) => {
    const endpoint = toEndpoint(action?.endpoint, action?.on_tap, action?.onTap);
    const label = toText(action?.button_text || action?.title || action?.label);
    return endpoint && label ? { id: `${surface}-action:${index}`, type: 'browse', title: label, endpoint } : null;
  }).filter(Boolean);
  return {
    version: 1,
    surface,
    title: title || surface,
    sections: normalizedSections,
    ...(normalizedActions.length ? { actions: normalizedActions } : {}),
  };
};
