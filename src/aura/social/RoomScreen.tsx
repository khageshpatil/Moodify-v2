import { Radio, Send, Share2, SkipForward, Users } from 'lucide-react';
import { useEffect, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { usePlayer } from '../PlayerContext';
import { Artwork, EmptyState, SectionHeader } from '../ui';
import { useRoom } from './RoomProvider';
import { normalizeRoomId } from './roomSync';
import { useSocial } from './SocialProvider';
import { Avatar, errorText, useToast } from './SocialScreens';

/** Listen together: start or join a room, see who's in it, what's next, and chat. */
export function RoomScreen() {
  const social = useSocial();
  const player = usePlayer();
  const rooms = useRoom();
  const room = rooms.room;
  const { id: linkedId } = useParams();
  const navigate = useNavigate();
  const [code, setCode] = useState('');
  const [message, setMessage] = useState('');
  const [inviting, setInviting] = useState(false);
  const { toast, say } = useToast();

  // /room/K7P2QX joins that room (invite links on the web).
  useEffect(() => {
    if (social.code && linkedId && normalizeRoomId(linkedId)) {
      rooms.join(linkedId);
      navigate('/room', { replace: true });
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [linkedId, social.code]);

  if (!social.ready) return <div className="aura-page">Loading…</div>;
  if (!social.code) {
    return (
      <div className="aura-page">
        <h1 className="type-display-small">Listen together</h1>
        <EmptyState icon={<Users />} title="Get your Moodify code" message="Start a room with friends: everyone hears the same song at the same moment."
          action={<Link to="/account" className="aura-btn aura-btn-primary" style={{ marginTop: 12 }}>Get your code</Link>} />
      </div>
    );
  }

  if (!room) {
    return (
      <div className="aura-page">
        <div className="aura-glow" />
        <h1 className="type-display-small">Listen together</h1>
        <p className="type-body-medium aura-muted">Start a room and invite friends. Everyone hears the same song at the same moment, on their own device.</p>
        <button className="aura-btn aura-btn-primary" style={{ width: '100%', marginTop: 8 }}
          onClick={async () => { try { await rooms.start(); } catch (e) { say(errorText(e)); } }}>
          <Radio size={18} />{player.currentTrack ? 'Start a room with this song' : 'Start a room'}
        </button>
        <section className="aura-section">
          <SectionHeader title="Have a room code?" />
          <form className="aura-row" onSubmit={(e) => { e.preventDefault(); if (normalizeRoomId(code)) rooms.join(code); }}>
            <input className="aura-field aura-mono" style={{ flex: 1 }} value={code} placeholder="K7P2QX" aria-label="Room code"
              onChange={(e) => setCode(e.target.value.toUpperCase().replace(/[^0-9A-Z]/g, '').slice(0, 6))} />
            <button className="aura-btn aura-btn-tonal" disabled={!normalizeRoomId(code)}>Join</button>
          </form>
        </section>
        {toast}
      </div>
    );
  }

  if (room.following) {
    const name = room.following.name;
    return (
      <div className="aura-page" style={{ maxWidth: 640 }}>
        <div className="aura-glow" />
        <h1 className="type-display-small">Following {name}</h1>
        <p className="type-body-medium aura-muted">{room.error ?? `Your player mirrors ${name}'s: the same song at the same moment. Play or pause yourself to stop.`}</p>
        {rooms.needsTap && <button className="aura-btn aura-btn-primary" style={{ width: '100%' }} onClick={rooms.unblock}>Tap to start listening</button>}
        {room.track && (
          <div className="aura-card aura-row" style={{ marginTop: 16 }}>
            <Artwork src={room.track.albumArt} size={64} radius="var(--radius-md)" placeholder={room.track.id} />
            <div className="aura-track-text">
              <div className="type-title-medium aura-ellipsis">{room.track.title}</div>
              <div className="type-body-small aura-muted aura-ellipsis">{room.track.artist}{room.playing ? '' : ' · paused'}</div>
            </div>
          </div>
        )}
        <button className="aura-btn aura-btn-tonal" style={{ width: '100%', marginTop: 16 }} onClick={() => rooms.leave()}>Stop following</button>
        {toast}
      </div>
    );
  }

  const status = !room.connected ? room.error ?? 'Connecting…' : room.role === 'host' ? "You're the host" : room.role === 'dj' ? "You're a DJ" : 'Following the host';
  const controls = room.role === 'host' || room.role === 'dj';
  const inRoom = new Set(room.members.map((m) => m.code));
  const sendChat = () => { if (message.trim()) { rooms.chat(message); setMessage(''); } };
  const shareLink = async () => {
    const url = `${window.location.origin}/room/${room.roomId}`;
    try {
      if (navigator.share) await navigator.share({ title: 'Listen with me on Moodify', url });
      else { await navigator.clipboard.writeText(url); say('Room link copied'); }
    } catch { /* cancelled */ }
  };

  return (
    <div className="aura-page">
      <div className="aura-glow" />
      <div className="aura-row">
        <div style={{ flex: 1 }}>
          <h1 className="type-display-small" style={{ margin: 0 }}>Listening room</h1>
          <div className="type-body-small" style={{ color: room.error && !room.connected ? 'var(--aura-error, #f87171)' : undefined, opacity: 0.75 }}>
            <span className="aura-mono">{room.roomId.slice(0, 3)} {room.roomId.slice(3)}</span> · {status}
          </div>
        </div>
        <button className="aura-icon-btn" aria-label="Share room link" onClick={shareLink}><Share2 size={20} /></button>
      </div>

      {rooms.needsTap && (
        <button className="aura-btn aura-btn-primary" style={{ width: '100%', marginTop: 16 }} onClick={rooms.unblock}>
          <Radio size={18} />Tap to start listening
        </button>
      )}

      <div className="aura-card aura-row" style={{ marginTop: 16 }}>
        {room.track ? (
          <>
            <Artwork src={room.track.albumArt} size={60} radius="var(--radius-md)" placeholder={room.track.id} />
            <div className="aura-track-text">
              <div className="type-label-medium" style={{ color: 'var(--aura-primary)' }}>{room.playing ? 'Playing for everyone' : 'Paused'}</div>
              <div className="type-title-medium aura-ellipsis">{room.track.title}</div>
              <div className="type-body-small aura-muted aura-ellipsis">{room.track.artist}</div>
            </div>
            {controls && room.queue.length > 0 && <button className="aura-icon-btn" aria-label="Play next in room" onClick={rooms.next}><SkipForward size={22} /></button>}
          </>
        ) : (
          <div className="aura-muted">{controls ? 'Play any song and everyone in the room will hear it.' : 'Waiting for the host to play something.'}</div>
        )}
      </div>

      {room.queue.length > 0 && (
        <section className="aura-section">
          <SectionHeader title={`Up next · ${room.queue.length}`} />
          {room.queue.slice(0, 10).map((t, i) => (
            <div key={`${t.id}-${i}`} className="aura-track">
              <Artwork src={t.albumArt} size={44} placeholder={t.id} />
              <div className="aura-track-text"><div className="type-title-small aura-ellipsis">{t.title}</div><div className="type-body-small aura-muted aura-ellipsis">{t.artist}</div></div>
            </div>
          ))}
        </section>
      )}

      {controls && room.suggestions.length > 0 && (
        <section className="aura-section">
          <SectionHeader title="Suggested" />
          {room.suggestions.slice(-5).reverse().map(({ displayName, track }, i) => (
            <div key={`${track.id}-${i}`} className="aura-track">
              <Artwork src={track.albumArt} size={44} placeholder={track.id} />
              <div className="aura-track-text"><div className="type-title-small aura-ellipsis">{track.title}</div><div className="type-body-small aura-muted aura-ellipsis">{track.artist} · from {displayName}</div></div>
              <button className="aura-btn-text" onClick={() => rooms.queue(track)}>Add</button>
            </div>
          ))}
        </section>
      )}

      <section className="aura-section">
        <SectionHeader title={`In the room · ${room.members.length}`} />
        {room.members.map((m) => (
          <div key={m.code} className="aura-track">
            <Avatar user={m} size={40} />
            <div className="aura-track-text">
              <div className="type-title-small">{m.displayName}{m.code === room.you ? ' (you)' : ''}</div>
              <div className="type-body-small aura-muted">{m.role === 'host' ? 'Host' : m.role === 'dj' ? 'DJ' : 'Listener'}</div>
            </div>
            {room.role === 'host' && m.code !== room.you && (
              <>
                {m.role !== 'dj' && <button className="aura-btn-text" onClick={() => rooms.setRole(m.code, 'dj')}>Make DJ</button>}
                {m.role === 'dj' && <button className="aura-btn-text" onClick={() => rooms.setRole(m.code, 'listener')}>Make listener</button>}
                <button className="aura-btn-text" onClick={() => rooms.setRole(m.code, 'host')}>Make host</button>
              </>
            )}
          </div>
        ))}
        <button className="aura-btn aura-btn-tonal" style={{ width: '100%', marginTop: 8 }} onClick={() => setInviting((v) => !v)}>{inviting ? 'Done inviting' : 'Invite friends'}</button>
        {inviting && (
          <div style={{ marginTop: 8 }}>
            {social.lists.friends.filter((f) => !inRoom.has(f.code)).length === 0 && <div className="aura-muted">All your friends are here, or you haven't added any yet.</div>}
            {social.lists.friends.filter((f) => !inRoom.has(f.code)).map((f) => (
              <div key={f.code} className="aura-track">
                <Avatar user={f} size={36} />
                <div className="aura-track-text type-title-small">{f.displayName}</div>
                <button className="aura-btn aura-btn-primary" style={{ height: 36 }}
                  onClick={async () => { try { await rooms.invite(f); say(`Invited ${f.displayName}`); } catch (e) { say(errorText(e)); } }}>Invite</button>
              </div>
            ))}
          </div>
        )}
      </section>

      <section className="aura-section">
        <SectionHeader title="Chat" />
        {room.chat.length === 0 && <div className="type-body-small aura-muted">Say hi. Only people in the room right now see this. Room chat isn't end-to-end encrypted and isn't saved.</div>}
        {room.chat.slice(-30).map((line, i) => (
          <div key={i} className="type-body-medium" style={{ padding: '2px 0' }}><strong>{line.from === room.you ? 'You' : line.displayName}:</strong> {line.text}</div>
        ))}
        <form className="aura-row" style={{ marginTop: 8 }} onSubmit={(e) => { e.preventDefault(); sendChat(); }}>
          <input className="aura-field" style={{ flex: 1 }} value={message} maxLength={300} placeholder="Message the room" aria-label="Message the room" onChange={(e) => setMessage(e.target.value)} />
          <button className="aura-icon-btn" aria-label="Send" disabled={!message.trim()}><Send size={20} /></button>
        </form>
      </section>

      <button className="aura-btn-text" style={{ width: '100%', marginTop: 24, color: 'var(--aura-error, #f87171)' }} onClick={rooms.leave}>Leave room</button>
      {toast}
    </div>
  );
}
