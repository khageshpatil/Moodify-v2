/**
 * Moodify identity: how a public signing key becomes a Moodify code, and how
 * signatures are checked. Kept free of Worker APIs so Node tests can run it.
 * Spec: docs/social-protocol/identity.md
 */

const CROCKFORD = '0123456789ABCDEFGHJKMNPQRSTVWXYZ';

export function bytesToBase64Url(bytes) {
  let binary = '';
  for (const b of bytes) binary += String.fromCharCode(b);
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

export function base64UrlToBytes(value) {
  if (typeof value !== 'string' || !/^[A-Za-z0-9_-]*$/.test(value)) throw new Error('bad base64url');
  const padded = value.replace(/-/g, '+').replace(/_/g, '/') + '==='.slice((value.length + 3) % 4);
  const binary = atob(padded);
  return Uint8Array.from(binary, (c) => c.charCodeAt(0));
}

/** 40 bits → 8 Crockford base32 characters. */
function crockford40(bytes) {
  let bits = 0n;
  for (const b of bytes.slice(0, 5)) bits = (bits << 8n) | BigInt(b);
  let out = '';
  for (let i = 7; i >= 0; i--) out += CROCKFORD[Number((bits >> BigInt(i * 5)) & 31n)];
  return out;
}

/** MOOD-XXXX-XXXX from a raw 32-byte Ed25519 public key. */
export async function codeForSigningKey(signPub) {
  if (!(signPub instanceof Uint8Array) || signPub.length !== 32) throw new Error('signing key must be 32 bytes');
  const digest = new Uint8Array(await crypto.subtle.digest('SHA-256', signPub));
  const chars = crockford40(digest);
  return `MOOD-${chars.slice(0, 4)}-${chars.slice(4)}`;
}

const CODE_PATTERN = /^MOOD-[0-9A-HJKMNP-TV-Z]{4}-[0-9A-HJKMNP-TV-Z]{4}$/;

/** Normalises what people type ("mood 7k3f 92qx", "MOOD-7K3F-92QX") to the canonical code, or null. */
export function normalizeCode(input) {
  if (typeof input !== 'string') return null;
  const compact = input.toUpperCase().replace(/[^0-9A-Z]/g, '').replace(/^MOOD/, '')
    // Crockford: I/L read as 1, O as 0.
    .replace(/[IL]/g, '1').replace(/O/g, '0');
  if (compact.length !== 8) return null;
  const code = `MOOD-${compact.slice(0, 4)}-${compact.slice(4)}`;
  return CODE_PATTERN.test(code) ? code : null;
}

export async function verifySignature(signPub, message, signature) {
  try {
    const key = await crypto.subtle.importKey('raw', signPub, { name: 'Ed25519' }, false, ['verify']);
    return await crypto.subtle.verify({ name: 'Ed25519' }, key, signature, new TextEncoder().encode(message));
  } catch {
    return false;
  }
}

/** Control characters, zero-width marks and bidi overrides (used to spoof names). */
function isInvisibleOrControl(cp) {
  return cp <= 0x1f || (cp >= 0x7f && cp <= 0x9f) || (cp >= 0x200b && cp <= 0x200f) || (cp >= 0x2028 && cp <= 0x202e);
}

/** Display names: 2–24 visible characters, no control characters or line breaks. */
export function cleanDisplayName(input) {
  if (typeof input !== 'string') return null;
  const name = input.normalize('NFC').replace(/\s+/g, ' ').trim();
  if ([...name].some((ch) => isInvisibleOrControl(ch.codePointAt(0)))) return null;
  const length = [...name].length;
  return length >= 2 && length <= 24 ? name : null;
}

/** Exact strings that get signed; any change here is a protocol version bump. */
export const signedMessages = {
  register: (challenge, signPub, boxPub, displayName) =>
    `moodify-register:v1:${challenge}:${signPub}:${boxPub}:${displayName}`,
  login: (challenge) => `moodify-login:v1:${challenge}`,
};
