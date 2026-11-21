/**
 * Links Moodify shares. They point at the public web app so they work for anyone;
 * on Android they open straight in the app when it's installed (App Links, verified
 * by public/.well-known/assetlinks.json). Keep in step with Android's MoodifyLinks.kt.
 */
export const PUBLIC_ORIGIN = 'https://moodify-v2-two.vercel.app';
export const APK_URL = 'https://github.com/khageshpatil/Moodify-v2/releases/latest';

export const inviteLink = (code: string) => `${PUBLIC_ORIGIN}/add/${code}`;
export const roomLink = (roomId: string) => `${PUBLIC_ORIGIN}/room/${roomId}`;

export const isAndroid = () => typeof navigator !== 'undefined' && /Android/i.test(navigator.userAgent);

/**
 * Opens [path] (e.g. "add/MOOD-…") in the Android app as moodify://[path]. For app versions
 * from before App Links, and browsers that keep links to themselves. Without the app,
 * Chrome goes to the download page instead.
 */
export const appIntentLink = (path: string) =>
  `intent://${path.replace(/^\/+/, '')}#Intent;scheme=moodify;package=com.moodify.app;S.browser_fallback_url=${encodeURIComponent(APK_URL)};end`;
