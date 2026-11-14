import { ArrowLeft } from 'lucide-react';
import type { ReactNode } from 'react';
import { useNavigate } from 'react-router-dom';

/** Moodify's privacy page (also opened from the Android app). Keep it true to what the code does. */
export const PRIVACY_UPDATED = '12 November 2026';

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="aura-section" style={{ marginTop: 28 }}>
      <h2 className="type-title-large" style={{ margin: '0 0 8px' }}>{title}</h2>
      <div className="type-body-large aura-prose">{children}</div>
    </section>
  );
}

export function PrivacyScreen() {
  const navigate = useNavigate();
  return (
    <div className="aura-page" style={{ maxWidth: 760 }}>
      <div className="aura-glow" />
      <button className="aura-icon-btn" aria-label="Back" onClick={() => (window.history.length > 1 ? navigate(-1) : navigate('/'))}><ArrowLeft size={22} /></button>
      <h1 className="type-display-small" style={{ margin: '8px 0 4px' }}>Privacy</h1>
      <div className="type-body-medium aura-muted">Last updated {PRIVACY_UPDATED}</div>

      <Section title="The short version">
        <p>Moodify is built to know as little about you as possible. There is no sign-up with a phone number or email, no contacts upload, no photos, no ads and no analytics or tracking. Your messages and synced library are end-to-end encrypted: we can't read them.</p>
        <p>Friends features are for people aged <b>13 and older</b>.</p>
      </Section>

      <Section title="Without a Moodify code">
        <p>If you just listen, your liked songs, playlists and history stay on your device. Songs, search, lyrics and artwork come from YouTube and YouTube Music: the Android app talks to them directly, and the web app plays through YouTube's embedded player. Search and browsing on the web go through Moodify's music server, which passes your search to YouTube Music and keeps no record of who searched. YouTube's own privacy policy applies to what it sees.</p>
      </Section>

      <Section title="What our server keeps when you make a Moodify code">
        <ul>
          <li><b>Your code, display name and picture</b> (an emoji on a colour, or a song's cover). Friends see these, and so does anyone you give your code to.</li>
          <li><b>Public keys</b> that prove you are you and let friends encrypt messages to you. Your private keys and 24 recovery words never leave your devices.</li>
          <li><b>Your friends, requests and blocks.</b></li>
          <li><b>Messages</b>, only as encrypted data we can't read, until all your devices have them, and never longer than 30 days.</li>
          <li><b>Your synced library</b> (likes, playlists, recently played), encrypted with a key from your recovery words. We store it but can't read it.</li>
          <li><b>Push addresses</b> for your devices, so we can wake the app when a message arrives. The wake-up itself contains no message, name or song.</li>
          <li><b>Reports</b> you send, with only the evidence you chose to include.</li>
        </ul>
      </Section>

      <Section title="What we don't keep">
        <ul>
          <li><b>What you're listening to.</b> If sharing is on, it goes to your friends live and is held in memory only, never written to disk. Turn it off in Settings and you appear offline.</li>
          <li><b>Listening rooms.</b> The room's song and queue live in memory while it's open and are deleted two minutes after everyone leaves.</li>
          <li><b>Your IP address.</b> To stop abuse, we keep a scrambled (keyed hash) form of it when a new code or browser link is made, for up to 24 hours, and then clear it. Our hosting provider, Cloudflare, handles connections and may keep short-lived logs of its own.</li>
        </ul>
      </Section>

      <Section title="Deleting your account">
        <p>In Settings › Your Moodify code › Delete account. This removes your code, name, picture, friendships, messages, synced library and push addresses from our server straight away. Reports about abuse are kept so we can act on them. Data on your own devices stays until you clear the app or browser data.</p>
      </Section>

      <Section title="Contact">
        <p>Questions or requests: open an issue at <a className="aura-link" style={{ color: 'var(--aura-primary)' }} href="https://github.com/khageshpatil/Moodify-v2/issues" target="_blank" rel="noreferrer">github.com/khageshpatil/Moodify-v2/issues</a>. Please don't post anything private there; say you'd like to be contacted privately.</p>
      </Section>
    </div>
  );
}
