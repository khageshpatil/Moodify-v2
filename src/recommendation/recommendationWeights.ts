/**
 * Recommendation Engine V1 weights.
 *
 * Deterministic policy surface — not ML-tuned.
 * Provider graph edges are discovery evidence, not preference.
 * User taste signals dominate ranking; graph/sequence provide context.
 */

export const RECOMMENDATION_WEIGHTS = Object.freeze({
  scoring: Object.freeze({
    tasteAffinity: 0.28,
    artistAffinity: 0.18,
    albumAffinity: 0.10,
    graphRelationship: 0.14,
    discoverySourceAffinity: 0.10,
    sessionContext: 0.08,
    recencyFit: 0.06,
    familiarity: 0.08,
    explorationBonus: 0.06,
    recentSkipPenalty: 0.22,
    repeatedSkipPenalty: 0.18,
    queueDuplicatePenalty: 0.30,
    immediateRepeatPenalty: 0.40,
    repeatedRecommendationPenalty: 0.12,
  }),
  graph: Object.freeze({
    maxDepth: 2,
    maxCandidates: 120,
    edgeStrength: Object.freeze({
      related_to: 0.55,
      queued_after: 0.50,
      performed_by: 0.45,
      belongs_to: 0.40,
      contains: 0.42,
      surfaced_in: 0.35,
      returned_for: 0.30,
    }),
    depthDecay: 0.65,
  }),
  filter: Object.freeze({
    /** Recent skip penalty window (ms) — ~3 days. */
    recentSkipWindowMs: 3 * 24 * 60 * 60 * 1000,
    /** Recently played decay window (ms) — ~2 hours for strong penalty. */
    recentPlayWindowMs: 2 * 60 * 60 * 1000,
    /** Favorite tracks get reduced recent-play penalty. */
    favoriteRecencyMultiplier: 0.35,
    /** Skips before repeated-skip filter kicks in. */
    repeatedSkipThreshold: 2,
    /** Repeated skip lookback (ms) — ~14 days. */
    repeatedSkipWindowMs: 14 * 24 * 60 * 60 * 1000,
  }),
  exploration: Object.freeze({
    /** Default exploitation ratio when taste is mature. */
    defaultExploitationRatio: 0.80,
    /** Cold-start exploitation ratio (more graph exploration). */
    coldStartExploitationRatio: 0.55,
    /** Sparse taste exploitation ratio. */
    sparseExploitationRatio: 0.65,
    /** Emerging taste exploitation ratio. */
    emergingExploitationRatio: 0.75,
  }),
  diversity: Object.freeze({
    maxPerArtist: 2,
    maxPerAlbum: 2,
    maxPerSource: 3,
    /** Penalty applied when diversity cap would be exceeded. */
    overflowPenalty: 0.15,
  }),
  coldStart: Object.freeze({
    emptyMaxEvents: 0,
    sparseMaxEvents: 5,
    emergingMaxEvents: 20,
  }),
  session: Object.freeze({
    /** Minimum transitions before sequence signal is trusted. */
    minTransitions: 2,
    transitionBoost: 0.12,
  }),
  /** Half-life for negative feedback decay (ms) — ~10 days. */
  negativeDecayHalfLifeMs: 10 * 24 * 60 * 60 * 1000,
});
