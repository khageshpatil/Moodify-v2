import { describe, expect, it } from 'vitest';
import {
  addMessage, applyReaction, conversations, KEEP_PER_THREAD, markRead, parsePayload, payloads, preview, toMessage, validEmoji,
  type ChatMessage, type ChatThreads,
} from '../social/chatModel';

const MID = '3f2a1c9e-6b7d-4e1a-9c2b-1a2b3c4d5e6f';
const RAVI = 'MOOD-6217-YAAB';
const msg = (mid: string, sentAt: number, over: Partial<ChatMessage> = {}): ChatMessage =>
  ({ mid, peer: RAVI, fromMe: false, kind: 'text', text: mid, sentAt, status: 'received', reactions: {}, read: false, ...over });

describe('chat payloads (chat.md §1)', () => {
  it('reads what Android sends', () => {
    // Exactly what SharePayload.text / reaction / track / room / collection write.
    expect(parsePayload(`{"v":1,"type":"text","mid":"${MID}","text":"hey! 🎶"}`, 'srv-1')).toEqual({ type: 'text', mid: MID, text: 'hey! 🎶' });
    expect(parsePayload(`{"v":1,"type":"reaction","mid":"${MID}","target":"abcdefgh","emoji":"🔥"}`, 'srv-1')).toEqual({ type: 'reaction', mid: MID, target: 'abcdefgh', emoji: '🔥' });
    const track = parsePayload(`{"v":1,"type":"track","mid":"${MID}","track":{"id":"VAdGW7QDJiU","title":"Chaleya","artist":"Arijit Singh","artworkUrl":"https://i.ytimg.com/vi/VAdGW7QDJiU/hqdefault.jpg","durationSeconds":188}}`, 'x');
    expect(track).toMatchObject({ type: 'track', mid: MID, track: { id: 'VAdGW7QDJiU', title: 'Chaleya', duration: 188 } });
    expect(parsePayload(`{"v":1,"type":"room","mid":"${MID}","room":{"id":"k7m2qp"}}`, 'x')).toMatchObject({ type: 'room', roomId: 'K7M2QP' });
    expect(parsePayload(`{"v":1,"type":"collection","mid":"${MID}","collection":{"kind":"album","browseId":"MPREb_abc","title":"Aashiqui 2","artworkUrl":"http://no"}}`, 'x'))
      .toMatchObject({ type: 'collection', collection: { kind: 'album', browseId: 'MPREb_abc', title: 'Aashiqui 2', artworkUrl: undefined } });
  });

  it('gives old messages a server id and skips what it cannot show', () => {
    expect(parsePayload('{"v":1,"type":"text","text":"old"}', 'srv-42')).toMatchObject({ mid: 'srv-42' });
    expect(parsePayload('{"v":2,"type":"text","mid":"abcdefgh","text":"future"}', 'x')).toBeNull();
    expect(parsePayload('{"v":1,"type":"poll","mid":"abcdefgh"}', 'x')).toBeNull();
    expect(parsePayload('{"v":1,"type":"text","mid":"abcdefgh","text":"   "}', 'x')).toBeNull();
    expect(parsePayload('not json', 'x')).toBeNull();
    expect(parsePayload('{"v":1,"type":"track","mid":"abcdefgh","track":{"id":"bad"}}', 'x')).toBeNull();
    expect(parsePayload('{"v":1,"type":"collection","mid":"abcdefgh","collection":{"kind":"evil","browseId":"VL123"}}', 'x')).toMatchObject({ collection: { kind: 'playlist' } });
  });

  it('checks reactions like Android: short, no ASCII letters or digits, "" removes', () => {
    expect(validEmoji('❤️')).toBe(true);
    expect(validEmoji('')).toBe(true);
    expect(validEmoji('lol')).toBe(false);
    expect(validEmoji('🔥'.repeat(9))).toBe(false);
    expect(parsePayload('{"v":1,"type":"reaction","mid":"abcdefgh","target":"bad id!","emoji":"🔥"}', 'x')).toBeNull();
  });

  it('round-trips what the web sends, capped at 2,000 characters', () => {
    const sent = parsePayload(payloads.text('x'.repeat(2500), MID), 'x');
    expect(sent).toEqual({ type: 'text', mid: MID, text: 'x'.repeat(2000) });
    const track = { id: 'VAdGW7QDJiU', title: 'Chaleya', artist: 'Arijit Singh', album: '', albumArt: 'https://a/b.jpg', duration: 188, provider: 'youtube-music' as const, providerId: 'VAdGW7QDJiU' };
    expect(parsePayload(payloads.track(track, MID), 'x')).toMatchObject({ type: 'track', track: { id: 'VAdGW7QDJiU', albumArt: 'https://a/b.jpg' } });
    expect(parsePayload(payloads.room('K7M2QP', track, MID), 'x')).toMatchObject({ type: 'room', roomId: 'K7M2QP', track: { title: 'Chaleya' } });
    expect(parsePayload(payloads.reaction('abcdefgh', '', MID), 'x')).toEqual({ type: 'reaction', mid: MID, target: 'abcdefgh', emoji: '' });
  });
});

