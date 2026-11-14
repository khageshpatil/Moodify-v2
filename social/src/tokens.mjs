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

/**
 * Sign-in challenges are stateless too: 16 random bytes, the expiry and a MAC.
 * Handing one out writes nothing; the server records a challenge only when it
 * is used with a valid signature (single use), so a flood of challenge
 * requests costs no database writes.
 */
export async function issueChallenge(secret, expiresAt) {
  const body = new Uint8Array(20);
  crypto.getRandomValues(body.subarray(0, 16));
  new DataView(body.buffer).setUint32(16, expiresAt);
  const mac = new Uint8Array(await crypto.subtle.sign('HMAC', await hmacKey(secret), withLabel('challenge', body))).subarray(0, 16);
  const out = new Uint8Array(36);
  out.set(body);
  out.set(mac, 20);
  return bytesToBase64Url(out);
}

/** The challenge's expiry when it is one of ours and still valid, else null. */
export async function readChallenge(secret, challenge, nowSeconds) {
  if (typeof challenge !== 'string' || challenge.length !== 48) return null;
  let bytes;
  try {
    bytes = base64UrlToBytes(challenge);
  } catch {
    return null;
  }
  if (bytes.length !== 36) return null;
  const body = bytes.subarray(0, 20);
  const expected = new Uint8Array(await crypto.subtle.sign('HMAC', await hmacKey(secret), withLabel('challenge', body))).subarray(0, 16);
  let diff = 0;
  for (let i = 0; i < 16; i += 1) diff |= expected[i] ^ bytes[20 + i];
  if (diff !== 0) return null;
  const expiresAt = new DataView(body.buffer, body.byteOffset).getUint32(16);
  return expiresAt >= nowSeconds ? expiresAt : null;
}

/** A keyed hash of an IP address, for counting sign-ups without storing the address. */
export async function ipTag(secret, ip) {
  const mac = await crypto.subtle.sign('HMAC', await hmacKey(secret), withLabel('ip', new TextEncoder().encode(ip)));
  return bytesToBase64Url(new Uint8Array(mac).subarray(0, 12));
}

function withLabel(label, bytes) {
  const prefix = new TextEncoder().encode(`${label}:`);
  const out = new Uint8Array(prefix.length + bytes.length);
  out.set(prefix);
  out.set(bytes, prefix.length);
  return out;
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
