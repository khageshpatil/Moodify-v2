import type { ListeningEvent } from '@/listening/listeningTypes';
import { identityKey } from '@/listening/listeningTypes';

export type RecommendationEvalType = 'shown' | 'played' | 'completed' | 'skipped' | 'favorited';

export interface RecommendationEvalEvent {
  id: string;
  type: RecommendationEvalType;
  trackKey: string;
  batchId?: string;
  timestamp: number;
  strategy?: string;
}

const STORAGE_KEY = 'moodify_recommendation_eval_v1';
const MAX_EVENTS = 500;

const readJson = <T,>(key: string, fallback: T): T => {
  if (typeof window === 'undefined') return fallback;
  try {
    const raw = window.localStorage.getItem(key);
    return raw ? JSON.parse(raw) as T : fallback;
  } catch {
    return fallback;
  }
};

export class RecommendationEvalStore {
  constructor(private readonly storage: Storage | null = typeof window === 'undefined' ? null : window.localStorage) {}

  load(): RecommendationEvalEvent[] {
    const events = this.storage ? readJson<RecommendationEvalEvent[]>(STORAGE_KEY, []) : [];
    return Array.isArray(events) ? events.slice(-MAX_EVENTS) : [];
  }

  append(event: RecommendationEvalEvent) {
    const events = [...this.load(), event].slice(-MAX_EVENTS);
    if (!this.storage) return events;
    try {
      this.storage.setItem(STORAGE_KEY, JSON.stringify(events));
    } catch {
      // Evaluation is optional and must not affect playback.
    }
    return events;
  }
}

export const recommendationEvalStore = new RecommendationEvalStore();

export const deriveRecommendationOutcomes = (events: ListeningEvent[]): RecommendationEvalEvent[] => {
  const outcomes: RecommendationEvalEvent[] = [];
  events.forEach((event) => {
    if (event.discoveryContext?.type !== 'recommendation') return;
    const type: RecommendationEvalType | null = event.type === 'play_started'
      ? 'played'
      : event.type === 'play_completed'
        ? 'completed'
        : event.type === 'skip'
          ? 'skipped'
          : event.type === 'favorite'
            ? 'favorited'
            : null;
    if (!type) return;
    outcomes.push({
      id: `eval-${event.id}`,
      type,
      trackKey: identityKey(event),
      batchId: event.discoveryContext?.id,
      timestamp: event.timestamp,
      strategy: event.discoveryContext?.strategy,
    });
  });
  return outcomes;
};
