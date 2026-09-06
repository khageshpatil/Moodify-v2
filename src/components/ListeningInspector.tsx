import { useEffect, useState } from 'react';
import type { ListeningEventCollector } from '@/listening/ListeningEventCollector';
import type { ListeningEvent } from '@/listening/listeningTypes';

interface ListeningInspectorProps {
  collector: ListeningEventCollector;
}

export const ListeningInspector = ({ collector }: ListeningInspectorProps) => {
  const [events, setEvents] = useState<ListeningEvent[]>(() => collector.getEvents());
  const [open, setOpen] = useState(true);

  useEffect(() => collector.subscribe(setEvents), [collector]);

  if (!import.meta.env.DEV || new URLSearchParams(window.location.search).get('listeningDebug') !== '1') return null;
  const session = collector.getSession();
  return (
    <aside className="fixed bottom-4 right-4 z-[70] w-96 max-w-[calc(100vw-2rem)] rounded-lg border border-white/20 bg-black/90 p-3 text-xs text-white shadow-2xl">
      <button className="mb-2 flex w-full items-center justify-between font-semibold" onClick={() => setOpen((value) => !value)}>
        <span>Listening inspector</span>
        <span>{open ? '−' : '+'}</span>
      </button>
      {open && (
        <>
          <div className="mb-2 text-white/70">
            Session {session.sessionId.slice(0, 8)} · {session.tracksPlayed.length} track plays
          </div>
          <div className="max-h-64 space-y-1 overflow-y-auto">
            {events.slice(-20).reverse().map((event) => (
              <div key={event.id} className="rounded bg-white/10 px-2 py-1">
                <div className="flex justify-between gap-2"><span>{event.type}</span><span>{new Date(event.timestamp).toLocaleTimeString()}</span></div>
                <div className="truncate text-white/60">{event.trackId} · {event.positionSeconds?.toFixed(1) ?? '—'}s</div>
              </div>
            ))}
            {!events.length && <div className="text-white/50">No listening events yet.</div>}
          </div>
        </>
      )}
    </aside>
  );
};
