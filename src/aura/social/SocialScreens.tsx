import { Check, Copy, Link2, MoreVertical, Play, QrCode, Share2, ShieldCheck, UserPlus, Users, X } from 'lucide-react';
import type { Track } from '@/data/mockMusic';
import QRCode from 'qrcode';
import { useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { usePlayer } from '../PlayerContext';
import { Artwork, EmptyState, SectionHeader, hueFor } from '../ui';
import { preview } from './chatModel';
import { useRoom } from './RoomProvider';
import { useSocial, type LinkSession, type SocialUser } from './SocialProvider';

export const timeAgo = (seconds: number) => {
  const minutes = Math.max(0, Math.floor((Date.now() / 1000 - seconds) / 60));
  if (minutes < 1) return 'just now';
  if (minutes < 60) return `${minutes}m ago`;
  if (minutes < 1440) return `${Math.floor(minutes / 60)}h ago`;
  return `${Math.floor(minutes / 1440)}d ago`;
};

export function Avatar({ user, size = 44, online = false }: { user: { code: string; displayName: string }; size?: number; online?: boolean }) {
  const hue = hueFor(user.code);
  return (
    <span className="aura-avatar" style={{ width: size, height: size, background: `linear-gradient(135deg, ${hue}, color-mix(in srgb, ${hue} 55%, black))` }} aria-hidden>
      {user.displayName.slice(0, 1).toUpperCase()}
      {online && <span className="aura-online" />}
    </span>
  );
}

export function useToast() {
  const [message, setMessage] = useState<string | null>(null);
  useEffect(() => { if (!message) return; const t = setTimeout(() => setMessage(null), 3500); return () => clearTimeout(t); }, [message]);
  return { toast: message ? <div className="aura-toast" role="status">{message}</div> : null, say: setMessage };
}

export const errorText = (err: unknown) => (err instanceof Error ? err.message : 'Something went wrong.');

// ── Account ──────────────────────────────────────────────────────────────────

export function AccountScreen() {
  const social = useSocial();
  const [mode, setMode] = useState<'create' | 'restore' | 'link'>('create');
  const [name, setName] = useState('');
  const [words, setWords] = useState('');
  const [newWords, setNewWords] = useState<string[] | null>(null);
  const [busy, setBusy] = useState(false);
  const { toast, say } = useToast();

  if (!social.ready) return <div className="aura-page">Loading…</div>;

  const run = async (work: () => Promise<void>) => {
    setBusy(true);
    try { await work(); } catch (err) { say(errorText(err)); } finally { setBusy(false); }
  };

  return (
    <div className="aura-page" style={{ maxWidth: 640 }}>
      <div className="aura-glow" />
      <h1 className="type-headline-medium">Your Moodify code</h1>
      {!social.code && (
        <div className="aura-card" style={{ display: 'grid', gap: 12 }}>
          <ShieldCheck color="var(--aura-primary)" />
          <div className="type-title-large">{mode === 'restore' ? 'Restore your code' : mode === 'link' ? 'Use the code from your phone' : 'Get your code to connect with friends'}</div>
          <div className="type-body-medium aura-muted">
            {mode === 'restore' ? 'Enter the 24 words you saved when you made your code.'
              : mode === 'link' ? 'Your phone sends your identity here, end-to-end encrypted.'
                : 'No phone number, no email, no photos. Your code is your identity, and only people you accept can reach you.'}
          </div>
          <div className="aura-chips">
            <button className="aura-chip" aria-pressed={mode === 'create'} onClick={() => setMode('create')}>New code</button>
            <button className="aura-chip" aria-pressed={mode === 'link'} onClick={() => setMode('link')}>Link my phone</button>
            <button className="aura-chip" aria-pressed={mode === 'restore'} onClick={() => setMode('restore')}>24 words</button>
          </div>
          {mode === 'link' ? <LinkPanel onDone={() => say('Linked. Welcome back.')} /> : (
            <>
              {mode === 'restore' && (
                <textarea className="aura-field" rows={3} value={words} onChange={(e) => setWords(e.target.value)} placeholder="Recovery key (24 words)" autoCapitalize="none" autoCorrect="off" spellCheck={false} />
              )}
              <input className="aura-field" value={name} maxLength={24} onChange={(e) => setName(e.target.value)} placeholder={mode === 'restore' ? 'Your name (if the server forgot it)' : 'What friends see you as'} />
              <button className="aura-btn aura-btn-primary" disabled={busy || (mode === 'create' ? name.trim().length < 2 : !words.trim())}
                onClick={() => run(async () => {
                  if (mode === 'create') setNewWords(await social.create(name));
                  else { await social.restore(words, name); say('Welcome back.'); }
                })}>
                {busy ? 'Working…' : mode === 'restore' ? 'Restore' : 'Create my code'}
              </button>
            </>
          )}
        </div>
      )}

      {social.code && (
        <div style={{ display: 'grid', gap: 16 }}>
          <div className="aura-card">
            <div className="type-title-medium aura-muted">{social.displayName}</div>
            <div className="type-display-small aura-mono" style={{ color: 'var(--aura-primary)' }}>{social.code}</div>
            <div className="aura-row" style={{ marginTop: 12, flexWrap: 'wrap' }}>
              <button className="aura-btn aura-btn-tonal" onClick={() => { void navigator.clipboard?.writeText(social.code!); say('Code copied'); }}><Copy size={16} />Copy</button>
              {'share' in navigator && <button className="aura-btn aura-btn-tonal" onClick={() => void navigator.share({ text: `Add me on Moodify: ${social.code}` })}><Share2 size={16} />Share</button>}
            </div>
            <div className="type-body-medium aura-muted" style={{ marginTop: 12 }}>
              {social.registered ? (social.connected ? 'Connected' : 'Signed in') : social.serverConfigured ? 'Saved in this browser, not connected yet.' : 'Saved in this browser. It connects when Moodify social goes live.'}
            </div>
          </div>
          {newWords && !social.backedUp
            ? <RecoveryCard words={newWords} onConfirmed={async () => { await social.confirmBackedUp(); setNewWords(null); say('Recovery key saved. Keep it safe.'); }} />
            : <ManageCard onSay={say} />}
        </div>
      )}
      {toast}
    </div>
  );
}

function LinkPanel({ onDone }: { onDone: () => void }) {
  const social = useSocial();
  const [link, setLink] = useState<LinkSession | null>(null);
  const [qr, setQr] = useState<string>('');
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    let active: LinkSession | null = null;
    social.startLink().then((session) => {
      active = session;
      setLink(session);
      void QRCode.toDataURL(session.qrText, { margin: 1, width: 240, color: { dark: '#0B0B0F', light: '#FFFFFF' } }).then(setQr);
      session.done.then(onDone).catch((err) => setError(errorText(err)));
    }).catch((err) => setError(errorText(err)));
    return () => active?.cancel();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);
  if (error) return <div className="aura-banner" data-error="true">{error}</div>;
  if (!link) return <div className="aura-muted">Preparing a link code…</div>;
  return (
    <div style={{ display: 'grid', gap: 12, justifyItems: 'center', textAlign: 'center' }}>
      {qr && <img src={qr} width={240} height={240} alt={`QR code for link code ${link.linkId}`} style={{ borderRadius: 16 }} />}
      <div className="type-body-medium aura-muted">Scan with your phone's camera, or in Moodify on your phone open<br /><b>Settings › Your Moodify code › Link a browser</b> and type:</div>
      <div className="type-headline-medium aura-mono" style={{ letterSpacing: '0.12em' }}>{link.linkId.slice(0, 4)}-{link.linkId.slice(4)}</div>
      <div className="aura-banner">Check your phone shows the same two words: <b>{link.words.join(' · ')}</b></div>
      <div className="type-body-small aura-muted">Waiting for your phone…</div>
    </div>
  );
}

