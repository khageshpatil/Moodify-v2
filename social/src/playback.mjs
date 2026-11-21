/**
 * Playback settings the Android app reads when it starts (docs/social-protocol/playback.md):
 * which YouTube clients to ask for a song's stream, in what order, and how long to wait.
 * Set the PLAYBACK_CONFIG variable (JSON) to change them without shipping an APK. Without
 * it, or for any field it leaves out, the app keeps its own defaults.
 */
const CLIENT_NAME = /^[A-Z0-9_]{2,40}$/;
const TIMINGS = ['hedgeAfterMs', 'perClientMs', 'budgetMs'];

/** The config to serve from the raw variable: only well-formed fields, everything else dropped. */
export function playbackConfig(raw) {
  let parsed;
  try {
    parsed = typeof raw === 'string' && raw.trim() ? JSON.parse(raw) : {};
  } catch {
    parsed = {};
  }
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) parsed = {};
  const config = {};
  if (Array.isArray(parsed.clients)) {
    const clients = [...new Set(parsed.clients.filter((c) => typeof c === 'string' && CLIENT_NAME.test(c)))].slice(0, 20);
    if (clients.length) config.clients = clients;
  }
  for (const key of TIMINGS) {
    const value = parsed[key];
    if (Number.isFinite(value) && value > 0) config[key] = Math.round(value);
  }
  return config;
}
