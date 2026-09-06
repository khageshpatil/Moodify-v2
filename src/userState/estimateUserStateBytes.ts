export const estimateUserStateBytes = (storage: Storage | null = typeof window === 'undefined' ? null : window.localStorage) => {
  if (!storage) return 0;
  const keys = [
    'moodify_user_state_v1',
    'moodify_listening_events',
    'moodify_listening_session',
    'moodify_playlists',
    'moodify_favorites',
    'moodify_history',
    'moodify_recently_played',
    'moodify_volume',
    'moodify_recommendation_eval_v1',
    'moodify_taste_snapshot_v1',
    'moodify_track_dna_v1',
    'moodify_identity',
  ];
  return keys.reduce((sum, key) => sum + (storage.getItem(key)?.length || 0), 0);
};
