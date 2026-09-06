/**
 * Taste Model V1 weights.
 *
 * Chosen deliberately for explainability, not trained fit:
 * - Favorites are explicit intent → highest positive weight.
 * - Completions beat bare play starts; early skips are stronger negatives than late skips.
 * - Replays and multi-session returns capture active preference, not one-shot exposure.
 * - Recency is a modest boost so old history does not dominate forever.
 * - Queue add is weak positive intent; queue remove is weak negative (not dislike).
 * - All contributions are bounded so a single event cannot create extreme scores.
 *
 * Tuning must be offline against replayed event histories — never silent ML.
 */

export const TASTE_WEIGHTS = Object.freeze({
  track: Object.freeze({
    completion: 0.34,
    replay: 0.22,
    favorite: 0.28,
    repetition: 0.18,
    recency: 0.12,
    queueAdd: 0.06,
    earlySkip: 0.36,
    skip: 0.14,
    queueRemove: 0.08,
  }),
  artist: Object.freeze({
    /** Preference blends track affinities and explicit positives. */
    trackAffinity: 0.45,
    completionRate: 0.20,
    replayRate: 0.12,
    favorite: 0.18,
    repetition: 0.15,
    recency: 0.10,
    earlySkipRate: 0.30,
    skipRate: 0.12,
    /** Exposure uses play volume only — never treated as preference alone. */
    exposureCap: 0.85,
  }),
  album: Object.freeze({
    trackAffinity: 0.40,
    completionRate: 0.18,
    replayRate: 0.12,
    favorite: 0.15,
    breadth: 0.20,
    recency: 0.08,
    earlySkipRate: 0.28,
    skipRate: 0.10,
    /** Require this many distinct tracks before album affinity is trusted. */
    minTracksForConfidence: 2,
  }),
  discovery: Object.freeze({
    successRate: 0.45,
    averageCompletion: 0.25,
    replayRate: 0.15,
    earlySkipRate: 0.40,
    skipRate: 0.15,
    /** Minimum attributed play_starts before surface affinity is reported as reliable. */
    minSamples: 3,
  }),
  /** Half-life for recency boost (ms). ~21 days. */
  recencyHalfLifeMs: 21 * 24 * 60 * 60 * 1000,
  /** Default recent window: 14 days. */
  recentWindowMs: 14 * 24 * 60 * 60 * 1000,
});

export const MISSING_TASTE_SIGNALS = Object.freeze([
  'wall_clock_listen_excluding_pause',
  'queue_position_at_play',
  'player_repeat_mode_events',
  'impression_without_play',
  'provider_surface_exposure_without_play',
  'cross_device_history',
  'reliable_album_id_on_every_event',
  'reliable_artist_id_on_every_event',
  'audio_features',
  'genre_mood_language_inference',
] as const);