function RecoveryCard({ words, onConfirmed }: { words: string[]; onConfirmed: () => void }) {
  const [checking, setChecking] = useState(false);
  const [positions] = useState(() => [...Array(24).keys()].sort(() => Math.random() - 0.5).slice(0, 2).sort((a, b) => a - b));
  const [answers, setAnswers] = useState(['', '']);
  const correct = positions.every((p, i) => answers[i].trim().toLowerCase() === words[p]);
  return (
    <div className="aura-card" style={{ display: 'grid', gap: 12 }}>
      <div className="type-title-large">Your recovery key</div>
      <div className="type-body-medium aura-muted">These 24 words are the only way back into your account on a new device. Write them down and keep them private: anyone with them can be you.</div>
      {!checking ? (
        <>
          <div className="aura-words">{words.map((w, i) => <div key={i} className="aura-word aura-mono">{i + 1}. {w}</div>)}</div>
          <button className="aura-btn aura-btn-primary" onClick={() => setChecking(true)}>I've written them down</button>
        </>
      ) : (
        <>
          <div className="type-title-small">Quick check: which words were these?</div>
          {positions.map((p, i) => (
            <input key={p} className="aura-field" placeholder={`Word ${p + 1}`} value={answers[i]} autoCapitalize="none" autoCorrect="off" spellCheck={false}
              onChange={(e) => setAnswers((a) => a.map((v, j) => (j === i ? e.target.value : v)))} />
          ))}
          <button className="aura-btn aura-btn-primary" disabled={!correct} onClick={onConfirmed}><Check size={18} />Done</button>
          <button className="aura-btn-text" onClick={() => setChecking(false)}>Show the words again</button>
        </>
      )}
    </div>
  );
}

