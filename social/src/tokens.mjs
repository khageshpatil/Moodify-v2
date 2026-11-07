/**
 * Session tokens: "v1.<code>.<expiresAtSeconds>.<mac>", where mac is
 * HMAC-SHA256 over everything before it, keyed by SESSION_SECRET. Stateless,
 * so the Worker needs no session table; deleting an account invalidates its
 * tokens because every request re-checks that the account exists.
 */
import { base64UrlToBytes, bytesToBase64Url } from './identity.mjs';

export const SESSION_SECONDS = 24 * 60 * 60;

async function hmacKey(secret) {
  if (typeof secret !== 'string' || secret.length < 32) throw new Error('SESSION_SECRET must be at least 32 characters');
  return crypto.subtle.importKey('raw', new TextEncoder().encode(secret), { name: 'HMAC', hash: 'SHA-256' }, false, ['sign', 'verify']);
}

export async function issueToken(secret, code, nowSeconds = Math.floor(Date.now() / 1000)) {
  const expiresAt = nowSeconds + SESSION_SECONDS;
  const payload = `v1.${code}.${expiresAt}`;
  const mac = await crypto.subtle.sign('HMAC', await hmacKey(secret), new TextEncoder().encode(payload));
  return { token: `${payload}.${bytesToBase64Url(new Uint8Array(mac))}`, expiresAt };
}

/** The code the token was issued to, or null when it's forged, malformed or expired. */
export async function readToken(secret, token, nowSeconds = Math.floor(Date.now() / 1000)) {
  if (typeof token !== 'string') return null;
  const parts = token.split('.');
  if (parts.length !== 4 || parts[0] !== 'v1') return null;
  const [, code, expiresRaw, macRaw] = parts;
  const expiresAt = Number(expiresRaw);
  if (!Number.isInteger(expiresAt) || expiresAt <= nowSeconds) return null;
  let mac;
  try {
    mac = base64UrlToBytes(macRaw);
  } catch {
    return null;
  }
  const ok = await crypto.subtle.verify('HMAC', await hmacKey(secret), mac, new TextEncoder().encode(`v1.${code}.${expiresRaw}`));
  return ok ? code : null;
}
