import { useState } from 'react';
import type { MoodifyHomeModel } from '@/home/homeTypes';
import type { MoodifyUserState } from './userStateTypes';
import { estimateUserStateBytes } from './estimateUserStateBytes';

interface UserStateInspectorProps {
  userState: MoodifyUserState;
  homeModel: MoodifyHomeModel;
}

export const UserStateInspector = ({ userState, homeModel }: UserStateInspectorProps) => {
  const [open, setOpen] = useState(false);

  if (!import.meta.env.DEV || new URLSearchParams(window.location.search).get('userStateDebug') !== '1') {
    return null;
  }

  const bytes = estimateUserStateBytes();
  const taste = userState.taste;
  const recs = userState.recommendations.result;

  return (
    <aside className="fixed bottom-4 right-4 z-[70] w-[30rem] max-w-[calc(100vw-2rem)] rounded-lg border border-white/20 bg-black/90 p-3 text-xs text-white shadow-2xl">
      <button className="mb-2 flex w-full items-center justify-between font-semibold" onClick={() => setOpen((value) => !value)}>
        <span>User state inspector</span>
        <span>{open ? '−' : '+'}</span>
      </button>
      {open && (
        <div className="max-h-[70vh] space-y-2 overflow-y-auto">
          <div className="rounded bg-white/5 p-2 text-white/80">
            USER STATE · schema {userState.schemaVersion} · {bytes} chars · events {userState.listening.eventCount}
            <div>library playlists {userState.library.playlistCount} · saved tracks {userState.library.savedTrackCount}</div>
            <div>session {userState.listening.session?.sessionId || 'none'}</div>
            <div className="text-white/50">{userState.identity.note}</div>
          </div>
          <div className="rounded bg-white/5 p-2 text-white/80">
            TASTE · {taste?.version || 'none'} · events {taste?.eventCount ?? 0}
            <div>top tracks {(taste?.longTerm.topTrackKeys || []).slice(0, 4).join(', ') || '—'}</div>
            <div>top artists {(taste?.longTerm.topArtistKeys || []).slice(0, 4).join(', ') || '—'}</div>
            <div>discovery {Object.keys(taste?.discovery || {}).sort().join(', ') || '—'}</div>
          </div>
          <div className="rounded bg-white/5 p-2 text-white/80">
            HOME · {homeModel.version} · {homeModel.coldStartTier}
            {homeModel.sections.map((section) => (
              <div key={section.id} className="mt-1 border-t border-white/10 pt-1">
                <div>{section.title} · {section.prominence} · {section.items.length}</div>
                {section.items.slice(0, 4).map((item) => (
                  <div key={item.id} className="truncate text-white/50">
                    {item.kind} · {item.source} · {item.score?.toFixed?.(2) || '—'} · {item.provenance?.map((entry) => entry.source).join(',') || '—'}
                    {item.explanationKeys?.length ? ` · ${item.explanationKeys.join('+')}` : ''}
                  </div>
                ))}
              </div>
            ))}
          </div>
          <div className="rounded bg-white/5 p-2 text-white/80">
            RECOMMENDATIONS · {recs?.strategy || 'none'} · batch {userState.recommendations.batch?.id?.slice(0, 18) || 'none'}
            <div>eval events {userState.recommendations.evaluation.length} (shown ≠ played)</div>
          </div>
        </div>
      )}
    </aside>
  );
};