function ManageCard({ onSay }: { onSay: (m: string) => void }) {
  const social = useSocial();
  const [words, setWords] = useState<string[] | null>(null);
  return (
    <div className="aura-card" style={{ display: 'grid', gap: 8 }}>
      <div className="type-title-medium">Account</div>
      <div className="aura-row">
        <div style={{ flex: 1 }}><div>Display name</div><div className="type-body-small aura-muted">{social.displayName}</div></div>
        <button className="aura-btn-text" onClick={async () => { const name = prompt('Display name', social.displayName); if (name && name.trim().length >= 2) { try { await social.rename(name.trim()); } catch (e) { onSay(errorText(e)); } } }}>Change</button>
      </div>
      <div className="aura-row">
        <div style={{ flex: 1 }}><div>Recovery key</div><div className="type-body-small aura-muted">{social.backedUp ? 'Saved by you' : 'Not confirmed yet'}</div></div>
        <button className="aura-btn-text" onClick={async () => { if (words) setWords(null); else if (confirm('Make sure no one can see your screen. Show the recovery key?')) setWords(await social.recoveryWords()); }}>{words ? 'Hide' : 'Show'}</button>
      </div>
      {words && <div className="aura-words">{words.map((w, i) => <div key={i} className="aura-word aura-mono">{i + 1}. {w}</div>)}</div>}
      <div className="aura-row">
        <div style={{ flex: 1 }}><div>Delete account</div><div className="type-body-small aura-muted">Removes your code from Moodify</div></div>
        <button className="aura-btn-text" style={{ color: 'var(--aura-error)' }} onClick={async () => {
          if (!confirm('Delete your account? Your code, name and friends are removed right away.')) return;
          try { await social.deleteAccount(); onSay('Your account was deleted.'); } catch (e) { onSay(errorText(e)); }
        }}>Delete</button>
      </div>
    </div>
  );
}

// ── Friends ──────────────────────────────────────────────────────────────────

