import { describe, expect, it } from 'vitest';
import { appendFinishedListen, reviveListenHistory } from '../listenHistory';

const track = {
  id: 't1',
  title: 'Late At Night',
  artist: 'Roddy Ricch',
  album: '',
  albumArt: '',
  duration: 194,
  provider: 'youtube-music',
  providerId: 't1',
};

describe('listen history contract', () => {
  it('records a finished listen and does not treat play-start as history', () => {
    const startedOnly: typeof track[] = [];
    expect(startedOnly).toHaveLength(0);
    const history = appendFinishedListen([], track, 32_000, new Date('2026-09-06T00:00:00.000Z'));
    expect(history).toHaveLength(1);
    expect(history[0].track.id).toBe('t1');
    expect(history[0].duration).toBe(32_000);
  });

  it('revives persisted ISO dates without fabricating tracks', () => {
    const revived = reviveListenHistory([
      { track, playedAt: '2026-09-06T00:00:00.000Z', duration: 180000 },
      { track: { id: '' }, playedAt: 'nope' },
    ]);
    expect(revived).toHaveLength(1);
    expect(revived[0].playedAt.toISOString()).toBe('2026-09-06T00:00:00.000Z');
  });
});
