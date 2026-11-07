/**
 * Wake-up pings (docs/social-protocol/chat.md §3). A ping carries no content,
 * only `{kind: "message"}`: the device wakes, fetches its encrypted inbox and
 * builds the notification itself. Google sees that a device got a ping, never
 * who sent it or what it says.
 *
 * Android: Firebase Cloud Messaging HTTP v1, signed with the service account
 * in the FCM_SERVICE_ACCOUNT secret (raw JSON, or base64 of it).
 */
import { base64UrlToBytes, bytesToBase64Url } from './identity.mjs';

const SCOPE = 'https://www.googleapis.com/auth/firebase.messaging';
const enc = new TextEncoder();
const b64url = (value) => bytesToBase64Url(typeof value === 'string' ? enc.encode(value) : new Uint8Array(value));

function parseServiceAccount(secret) {
  const text = secret.trim().startsWith('{') ? secret : new TextDecoder().decode(base64UrlToBytes(secret.trim().replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')));
  const account = JSON.parse(text);
  if (!account.client_email || !account.private_key || !account.project_id) throw new Error('FCM service account is missing fields');
  return account;
}

function pemToPkcs8(pem) {
  const body = pem.replace(/-----(BEGIN|END) PRIVATE KEY-----/g, '').replace(/\s+/g, '');
  return Uint8Array.from(atob(body), (c) => c.charCodeAt(0));
}

/** Signs a short-lived OAuth assertion and trades it for an access token (cached ~55 min). */
function accessTokens(account, fetchImpl) {
  let cached = null;
  let key = null;
  return async () => {
    const now = Math.floor(Date.now() / 1000);
    if (cached && cached.expiresAt > now + 60) return cached.token;
    key ??= await crypto.subtle.importKey('pkcs8', pemToPkcs8(account.private_key), { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' }, false, ['sign']);
    const tokenUri = account.token_uri || 'https://oauth2.googleapis.com/token';
    const unsigned = `${b64url(JSON.stringify({ alg: 'RS256', typ: 'JWT' }))}.${b64url(JSON.stringify({ iss: account.client_email, scope: SCOPE, aud: tokenUri, iat: now, exp: now + 3600 }))}`;
    const signature = await crypto.subtle.sign('RSASSA-PKCS1-v1_5', key, enc.encode(unsigned));
    const response = await fetchImpl(tokenUri, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({ grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer', assertion: `${unsigned}.${b64url(signature)}` }),
    });
    if (!response.ok) throw new Error(`FCM auth failed (${response.status})`);
    const data = await response.json();
    cached = { token: data.access_token, expiresAt: now + (Number(data.expires_in) || 3600) };
    return cached.token;
  };
}

/**
 * A sender for `createApi({ push })`: `push({ platform, token }, data)` resolves
 * to "ok", "gone" (forget this token) or "error" (try again next time).
 */
export function fcmPush(secret, fetchImpl = fetch) {
  const account = parseServiceAccount(secret);
  const token = accessTokens(account, fetchImpl);
  const endpoint = `https://fcm.googleapis.com/v1/projects/${account.project_id}/messages:send`;
  return async function push(target, data) {
    if (target.platform !== 'fcm') return 'error';
    try {
      const response = await fetchImpl(endpoint, {
        method: 'POST',
        headers: { Authorization: `Bearer ${await token()}`, 'Content-Type': 'application/json' },
        body: JSON.stringify({
          message: {
            token: target.token,
            data,
            // High priority wakes a sleeping phone; one waiting ping is enough, so they collapse.
            android: { priority: 'HIGH', ttl: '86400s', collapse_key: 'inbox' },
          },
        }),
      });
      if (response.ok) return 'ok';
      const text = await response.text();
      // UNREGISTERED: the app was uninstalled or the token rotated. INVALID_ARGUMENT on a token: never valid.
      if (response.status === 404 || /UNREGISTERED/.test(text) || (response.status === 400 && /registration token/i.test(text))) return 'gone';
      console.warn('fcm send failed', response.status, text.slice(0, 200));
      return 'error';
    } catch (err) {
      console.warn('fcm send failed', err?.message);
      return 'error';
    }
  };
}

let shared = null;
/** One sender per isolate, so the access token is reused across requests. */
export function pushFromEnv(env) {
  if (!env.FCM_SERVICE_ACCOUNT) return undefined;
  if (shared?.secret !== env.FCM_SERVICE_ACCOUNT) shared = { secret: env.FCM_SERVICE_ACCOUNT, push: fcmPush(env.FCM_SERVICE_ACCOUNT) };
  return shared.push;
}
