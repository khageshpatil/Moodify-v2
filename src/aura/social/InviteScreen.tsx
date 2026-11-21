import { normalizeCode } from '@social/identity.mjs';
import { Check, Copy, Download, MessageCircle, Share2, UserPlus, Users } from 'lucide-react';
import { useEffect, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { APK_URL, appIntentLink, inviteLink, isAndroid } from '../links';
import { EmptyState } from '../ui';
import { useSocial, type SocialUser } from './SocialProvider';
import { Avatar, errorText, useToast } from './SocialScreens';

/**
 * On Android: get the app, or open this link in the app already installed (versions
 * from before App Links don't take these links over by themselves).
 */
export function GetTheApp({ path }: { path: string }) {
  if (!isAndroid()) return null;
  return (
    <div style={{ display: 'grid', gap: 4 }}>
      <a className="aura-btn aura-btn-primary" href={APK_URL} target="_blank" rel="noreferrer"><Download size={18} />Get the Android app</a>
      <a className="aura-btn-text" href={appIntentLink(path)}>Already have it? Open in the app</a>
    </div>
  );
}

/** /add/MOOD-…: a friend's invite link, for people with or without Moodify. */
export function InviteScreen() {
  const { code: raw = '' } = useParams();
  const code = normalizeCode(raw);
  const social = useSocial();

  if (!social.ready) return <div className="aura-page">Loading…</div>;
  return (
    <div className="aura-page" style={{ maxWidth: 560 }}>
      <div className="aura-glow" />
      {!code ? (
        <EmptyState icon={<Users />} title="This invite link is broken" message="Part of the code is missing. Ask your friend to send the link again."
          action={<Link to="/friends" className="aura-btn aura-btn-tonal" style={{ marginTop: 12 }}>Go to Friends</Link>} />
      ) : code === social.code ? (
        <OwnLink code={code} />
      ) : social.code ? (
        <AddThem code={code} />
      ) : (
        <Newcomer code={code} />
      )}
    </div>
  );
}

/** No code yet: get the app, or make a code here and carry on. */
function Newcomer({ code }: { code: string }) {
  const social = useSocial();
  const [name, setName] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const create = async () => {
    setBusy(true); setError(null);
    // Once the code exists this page shows the friend to add.
    try { await social.create(name); } catch (err) { setError(errorText(err)); } finally { setBusy(false); }
  };
  return (
    <div style={{ display: 'grid', gap: 16 }}>
      <div className="aura-card" style={{ display: 'grid', gap: 12 }}>
        <h1 className="type-headline-small" style={{ margin: 0 }}>You're invited to Moodify</h1>
        <div className="type-body-medium aura-muted">
          A friend wants to add you. See what each other is playing, send songs and listen together.
          No phone number or email, just a Moodify code.
        </div>
        <div>
          <div className="type-label-medium aura-muted">Their code</div>
          <div className="type-title-large aura-mono" style={{ color: 'var(--aura-primary)' }}>{code}</div>
        </div>
        <GetTheApp path={`add/${code}`} />
      </div>
      <form className="aura-card" style={{ display: 'grid', gap: 12 }} onSubmit={(e) => { e.preventDefault(); void create(); }}>
        <div className="type-title-medium">{isAndroid() ? 'Or use Moodify on the web' : 'Use Moodify on the web'}</div>
        <input className="aura-field" value={name} maxLength={24} onChange={(e) => setName(e.target.value)} placeholder="Your name, as friends will see it" aria-label="Your name" />
        <button type="submit" className="aura-btn aura-btn-primary" disabled={busy || name.trim().length < 2}>{busy ? 'Creating…' : 'Create my code'}</button>
        {error && <div className="aura-banner" data-error="true">{error}</div>}
        <div className="type-body-small aura-muted">
          Already have a code? <Link className="aura-link" style={{ color: 'var(--aura-primary)' }} to={`/account?mode=link&next=${encodeURIComponent(`/add/${code}`)}`}>Use it on this browser</Link>.
          {' '}Friends features are for people 13 and older. See <Link className="aura-link" style={{ color: 'var(--aura-primary)' }} to="/privacy">Privacy</Link>.
        </div>
      </form>
    </div>
  );
}

/** Signed in: who this is, and the one thing to do next. */
function AddThem({ code }: { code: string }) {
  const social = useSocial();
  const navigate = useNavigate();
  const { toast, say } = useToast();
  const friend = social.lists.friends.find((f) => f.code === code);
  const incoming = social.lists.incoming.find((f) => f.code === code);
  const outgoing = social.lists.outgoing.find((f) => f.code === code);
  const known = friend ?? incoming ?? outgoing;
  // undefined while looking them up, null when no one has the code.
  const [looked, setLooked] = useState<SocialUser | null | undefined>(undefined);
  const [error, setError] = useState<string | null>(null);
  const [note, setNote] = useState('');
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (known) return;
    let live = true;
    setError(null);
    social.lookup(code).then((user) => { if (live) setLooked(user); }).catch((err) => { if (live) setError(errorText(err)); });
    return () => { live = false; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [code, !!known]);

  const act = async (work: () => Promise<unknown>) => {
    setBusy(true);
    try { const result = await work(); if (typeof result === 'string') say(result); } catch (err) { say(errorText(err)); } finally { setBusy(false); }
  };
  const person = known ?? looked;

  if (!person) {
    if (error) return <div className="aura-banner" data-error="true">{error}</div>;
    if (person === undefined) return <div className="aura-muted">Looking up {code}…</div>;
    return (
      <EmptyState icon={<Users />} title="No one has this code" message="The link may be old: a code stops working when its owner deletes their account. Ask your friend for their code."
        action={<Link to="/friends" className="aura-btn aura-btn-tonal" style={{ marginTop: 12 }}>Go to Friends</Link>} />
    );
  }

  return (
    <div style={{ display: 'grid', gap: 16 }}>
      <div className="aura-card" style={{ display: 'grid', gap: 16 }}>
        <div className="aura-row">
          <Avatar user={person} size={64} />
          <div style={{ minWidth: 0 }}>
            <h1 className="type-headline-small aura-ellipsis" style={{ margin: 0 }}>{person.displayName}</h1>
            <div className="aura-mono aura-muted">{code}</div>
          </div>
        </div>
        {friend ? (
          <>
            <div className="type-body-medium aura-muted">You're friends.</div>
            <button className="aura-btn aura-btn-primary" onClick={() => navigate(`/chat/${code}`)}><MessageCircle size={18} />Message</button>
          </>
        ) : outgoing ? (
          <>
            <div className="type-body-medium aura-muted">Request sent. You'll be friends once they accept.</div>
            <Link to="/friends" className="aura-btn aura-btn-tonal">Go to Friends</Link>
          </>
        ) : incoming ? (
          <>
            <div className="type-body-medium aura-muted">{person.displayName} already asked to be your friend.</div>
            <div className="aura-row">
              <button className="aura-btn aura-btn-primary" style={{ flex: 1 }} disabled={busy} onClick={() => act(async () => { await social.answer(code, true); return `You and ${person.displayName} are now friends.`; })}><Check size={18} />Accept</button>
              <button className="aura-btn-text" disabled={busy} onClick={() => act(async () => { await social.answer(code, false); navigate('/friends'); })}>Ignore</button>
            </div>
          </>
        ) : (
          <form style={{ display: 'grid', gap: 12 }} onSubmit={(e) => { e.preventDefault(); void act(() => social.addFriend(code, note)); }}>
            <input className="aura-field" placeholder="Note (optional)" value={note} maxLength={60} onChange={(e) => setNote(e.target.value)} aria-label="Note" />
            <button type="submit" className="aura-btn aura-btn-primary" disabled={busy}><UserPlus size={18} />{busy ? 'Sending…' : 'Send friend request'}</button>
          </form>
        )}
      </div>
      {!social.backedUp && (
        <div className="aura-banner">
          Your code lives in this browser. <Link className="aura-link" style={{ color: 'var(--aura-primary)' }} to="/account">Save your recovery key</Link> so you don't lose it.
        </div>
      )}
      {toast}
    </div>
  );
}

/** Your own invite link: what it's for, and a way to send it. */
function OwnLink({ code }: { code: string }) {
  const { toast, say } = useToast();
  const link = inviteLink(code);
  const share = async () => {
    try {
      if (navigator.share) await navigator.share({ text: `Add me on Moodify: ${link}` });
      else { await navigator.clipboard.writeText(link); say('Link copied'); }
    } catch { /* cancelled */ }
  };
  return (
    <>
      <EmptyState icon={<Users />} title="This is your invite link" message="Send it to a friend. When they open it, they can add you, with or without the app."
        action={(
          <div className="aura-row" style={{ marginTop: 12 }}>
            <button className="aura-btn aura-btn-primary" onClick={() => void share()}>{'share' in navigator ? <Share2 size={18} /> : <Copy size={18} />}{'share' in navigator ? 'Share' : 'Copy link'}</button>
            <Link to="/friends" className="aura-btn aura-btn-tonal">Go to Friends</Link>
          </div>
        )} />
      {toast}
    </>
  );
}
