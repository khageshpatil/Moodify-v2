import { describe, expect, it } from 'vitest';
import { playbackForHomeItem } from '../homePlayback';
import { sectionById, tracksFromItems } from '../homePresentation';
import { HOME_MODEL_VERSION, SCENE_CONTRACT_VERSION, type MoodifyHomeModel } from '../homeTypes';

const track = (id: string) => ({
  id,
  title: `Track ${id}`,
  artist: 'Artist A',
  album: 'Album',
  albumArt: '',
  duration: 180,
  provider: 'youtube-music' as const,
  providerId: id,
});

const model = (overrides: Partial<MoodifyHomeModel> = {}): MoodifyHomeModel => ({
  version: HOME_MODEL_VERSION,
  generatedAt: 1,
  coldStartTier: 'mature',
  sceneContractVersion: SCENE_CONTRACT_VERSION,
  publishedScenes: [],
  moodifyMix: {
    version: 'moodify-mix-v1.0.0',
    id: 'mix:1:continue-listening',
    engineVersion: 'recommendation-engine-v1',
    strategy: 'continue-listening',
    generatedAt: 1,
    candidateKeys: ['youtube-music:a', 'youtube-music:b'],
    source: 'recommendation-engine-v1',
  },
  sections: [
    {
      id: 'forYou',
      title: 'For You',
      visible: true,
      prominence: 'prominent',
      items: [
        { id: 'for-you:a', kind: 'track', track: track('a'), source: 'taste-track', provenance: [{ source: 'taste-track', evidence: ['taste'] }] },
        { id: 'for-you:b', kind: 'track', track: track('b'), source: 'taste-track' },
      ],
    },
    {
      id: 'continueListening',
      title: 'Continue Listening',
      visible: true,
      prominence: 'prominent',
      items: [{ id: 'continue-recent:a', kind: 'track', track: track('a'), source: 'recently-played' }],
    },
  ],
  ...overrides,
});

describe('home presentation and playback', () => {
  it('reads section tracks without reordering', () => {
    const home = model();
    expect(sectionById(home, 'forYou')?.items.map((item) => item.track?.id)).toEqual(['a', 'b']);
    expect(tracksFromItems(sectionById(home, 'forYou')!.items).map((item) => item.id)).toEqual(['a', 'b']);
  });

  it('plays For You with recommendation provenance and the section queue', () => {
    const home = model();
    const item = sectionById(home, 'forYou')!.items[1];
    const request = playbackForHomeItem(home, 'forYou', item);
    expect(request?.tracks.map((entry) => entry.id)).toEqual(['a', 'b']);
    expect(request?.track.id).toBe('b');
    expect(request?.discoveryContext.type).toBe('recommendation');
    expect(request?.discoveryContext.id).toBe('mix:1:continue-listening');
    expect(request?.discoveryContext.position).toBe(1);
  });

  it('plays Moodify Mix as a recommendation session queue', () => {
    const home = model({
      sections: [
        ...model().sections,
        {
          id: 'moodifyMix',
          title: 'Moodify Mix',
          visible: true,
          prominence: 'prominent',
          items: [{ id: 'mix:1:continue-listening', kind: 'mix', source: 'recommendation-engine-v1', queue: [track('a'), track('b')] }],
        },
      ],
    });
    const request = playbackForHomeItem(home, 'moodifyMix', home.sections.find((section) => section.id === 'moodifyMix')!.items[0]);
    expect(request?.tracks.map((entry) => entry.id)).toEqual(['a', 'b']);
    expect(request?.discoveryContext.type).toBe('recommendation');
    expect(request?.discoveryContext.id).toBe('mix:1:continue-listening');
  });
  it('plays continue from the open thread origin and remainder', () => {
    const home = model({
      sections: [
        {
          id: 'continueListening',
          title: 'Continue',
          visible: true,
          prominence: 'prominent',
          items: [{
            id: 'continue-open-thread',
            kind: 'track',
            track: track('a'),
            queue: [track('a'), track('b')],
            source: 'open-thread',
            owner: 'user',
            discoveryContext: { type: 'search', id: 'rain songs', position: 0 },
          }],
        },
      ],
    });
    const item = sectionById(home, 'continueListening')!.items[0];
    const request = playbackForHomeItem(home, 'continueListening', item);
    expect(request?.tracks.map((entry) => entry.id)).toEqual(['a', 'b']);
    expect(request?.discoveryContext).toEqual({ type: 'search', id: 'rain songs', position: 0 });
  });
});