export function FriendsScreen() {
  const social = useSocial();
  const player = usePlayer();
  const rooms = useRoom();
  const navigate = useNavigate();
  const [adding, setAdding] = useState(false);
  const [menuFor, setMenuFor] = useState<SocialUser | null>(null);
  const [showQr, setShowQr] = useState(false);
  const { toast, say } = useToast();
  const act = async (work: () => Promise<unknown>, ok?: string) => { try { await work(); if (ok) say(ok); } catch (e) { say(errorText(e)); } };

  if (!social.ready) return <div className="aura-page">Loading…</div>;
  if (!social.code) {
    return (
      <div className="aura-page">
        <h1 className="type-display-small">Friends</h1>
        <EmptyState icon={<Users />} title="Listen with your friends" message="See what friends are playing and send each other songs. No phone number or email: just a Moodify code."
          action={<Link to="/account" className="aura-btn aura-btn-primary" style={{ marginTop: 12 }}>Get your code</Link>} />
      </div>
    );
  }
  const listening = social.lists.friends.filter((f) => social.presence[f.code]?.online && social.presence[f.code]?.playing && social.presence[f.code]?.track);
  const status = (f: SocialUser) => {
    const p = social.presence[f.code];
    if (!p) return 'Offline';
    if (p.online && p.playing && p.track) return `Listening to ${p.track.title}`;
    if (p.online) return 'Online';
    if (p.track && p.lastSeenAt) return `Played ${p.track.title} · ${timeAgo(p.lastSeenAt)}`;
    if (p.lastSeenAt) return `Last seen ${timeAgo(p.lastSeenAt)}`;
    return 'Offline';
  };
  return (
    <div className="aura-page">
      <div className="aura-glow" />
      <div className="aura-row">
        <h1 className="type-display-small" style={{ margin: 0, flex: 1 }}>Friends</h1>
        <button className="aura-btn aura-btn-primary" onClick={() => setAdding(true)}><UserPlus size={18} />Add</button>
      </div>
      <Link to="/account" className="aura-card aura-row" style={{ marginTop: 16 }}>
        <div style={{ flex: 1 }}>
          <div className="type-label-medium aura-muted">Your code</div>
          <div className="type-title-large aura-mono" style={{ color: 'var(--aura-primary)' }}>{social.code}</div>
          <div className="type-body-small aura-muted">{social.connected ? 'Connected' : social.serverConfigured ? 'Connecting…' : 'Friends go live soon.'}</div>
        </div>
        <button className="aura-icon-btn" aria-label="Show your code as a QR" onClick={(e) => { e.preventDefault(); setShowQr(true); }}><QrCode size={20} /></button>
        <Link2 size={20} />
      </Link>
      {showQr && social.code && <CodeQrDialog code={social.code} name={social.displayName} onClose={() => setShowQr(false)} />}

      {social.lists.incoming.length > 0 && (
        <section className="aura-section">
          <SectionHeader title="Requests" />
          {social.lists.incoming.map((u) => (
            <div key={u.code} className="aura-track">
              <Avatar user={u} />
              <div className="aura-track-text"><div className="type-title-medium">{u.displayName}</div><div className="type-body-small aura-muted">{u.note ? `${u.note} · ${u.code}` : u.code}</div></div>
              <button className="aura-btn-text" onClick={() => act(() => social.answer(u.code, false))}>Ignore</button>
              <button className="aura-btn aura-btn-primary" style={{ height: 40 }} onClick={() => act(() => social.answer(u.code, true), `You and ${u.displayName} are now friends.`)}>Accept</button>
            </div>
          ))}
        </section>
      )}

      {listening.length > 0 && (
        <section className="aura-section">
          <SectionHeader title="Listening now" />
          {listening.map((f) => {
            const track = social.presence[f.code].track!;
            return (
              <div key={f.code} className="aura-card aura-row" style={{ marginBottom: 8 }}>
                <Artwork src={track.albumArt} size={60} radius="var(--radius-md)" placeholder={track.id} />
                <div className="aura-track-text">
                  <div className="type-label-large" style={{ color: 'var(--aura-primary)' }}>{f.displayName}</div>
                  <div className="type-title-medium aura-ellipsis">{track.title}</div>
                  <div className="type-body-small aura-muted aura-ellipsis">{track.artist}</div>
                </div>
                <div style={{ display: 'grid', gap: 4, justifyItems: 'end' }}>
                  <button className="aura-btn aura-btn-tonal" onClick={() => { rooms.followFriend(f); navigate('/room'); }}>Follow</button>
                  <button className="aura-btn-text" onClick={() => player.playTrack(track, [track])}><Play size={14} fill="currentColor" />Just play</button>
                </div>
              </div>
            );
          })}
        </section>
      )}

      <section className="aura-section">
        <SectionHeader title={social.lists.friends.length ? `Friends · ${social.lists.friends.length}` : 'Friends'} />
        {social.lists.friends.length === 0 && social.lists.outgoing.length === 0 && <div className="aura-muted">Add a friend with their Moodify code, or share yours.</div>}
        {(() => {
          // One list: friends you've talked to first (newest chat on top), then the rest.
          const chats = new Map(social.conversations.map((c) => [c.peer, c]));
          const sameNames = new Set(Object.entries(social.lists.friends.reduce<Record<string, number>>((n, f) => {
            const k = f.displayName.trim().toLowerCase(); n[k] = (n[k] ?? 0) + 1; return n;
          }, {})).filter(([, n]) => n > 1).map(([k]) => k));
          const ordered = [...social.lists.friends].sort((a, b) => (chats.get(b.code)?.last.sentAt ?? 0) - (chats.get(a.code)?.last.sentAt ?? 0) || a.displayName.localeCompare(b.displayName));
          return ordered.map((f) => {
            const chat = chats.get(f.code);
            const unread = chat?.unread ?? 0;
            const p = social.presence[f.code];
            const listeningNow = !!(p?.online && p.playing && p.track);
            const subtitle = chat && (unread > 0 || !listeningNow) ? `${chat.last.fromMe ? 'You: ' : ''}${preview(chat.last)}` : status(f);
            return (
              <div key={f.code} className="aura-track">
                <button className="aura-row" style={{ flex: 1, minWidth: 0, textAlign: 'left' }} onClick={() => navigate(`/chat/${f.code}`)}>
                  <Avatar user={f} online={!!p?.online} />
                  <span className="aura-track-text">
                    <span className="type-title-medium aura-ellipsis" style={{ display: 'block', fontWeight: unread ? 700 : undefined }}>
                      {f.displayName}{sameNames.has(f.displayName.trim().toLowerCase()) && <span className="type-label-medium aura-mono aura-muted"> · {f.code.slice(-4)}</span>}
                    </span>
                    <span className="type-body-small aura-ellipsis" style={{ display: 'block', color: unread ? 'var(--aura-on-background)' : 'var(--aura-muted)', fontWeight: unread ? 600 : undefined }}>{subtitle}</span>
                  </span>
                  {chat && (
                    <span style={{ display: 'grid', justifyItems: 'end', gap: 4 }}>
                      <span className="type-label-small aura-muted">{timeAgo(chat.last.sentAt)}</span>
                      {unread > 0 && <span className="aura-badge">{unread}</span>}
                    </span>
                  )}
                </button>
                <button className="aura-icon-btn" aria-label={`Options for ${f.displayName}`} onClick={() => setMenuFor(f)}><MoreVertical size={20} /></button>
              </div>
            );
          });
        })()}
        {social.lists.outgoing.map((f) => (
          <div key={f.code} className="aura-track">
            <Avatar user={f} />
            <span className="aura-track-text"><span className="type-title-medium" style={{ display: 'block' }}>{f.displayName}</span><span className="type-body-small aura-muted" style={{ display: 'block' }}>Request sent · waiting for them</span></span>
            <button className="aura-icon-btn" aria-label={`Options for ${f.displayName}`} onClick={() => setMenuFor(f)}><MoreVertical size={20} /></button>
          </div>
        ))}
        {social.conversations.filter((c) => !social.lists.friends.some((f) => f.code === c.peer)).map((c) => (
          <Link key={c.peer} to={`/chat/${c.peer}`} className="aura-track">
            <Avatar user={{ code: c.peer, displayName: '?' }} />
            <span className="aura-track-text"><span className="type-title-medium" style={{ display: 'block' }}>Former friend · {c.peer.slice(-4)}</span><span className="type-body-small aura-muted aura-ellipsis" style={{ display: 'block' }}>{preview(c.last)}</span></span>
          </Link>
        ))}
      </section>

      {adding && <AddFriendDialog onClose={() => setAdding(false)} onAdd={(code, note) => act(async () => say(await social.addFriend(code, note)))} />}
      {menuFor && (
        <div className="aura-dialog-backdrop" onClick={() => setMenuFor(null)}>
          <div className="aura-dialog" role="dialog" aria-label={`Options for ${menuFor.displayName}`} onClick={(e) => e.stopPropagation()}>
            <div className="aura-row"><Avatar user={menuFor} size={52} /><div><div className="type-title-large">{menuFor.displayName}</div><div className="aura-mono aura-muted">{menuFor.code}</div></div></div>
            <button className="aura-btn aura-btn-tonal" onClick={() => { if (confirm(`Remove ${menuFor.displayName}?`)) void act(() => social.remove(menuFor.code), `Removed ${menuFor.displayName}.`); setMenuFor(null); }}>Remove friend</button>
            <button className="aura-btn aura-btn-tonal" style={{ color: 'var(--aura-error)' }} onClick={() => { if (confirm(`Block ${menuFor.displayName}? They won't be told.`)) void act(() => social.block(menuFor.code), `Blocked ${menuFor.displayName}.`); setMenuFor(null); }}>Block</button>
          </div>
        </div>
      )}
      {toast}
    </div>
  );
}