describe('chat threads', () => {
  it('shows a message once, in time order, keeping the newest', () => {
    let t: ChatThreads = {};
    t = addMessage(t, msg('m-000002', 20));
    t = addMessage(t, msg('m-000001', 10));
    t = addMessage(t, msg('m-000003', 20)); // same second: after the one already there
    expect(addMessage(t, msg('m-000001', 10))).toBe(t); // a retry or own copy
    expect(t[RAVI].map((m) => m.mid)).toEqual(['m-000001', 'm-000002', 'm-000003']);
    for (let i = 0; i < KEEP_PER_THREAD + 5; i++) t = addMessage(t, msg(`n-${String(i).padStart(6, '0')}`, 100 + i));
    expect(t[RAVI]).toHaveLength(KEEP_PER_THREAD);
    expect(t[RAVI][0].mid).toBe('n-000005');
  });

  it('keeps one reaction per person and removes it with ""', () => {
    let t = addMessage({}, msg('m-000001', 10));
    t = applyReaction(t, RAVI, 'm-000001', RAVI, '🔥');
    t = applyReaction(t, RAVI, 'm-000001', 'MOOD-W87S-416C', '❤️');
    t = applyReaction(t, RAVI, 'm-000001', RAVI, '😂');
    expect(t[RAVI][0].reactions).toEqual({ [RAVI]: '😂', 'MOOD-W87S-416C': '❤️' });
    t = applyReaction(t, RAVI, 'm-000001', RAVI, '');
    expect(t[RAVI][0].reactions).toEqual({ 'MOOD-W87S-416C': '❤️' });
    expect(applyReaction(t, RAVI, 'unknown-1', RAVI, '🔥')).toBe(t);
  });

  it('lists conversations newest first with unread counts', () => {
    let t: ChatThreads = {};
    t = addMessage(t, msg('m-000001', 10));
    t = addMessage(t, msg('m-000002', 12));
    t = addMessage(t, msg('m-000003', 30, { peer: 'MOOD-AAAA-BBBB', fromMe: true, read: true, status: 'sent' }));
    expect(conversations(t).map((c) => [c.peer, c.unread])).toEqual([['MOOD-AAAA-BBBB', 0], [RAVI, 2]]);
    t = markRead(t, RAVI);
    expect(conversations(t).find((c) => c.peer === RAVI)?.unread).toBe(0);
    expect(markRead(t, RAVI)).toBe(t);
  });

  it('previews each kind in one line', () => {
    const p = parsePayload(payloads.room('K7M2QP', null, MID), 'x');
    expect(p && p.type !== 'reaction' && preview(toMessage(p, RAVI, false, 1, 'received'))).toBe('🎧 Listen together');
    expect(preview({ kind: 'text', text: 'hey' })).toBe('hey');
  });
});

describe('shared playlists', () => {
  const song = (id: string) => ({ id, title: id, artist: 'A', album: '', albumArt: 'https://a/b.jpg', duration: 200, provider: 'youtube-music' as const, providerId: id });
  it('round-trips, caps at 300 songs, and drops unplayable ones', () => {
    const tracks = ['JGwWNGJdvx8', 'kJQP7kiw5Fk'].map(song);
    const p = parsePayload(payloads.playlist('Road trip', tracks, MID), 'x');
    expect(p).toMatchObject({ type: 'playlist', mid: MID, playlist: { name: 'Road trip' } });
    expect(p && p.type === 'playlist' && p.playlist.tracks.map((t) => t.id)).toEqual(['JGwWNGJdvx8', 'kJQP7kiw5Fk']);
    const many = Array.from({ length: 400 }, (_, i) => song(`x${String(i).padStart(10, '0')}`));
    const big = parsePayload(payloads.playlist('Big', many), 'x');
    expect(big && big.type === 'playlist' && big.playlist.tracks.length).toBe(300);
    expect(parsePayload('{"v":1,"type":"playlist","mid":"abcdefgh","playlist":{"name":"x","tracks":[{"id":"bad"}]}}', 'x')).toBeNull();
    expect(p && p.type === 'playlist' && preview(toMessage(p, RAVI, false, 1, 'received'))).toBe('📃 Road trip');
  });
  it('reads what Android sends', () => {
    const android = '{"v":1,"type":"playlist","mid":"mid-0001-abcd","playlist":{"name":"Gym","tracks":[{"id":"JGwWNGJdvx1","title":"Song 1","artist":"Artist","artworkUrl":"https://i.ytimg.com/1.jpg","durationSeconds":200}]}}';
    expect(parsePayload(android, 'x')).toMatchObject({ type: 'playlist', playlist: { name: 'Gym', tracks: [{ id: 'JGwWNGJdvx1', title: 'Song 1', duration: 200 }] } });
  });
});
