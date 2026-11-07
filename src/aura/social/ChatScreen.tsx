import { normalizeCode } from '@social/identity.mjs';
import { ArrowLeft, Bell, Copy, Headphones, Music2, Play, Send, SmilePlus } from 'lucide-react';
import { useEffect, useLayoutEffect, useRef, useState, type KeyboardEvent } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { usePlayer } from '../PlayerContext';
import { Artwork, EmptyState } from '../ui';
import { MAX_TEXT, QUICK_REACTIONS, type ChatMessage, type SharedPlaylist } from './chatModel';
import { useRoom } from './RoomProvider';
import { Avatar, errorText, timeAgo, useToast } from './SocialScreens';
import { useSocial } from './SocialProvider';

const clock = (seconds: number) => new Date(seconds * 1000).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' });
const day = (seconds: number) => new Date(seconds * 1000).toLocaleDateString([], { weekday: 'long', month: 'short', day: 'numeric' });

/** One conversation with a friend (docs/social-protocol/chat.md). */
export function ChatScreen() {
  const params = useParams();
  const peer = normalizeCode(params.code ?? '') ?? '';
  const social = useSocial();
  const player = usePlayer();
  const rooms = useRoom();
  const navigate = useNavigate();
  const { toast, say } = useToast();
  const [draft, setDraft] = useState('');
  const [picker, setPicker] = useState<string | null>(null);
  const bottom = useRef<HTMLDivElement>(null);
  const friend = social.lists.friends.find((f) => f.code === peer);
  const thread = social.threads[peer] ?? [];
  const presence = social.presence[peer];
  const setActive = useRef(social.setActiveThread);
  setActive.current = social.setActiveThread;

  // On screen: keep it read and quiet; reading again when the tab comes back.
  useEffect(() => {
    const mark = () => setActive.current(document.hidden ? null : peer);
    mark();
    document.addEventListener('visibilitychange', mark);
    return () => { document.removeEventListener('visibilitychange', mark); setActive.current(null); };
  }, [peer]);
  useEffect(() => { if (!document.hidden && thread.some((m) => !m.fromMe && !m.read)) setActive.current(peer); }, [thread, peer]);
  useLayoutEffect(() => { bottom.current?.scrollIntoView({ block: 'end' }); }, [thread.length]);

  if (!social.ready) return <div className="aura-page">Loading…</div>;
  if (!peer) return <div className="aura-page"><EmptyState icon={<Music2 />} title="No such conversation" message="That link doesn't point to a Moodify code." /></div>;

  const name = friend?.displayName ?? thread[0]?.peer ?? peer;
  const status = !friend ? 'Not friends any more'
    : presence?.online && presence.playing && presence.track ? `Listening to ${presence.track.title}`
      : presence?.online ? 'Online'
        : presence?.lastSeenAt ? `Seen ${timeAgo(presence.lastSeenAt)}` : friend.code;
  const act = async (work: () => Promise<unknown>) => { try { await work(); } catch (e) { say(errorText(e)); } };
  const send = () => {
    const text = draft.trim();
    if (!text || !friend) return;
    setDraft('');
    void act(() => social.sendText(friend, text));
  };
  const onKey = (e: KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) { e.preventDefault(); send(); }
  };
  const nowPlaying = player.currentTrack;

  return (
    <div className="aura-page aura-chat">
      <header className="aura-chat-header">
        <button className="aura-icon-btn" aria-label="Back" onClick={() => (window.history.length > 1 ? navigate(-1) : navigate('/friends'))}><ArrowLeft size={22} /></button>
        <Avatar user={{ code: peer, displayName: name }} size={40} online={!!presence?.online} />
        <div className="aura-track-text">
          <div className="type-title-medium aura-ellipsis">{name}</div>
          <div className="type-body-small aura-muted aura-ellipsis">{status}</div>
        </div>
      </header>

      {social.notifications === 'default' && (
        <button className="aura-banner aura-row" style={{ width: '100%', marginBottom: 12 }} onClick={() => void social.enableNotifications()}>
          <Bell size={18} /><span style={{ flex: 1, textAlign: 'left' }}>Get a notification when {name} messages you</span><b>Turn on</b>
        </button>
      )}

      <div className="aura-chat-list" role="log" aria-label={`Messages with ${name}`}>
        {thread.length === 0 && (
          <EmptyState icon={<Music2 />} title={`Say hi to ${name}`} message="Messages are end-to-end encrypted: only you and your friend can read them." />
        )}
        {thread.map((m, i) => {
          const prev = thread[i - 1];
          const newDay = !prev || day(prev.sentAt) !== day(m.sentAt);
          return (
            <div key={m.mid}>
              {newDay && <div className="aura-chat-day type-label-medium aura-muted">{day(m.sentAt)}</div>}
              <Bubble
                message={m}
                me={social.code ?? ''}
                pickerOpen={picker === m.mid}
                onPicker={(open) => setPicker(open ? m.mid : null)}
                onReact={(emoji) => { setPicker(null); if (friend) void act(() => social.react(peer, m.mid, emoji)); }}
                onRetry={() => void act(() => social.retry(peer, m.mid))}
                onCopy={() => { void navigator.clipboard?.writeText(m.text ?? ''); setPicker(null); say('Copied'); }}
                onPlay={(t, queue) => player.playTrack(t, queue)}
                onSavePlaylist={(p) => { player.createPlaylist(p.name, p.tracks); say(`Saved “${p.name}” to your library`); }}
                onJoin={(roomId) => { rooms.join(roomId); navigate('/room'); }}
              />
            </div>
          );
        })}
        <div ref={bottom} />
      </div>

      {friend ? (
        <form className="aura-chat-composer" onSubmit={(e) => { e.preventDefault(); send(); }}>
          {nowPlaying && (
            <button type="button" className="aura-icon-btn" aria-label={`Send ${nowPlaying.title}`} title={`Send ${nowPlaying.title}`}
              onClick={() => void act(() => social.sendTrack(friend, nowPlaying))}>
              <Music2 size={20} />
            </button>
          )}
          <textarea className="aura-chat-input" rows={1} value={draft} maxLength={MAX_TEXT} placeholder={`Message ${name}`}
            aria-label={`Message ${name}`} onChange={(e) => setDraft(e.target.value)} onKeyDown={onKey} />
          <button type="submit" className="aura-icon-btn aura-chat-send" aria-label="Send" disabled={!draft.trim()}><Send size={20} /></button>
        </form>
      ) : (
        <div className="aura-banner" style={{ marginTop: 12 }}>You can read this conversation, but you're no longer friends. <Link to="/friends"><b>Friends</b></Link></div>
      )}
      {toast}
    </div>
  );
}

