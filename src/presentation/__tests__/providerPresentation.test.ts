import { describe, expect, it } from 'vitest';
import {
  presentArtworkUrl,
  presentEntityTitle,
  presentTrack,
  presentableAlbum,
  presentableTitle,
} from '../providerPresentation';

describe('provider presentation', () => {
  it('never uses video ids, slugs, or provider type names as titles', () => {
    expect(presentableTitle('YW6deNQMoe8', ['YW6deNQMoe8'])).toBeUndefined();
    expect(presentableTitle('love-1', ['love-1'])).toBeUndefined();
    expect(presentableTitle('artist')).toBeUndefined();
    expect(presentableTitle('YouTube Music')).toBeUndefined();
    expect(presentableTitle('UCabc123')).toBeUndefined();
    expect(presentableTitle('Late At Night', ['Q5lohxkoBLE'])).toBe('Late At Night');
  });

  it('omits placeholder albums instead of fabricating names', () => {
    expect(presentableAlbum('YouTube Music')).toBeUndefined();
    expect(presentableAlbum('Album A')).toBe('Album A');
  });

  it('hides tracks whose only title is an internal id', () => {
    expect(presentTrack({
      id: 'YW6deNQMoe8',
      title: 'YW6deNQMoe8',
      artist: 'Ustad Sultan Khan',
      album: 'YouTube Music',
      albumArt: '',
      duration: 0,
      providerId: 'YW6deNQMoe8',
    })).toBeNull();
  });

  it('keeps canonical names and drops placeholder album copy', () => {
    const presented = presentTrack({
      id: 'Q5lohxkoBLE',
      title: 'late at night',
      artist: 'Roddy Ricch',
      album: 'YouTube Music',
      albumArt: 'https://lh3.googleusercontent.com/art=w60-h60-l90-rj',
      duration: 180,
      providerId: 'Q5lohxkoBLE',
    });
    expect(presented?.title).toBe('late at night');
    expect(presented?.artist).toBe('Roddy Ricch');
    expect(presented?.album).toBe('');
    expect(presented?.albumArt).toContain('w226-h226');
  });

  it('picks a stable http artwork url and ignores nested objects', () => {
    expect(presentArtworkUrl({ url: { url: 'https://example.test/nested.jpg' } })).toBe('https://example.test/nested.jpg');
    expect(presentArtworkUrl({ thumbnail: { url: 'https://example.test/thumb.jpg', width: 320 } })).toBe('https://example.test/thumb.jpg');
    expect(presentArtworkUrl({
      thumbnails: [
        { url: 'https://lh3.googleusercontent.com/x=w60-h60-l90-rj', width: 60 },
        { url: 'https://lh3.googleusercontent.com/x=w544-h544-l90-rj', width: 544 },
      ],
    })).toBe('https://lh3.googleusercontent.com/x=w544-h544-l90-rj');
  });

  it('does not use an entity id as its visible name', () => {
    expect(presentEntityTitle('UCa', 'UCa')).toBeUndefined();
    expect(presentEntityTitle('Roddy Ricch', 'UCa')).toBe('Roddy Ricch');
  });

  it('rejects suggestion strings that are provider ids', () => {
    expect(presentableTitle('Q5lohxkoBLE')).toBeUndefined();
    expect(presentableTitle('late night rain')).toBe('late night rain');
  });
});