function AddFriendDialog({ onClose, onAdd }: { onClose: () => void; onAdd: (code: string, note: string) => void }) {
  const [code, setCode] = useState('');
  const [note, setNote] = useState('');
  return (
    <div className="aura-dialog-backdrop" onClick={onClose}>
      <form className="aura-dialog" role="dialog" aria-label="Add a friend" onClick={(e) => e.stopPropagation()} onSubmit={(e) => { e.preventDefault(); onAdd(code, note); onClose(); }}>
        <div className="type-title-large">Add a friend</div>
        <input className="aura-field aura-mono" autoFocus placeholder="MOOD-XXXX-XXXX" value={code} maxLength={20} onChange={(e) => setCode(e.target.value.toUpperCase())} aria-label="Their Moodify code" />
        <input className="aura-field" placeholder="Note (optional)" value={note} maxLength={60} onChange={(e) => setNote(e.target.value)} aria-label="Note" />
        <div className="aura-row" style={{ justifyContent: 'flex-end' }}>
          <button type="button" className="aura-btn-text" onClick={onClose}>Cancel</button>
          <button type="submit" className="aura-btn aura-btn-primary" disabled={code.replace(/[^0-9A-Z]/g, '').replace(/^MOOD/, '').length !== 8}>Send request</button>
        </div>
      </form>
    </div>
  );
}

