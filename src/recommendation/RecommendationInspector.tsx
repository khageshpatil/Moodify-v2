import { useState } from 'react';
import type { RecommendationResult } from './recommendationTypes';
import type { AutoplayDiagnostics, RecommendationBatch } from './recommendationAutoplay';

interface RecommendationInspectorProps {
  result: RecommendationResult | null;
  autoplay?: AutoplayDiagnostics | null;
  batch?: RecommendationBatch | null;
  currentTitle?: string;
  queueRemaining?: number;
}

export const RecommendationInspector = ({ result, autoplay, batch, currentTitle, queueRemaining }: RecommendationInspectorProps) => {
  const [open, setOpen] = useState(false);

  if (!import.meta.env.DEV || new URLSearchParams(window.location.search).get('recommendationDebug') !== '1') {
    return null;
  }

  const remaining = autoplay?.remaining ?? 0;

  return (
    <aside className="fixed bottom-4 left-4 z-[70] w-[28rem] max-w-[calc(100vw-2rem)] rounded-lg border border-white/20 bg-black/90 p-3 text-xs text-white shadow-2xl">
      <button className="mb-2 flex w-full items-center justify-between font-semibold" onClick={() => setOpen((value) => !value)}>
        <span>Recommendation inspector</span>
        <span>{open ? '−' : '+'}</span>
      </button>
      {open && (
        <>
          <div className="mb-2 rounded bg-white/5 p-2 text-white/80">
            CURRENT · {currentTitle || 'none'} · remaining {autoplay?.remaining ?? queueRemaining ?? remaining}
          </div>
          <div className="mb-2 rounded bg-white/5 p-2 text-white/80">
            AUTOPLAY · {autoplay?.reason || 'idle'} · prefetch {String(Boolean(autoplay?.prefetch))} · injected {String(Boolean(autoplay?.injected))}
            {autoplay?.injectedKeys.length ? <div className="truncate text-white/50">{autoplay.injectedKeys.join(', ')}</div> : null}
            {autoplay?.failedKeys.length ? <div className="truncate text-white/50">failed {autoplay.failedKeys.join(', ')}</div> : null}
          </div>
          {batch && (
            <div className="mb-2 rounded bg-white/5 p-2 text-white/70">
              BATCH {batch.id.slice(0, 24)} · {batch.candidates.length} locked · injected {batch.injectedKeys.length}
            </div>
          )}
          {result && (
            <>
              <div className="mb-2 text-white/70">
                {result.version} · {result.strategy} · {result.coldStartTier} · {result.candidates.length} generated
              </div>
              <div className="max-h-64 space-y-2 overflow-y-auto">
                {result.candidates.slice(0, 12).map((candidate) => (
                  <div key={candidate.trackKey} className="rounded bg-white/10 px-2 py-1">
                    <div className="flex justify-between gap-2">
                      <span className="truncate">{candidate.track.title}</span>
                      <span>{candidate.score.toFixed(2)}</span>
                    </div>
                    <div className="truncate text-white/60">{candidate.track.artist}</div>
                    <div className="text-white/50">{candidate.provenance.map((entry) => entry.source).join(', ')}</div>
                    <div className="text-white/40">{candidate.explanationKeys.join(' · ')}</div>
                  </div>
                ))}
                {!result.candidates.length && <div className="text-white/50">No recommendations yet.</div>}
              </div>
            </>
          )}
          {!result && <div className="text-white/50">Recommendation engine warming up…</div>}
        </>
      )}
    </aside>
  );
};
