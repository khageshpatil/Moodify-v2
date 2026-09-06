import type { ListeningEvent, ListeningSession } from './listeningTypes';

const EVENTS_KEY = 'moodify_listening_events';
const SESSION_KEY = 'moodify_listening_session';
const DEFAULT_MAX_EVENTS = 2000;
const DEFAULT_MAX_AGE_MS = 1000 * 60 * 60 * 24 * 180;

const readJson = <T,>(key: string, fallback: T): T => {
  if (typeof window === 'undefined') return fallback;
  try {
    const raw = window.localStorage.getItem(key);
    return raw ? JSON.parse(raw) as T : fallback;
  } catch {
    return fallback;
  }
};

export class ListeningEventStore {
  constructor(
    private readonly maxEvents = DEFAULT_MAX_EVENTS,
    private readonly maxAgeMs = DEFAULT_MAX_AGE_MS,
  ) {}

  loadEvents(now = Date.now()): ListeningEvent[] {
    const events = readJson<ListeningEvent[]>(EVENTS_KEY, []);
    if (!Array.isArray(events)) return [];
    return events
      .filter((event) => event && typeof event.id === 'string' && typeof event.timestamp === 'number')
      .filter((event) => now - event.timestamp <= this.maxAgeMs)
      .slice(-this.maxEvents);
  }

  append(event: ListeningEvent): ListeningEvent[] {
    const events = [...this.loadEvents(event.timestamp), event].slice(-this.maxEvents);
    this.write(EVENTS_KEY, events);
    return events;
  }

  loadSession(): ListeningSession | null {
    const session = readJson<ListeningSession | null>(SESSION_KEY, null);
    return session && typeof session.sessionId === 'string' ? session : null;
  }

  saveSession(session: ListeningSession) {
    this.write(SESSION_KEY, session);
  }

  private write(key: string, value: unknown) {
    if (typeof window === 'undefined') return;
    try {
      window.localStorage.setItem(key, JSON.stringify(value));
    } catch {
      // Storage is optional. A full/private storage area must not break playback.
      try {
        if (key === EVENTS_KEY) {
          const compact = (value as ListeningEvent[]).slice(-Math.floor(this.maxEvents / 2));
          window.localStorage.setItem(key, JSON.stringify(compact));
        }
      } catch {
        // Ignore unavailable storage.
      }
    }
  }
}

export const listeningEventStore = new ListeningEventStore();