/** Your code as a QR: a friend scans it in Moodify on their phone (Friends › Add › Scan). */
function CodeQrDialog({ code, name, onClose }: { code: string; name: string; onClose: () => void }) {
  const [qr, setQr] = useState('');
  useEffect(() => { void QRCode.toDataURL(`moodify://add/${code}`, { margin: 1, width: 260, color: { dark: '#0B0B0F', light: '#FFFFFF' } }).then(setQr); }, [code]);
  return (
    <div className="aura-dialog-backdrop" onClick={onClose}>
      <div className="aura-dialog" role="dialog" aria-label="Your Moodify code" onClick={(e) => e.stopPropagation()} style={{ justifyItems: 'center', textAlign: 'center' }}>
        <div className="type-title-large">{name}</div>
        <div className="type-title-medium aura-mono" style={{ color: 'var(--aura-primary)' }}>{code}</div>
        {qr && <img src={qr} width={260} height={260} alt={`QR code for ${code}`} style={{ borderRadius: 16 }} />}
        <div className="type-body-medium aura-muted">Your friend scans this in Moodify on their phone: Friends › Add › Scan.</div>
        <button className="aura-btn aura-btn-tonal" onClick={onClose}>Done</button>
      </div>
    </div>
  );
}

/** Pick a friend to send one of your playlists to (end-to-end encrypted, like chat). */
export function SendPlaylistDialog({ name, tracks, onClose }: { name: string; tracks: Track[]; onClose: () => void }) {
  const social = useSocial();
  const { toast, say } = useToast();
  const send = async (friend: SocialUser) => {
    try { await social.sendPlaylist(friend, name, tracks); onClose(); } catch (e) { say(errorText(e)); }
  };
  return (
    <div className="aura-dialog-backdrop" onClick={onClose}>
      <div className="aura-dialog" role="dialog" aria-label={`Send ${name}`} onClick={(e) => e.stopPropagation()}>
        <div className="type-title-large">Send “{name}”</div>
        <div className="type-body-small aura-muted">End-to-end encrypted: only your friend can see it.</div>
        {!social.code ? <Link to="/account" className="aura-btn aura-btn-primary">Get your code</Link>
          : social.lists.friends.length === 0 ? <div className="aura-muted">Add friends on the Friends page first.</div>
            : social.lists.friends.map((f) => (
              <button key={f.code} className="aura-track" onClick={() => void send(f)}>
                <Avatar user={f} />
                <span className="type-title-medium">{f.displayName}</span>
              </button>
            ))}
        <button className="aura-btn-text" onClick={onClose}>Cancel</button>
        {toast}
      </div>
    </div>
  );
}