function Bubble({ message: m, me, pickerOpen, onPicker, onReact, onRetry, onCopy, onPlay, onJoin, onSavePlaylist }: {
  message: ChatMessage; me: string; pickerOpen: boolean;
  onPicker(open: boolean): void; onReact(emoji: string): void; onRetry(): void; onCopy(): void;
  onPlay(t: NonNullable<ChatMessage['track']>, queue: NonNullable<ChatMessage['track']>[]): void; onJoin(roomId: string): void;
  onSavePlaylist(p: SharedPlaylist): void;
}) {
  const reactions = Object.entries(m.reactions);
  const counts = reactions.reduce<Record<string, number>>((all, [, e]) => ({ ...all, [e]: (all[e] ?? 0) + 1 }), {});
  const mine = m.reactions[me];
  return (
    <div className="aura-chat-row" data-me={m.fromMe}>
      <div className="aura-chat-stack">
        <div className="aura-chat-bubble-wrap">
          <div className="aura-chat-bubble" data-me={m.fromMe} data-kind={m.kind} title={clock(m.sentAt)}
            onContextMenu={(e) => { e.preventDefault(); onPicker(true); }}>
            {m.kind === 'text' && <span className="aura-chat-text">{m.text}</span>}
            {(m.kind === 'track' || m.kind === 'room') && m.track && (
              <div className="aura-row">
                <Artwork src={m.track.albumArt} size={56} placeholder={m.track.id} />
                <div className="aura-track-text">
                  <div className="type-label-medium" style={{ opacity: 0.8 }}>{m.kind === 'room' ? 'Listen together' : 'Song'}</div>
                  <div className="type-title-small aura-ellipsis">{m.track.title}</div>
                  <div className="type-body-small aura-ellipsis" style={{ opacity: 0.8 }}>{m.track.artist}</div>
                </div>
              </div>
            )}
            {m.kind === 'room' && !m.track && <div className="aura-row"><Headphones size={22} /><div className="type-title-small">Listen together · {m.roomId}</div></div>}
            {m.kind === 'playlist' && m.playlist && (
              <div className="aura-row">
                <Artwork src={m.playlist.tracks[0]?.albumArt} size={56} placeholder={m.playlist.name} />
                <div className="aura-track-text">
                  <div className="type-label-medium" style={{ opacity: 0.8 }}>Playlist · {m.playlist.tracks.length} {m.playlist.tracks.length === 1 ? 'song' : 'songs'}</div>
                  <div className="type-title-small aura-ellipsis">{m.playlist.name}</div>
                  <div className="type-body-small aura-ellipsis" style={{ opacity: 0.7 }}>{m.playlist.tracks.slice(0, 3).map((t) => t.title).join(', ')}</div>
                </div>
              </div>
            )}
            {m.kind === 'playlist' && m.playlist && (
              <div className="aura-row" style={{ gap: 8 }}>
                <button className="aura-btn aura-btn-tonal aura-chat-card-btn" onClick={() => onPlay(m.playlist!.tracks[0], m.playlist!.tracks)}><Play size={14} fill="currentColor" />Play</button>
                {!m.fromMe && <button className="aura-btn aura-btn-tonal aura-chat-card-btn" onClick={() => onSavePlaylist(m.playlist!)}>Save to library</button>}
              </div>
            )}
            {m.kind === 'collection' && m.collection && (
              <div className="aura-row">
                <Artwork src={m.collection.artworkUrl} size={56} round={m.collection.kind === 'artist'} placeholder={m.collection.browseId} />
                <div className="aura-track-text">
                  <div className="type-label-medium" style={{ opacity: 0.8 }}>{m.collection.kind[0].toUpperCase() + m.collection.kind.slice(1)}</div>
                  <div className="type-title-small aura-ellipsis">{m.collection.title}</div>
                </div>
              </div>
            )}
            {m.kind === 'track' && m.track && <button className="aura-btn aura-btn-tonal aura-chat-card-btn" onClick={() => onPlay(m.track!, [m.track!])}><Play size={14} fill="currentColor" />Play</button>}
            {m.kind === 'room' && m.roomId && <button className="aura-btn aura-btn-tonal aura-chat-card-btn" onClick={() => onJoin(m.roomId!)}><Headphones size={14} />Join</button>}
            {m.kind === 'collection' && m.collection && (
              <Link className="aura-btn aura-btn-tonal aura-chat-card-btn" to={`/browse/${m.collection.kind}/${encodeURIComponent(m.collection.browseId)}${m.collection.params ? `?params=${encodeURIComponent(m.collection.params)}` : ''}`}>Open</Link>
            )}
          </div>
          <button className="aura-icon-btn aura-chat-react-btn" aria-label="React" aria-expanded={pickerOpen} onClick={() => onPicker(!pickerOpen)}><SmilePlus size={18} /></button>
        </div>
        {pickerOpen && (
          <div className="aura-chat-picker" role="menu" aria-label="Reactions">
            {QUICK_REACTIONS.map((e) => <button key={e} role="menuitemradio" aria-checked={mine === e} data-on={mine === e} onClick={() => onReact(e)}>{e}</button>)}
            {m.kind === 'text' && <button role="menuitem" aria-label="Copy" onClick={onCopy}><Copy size={16} /></button>}
          </div>
        )}
        {reactions.length > 0 && (
          <button className="aura-chat-reactions type-label-medium" aria-label={`Reactions: ${reactions.map(([, e]) => e).join(' ')}`} onClick={() => onPicker(!pickerOpen)}>
            {Object.entries(counts).map(([e, n]) => <span key={e}>{e}{n > 1 ? n : ''}</span>)}
          </button>
        )}
        {m.fromMe && m.status === 'sending' && <div className="type-body-small aura-muted">Sending…</div>}
        {m.fromMe && m.status === 'failed' && <button className="type-body-small aura-chat-failed" onClick={onRetry}>Not sent · Retry</button>}
      </div>
    </div>
  );
}
