import { USER_STATE_SCHEMA_VERSION, type MoodifyUserState, type UserStateAssemblyInput } from './userStateTypes';

export const assembleUserState = (input: UserStateAssemblyInput): MoodifyUserState => ({
  schemaVersion: USER_STATE_SCHEMA_VERSION,
  generatedAt: input.now,
  identity: {
    kind: 'anonymous-local',
    note: 'No authenticated Moodify account. Local device state only. Provider account personalization is not assumed.',
  },
  listening: {
    eventCount: input.events.length,
    historyCount: input.history.length,
    recentCount: input.recentlyPlayed.length,
    favoriteCount: input.favorites.length,
    skipCount: input.events.filter((event) => event.type === 'skip').length,
    session: input.session,
    openThread: input.envelope.openThread,
  },
  library: {
    playlistCount: input.playlists.length,
    savedTrackCount: input.favorites.length,
    savedAlbumKeys: [...input.envelope.libraryExtras.savedAlbumKeys].sort(),
    savedArtistKeys: [...input.envelope.libraryExtras.savedArtistKeys].sort(),
    savedSceneIds: [...input.envelope.libraryExtras.savedSceneIds].sort(),
  },
  discovery: {
    graphNodeCount: input.graphNodeCount,
    graphEdgeCount: input.graphEdgeCount,
    surfaces: [...input.discoverySurfaces].sort(),
  },
  taste: input.taste,
  recommendations: {
    result: input.recommendationResult,
    batch: input.recommendationBatch,
    evaluation: input.evaluation,
  },
  preferences: {
    volume: input.volume,
    shuffle: input.shuffle,
    repeat: input.repeat,
  },
  sources: {
    listeningEvents: 'moodify_listening_events',
    playlists: 'moodify_playlists',
    favorites: 'moodify_favorites',
    history: 'moodify_history',
    recentlyPlayed: 'moodify_recently_played',
    volume: 'moodify_volume',
    identity: 'moodify_identity',
    trackDna: 'moodify_track_dna_v1',
    recommendationEval: 'moodify_recommendation_eval_v1',
    envelope: 'moodify_user_state_v1',
  },
});
