/**
 * Reference client side of Moodify identity (used by tests and, later, the
 * web app): seed → keys → code, and seed ↔ 24-word recovery key.
 * Spec: docs/social-protocol/identity.md. Android implements the same steps.
 */
import { BIP39_ENGLISH } from './bip39-english.mjs';
import { codeForSigningKey } from './identity.mjs';

const enc = new TextEncoder();
const SALT = enc.encode('moodify-identity-v1');
// PKCS#8 headers for a raw 32-byte private key (RFC 8410).
const ED25519_PKCS8 = Uint8Array.from([0x30, 0x2e, 0x02, 0x01, 0x00, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x04, 0x22, 0x04, 0x20]);
const X25519_PKCS8 = Uint8Array.from([0x30, 0x2e, 0x02, 0x01, 0x00, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x6e, 0x04, 0x22, 0x04, 0x20]);

async function hkdf(seed, info) {
  const key = await crypto.subtle.importKey('raw', seed, 'HKDF', false, ['deriveBits']);
  return new Uint8Array(await crypto.subtle.deriveBits({ name: 'HKDF', hash: 'SHA-256', salt: SALT, info: enc.encode(info) }, key, 256));
}

const concat = (a, b) => { const out = new Uint8Array(a.length + b.length); out.set(a); out.set(b, a.length); return out; };

async function publicOf(algorithm, header, privateRaw) {
  const key = await crypto.subtle.importKey('pkcs8', concat(header, privateRaw), { name: algorithm }, true, algorithm === 'Ed25519' ? ['sign'] : ['deriveBits']);
  const jwk = await crypto.subtle.exportKey('jwk', key);
  return Uint8Array.from(atob(jwk.x.replace(/-/g, '+').replace(/_/g, '/')), (c) => c.charCodeAt(0));
}

/** Everything an identity needs, derived from its 32-byte seed. */
export async function identityFromSeed(seed) {
  if (!(seed instanceof Uint8Array) || seed.length !== 32) throw new Error('seed must be 32 bytes');
  const signPrivate = await hkdf(seed, 'sign');
  const boxPrivate = await hkdf(seed, 'box');
  const signPub = await publicOf('Ed25519', ED25519_PKCS8, signPrivate);
  const boxPub = await publicOf('X25519', X25519_PKCS8, boxPrivate);
  return { signPrivate, boxPrivate, signPub, boxPub, code: await codeForSigningKey(signPub) };
}

export async function sign(signPrivate, message) {
  const key = await crypto.subtle.importKey('pkcs8', concat(ED25519_PKCS8, signPrivate), { name: 'Ed25519' }, false, ['sign']);
  return new Uint8Array(await crypto.subtle.sign({ name: 'Ed25519' }, key, enc.encode(message)));
}

/** 32-byte seed → 24 BIP-39 words (256 bits + 8-bit SHA-256 checksum). */
export async function seedToWords(seed) {
  const checksum = new Uint8Array(await crypto.subtle.digest('SHA-256', seed))[0];
  let bits = '';
  for (const b of seed) bits += b.toString(2).padStart(8, '0');
  bits += checksum.toString(2).padStart(8, '0');
  const words = [];
  for (let i = 0; i < 24; i++) words.push(BIP39_ENGLISH[parseInt(bits.slice(i * 11, i * 11 + 11), 2)]);
  return words;
}

/** 24 words → seed; throws on unknown words or a wrong checksum (a typo). */
export async function wordsToSeed(input) {
  const words = (Array.isArray(input) ? input : String(input).trim().toLowerCase().split(/\s+/)).filter(Boolean);
  if (words.length !== 24) throw new Error('A recovery key has 24 words.');
  let bits = '';
  for (const word of words) {
    const index = BIP39_ENGLISH.indexOf(word);
    if (index < 0) throw new Error(`"${word}" isn't a recovery-key word.`);
    bits += index.toString(2).padStart(11, '0');
  }
  const seed = new Uint8Array(32);
  for (let i = 0; i < 32; i++) seed[i] = parseInt(bits.slice(i * 8, i * 8 + 8), 2);
  const checksum = new Uint8Array(await crypto.subtle.digest('SHA-256', seed))[0];
  if (checksum !== parseInt(bits.slice(256), 2)) throw new Error('Those words have a typo somewhere.');
  return seed;
}

// ── End-to-end encrypted messages (docs/social-protocol/friends.md §3) ───────

const BOX_SALT = enc.encode('moodify-box-v1');

/**
 * The AES-256-GCM key two friends share: HKDF over their X25519 secret, bound
 * to both codes (sorted) so it differs for every pair.
 */
async function pairKey(myBoxPrivate, theirBoxPub, codeA, codeB) {
  const mine = await crypto.subtle.importKey('pkcs8', concat(X25519_PKCS8, myBoxPrivate), { name: 'X25519' }, false, ['deriveBits']);
  const theirs = await crypto.subtle.importKey('raw', theirBoxPub, { name: 'X25519' }, false, []);
  const shared = new Uint8Array(await crypto.subtle.deriveBits({ name: 'X25519', public: theirs }, mine, 256));
  const [first, second] = [codeA, codeB].sort();
  const ikm = await crypto.subtle.importKey('raw', shared, 'HKDF', false, ['deriveKey']);
  return crypto.subtle.deriveKey(
    { name: 'HKDF', hash: 'SHA-256', salt: BOX_SALT, info: enc.encode(`${first}|${second}`) },
    ikm, { name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt'],
  );
}

const messageAad = (sender, recipient) => enc.encode(`moodify-msg:v1:${sender}:${recipient}`);

/** Encrypts [plaintext] (a string) from me to them. Returns raw nonce and ciphertext bytes. */
export async function seal({ myBoxPrivate, myCode, theirBoxPub, theirCode, plaintext, nonce = crypto.getRandomValues(new Uint8Array(12)) }) {
  const key = await pairKey(myBoxPrivate, theirBoxPub, myCode, theirCode);
  const ciphertext = new Uint8Array(await crypto.subtle.encrypt(
    { name: 'AES-GCM', iv: nonce, additionalData: messageAad(myCode, theirCode) }, key, enc.encode(plaintext),
  ));
  return { nonce, ciphertext };
}

/**
 * Decrypts a message I sent them (the copy my other devices get, chat.md §2).
 * The pair key is the same both ways; only the direction in the AAD differs.
 */
export async function openOwn({ myBoxPrivate, myCode, theirBoxPub, theirCode, nonce, ciphertext }) {
  const key = await pairKey(myBoxPrivate, theirBoxPub, myCode, theirCode);
  const plain = await crypto.subtle.decrypt(
    { name: 'AES-GCM', iv: nonce, additionalData: messageAad(myCode, theirCode) }, key, ciphertext,
  );
  return new TextDecoder().decode(plain);
}

/** Decrypts a message they sent me; throws if it was altered or isn't for me. */
export async function open({ myBoxPrivate, myCode, theirBoxPub, theirCode, nonce, ciphertext }) {
  const key = await pairKey(myBoxPrivate, theirBoxPub, myCode, theirCode);
  const plain = await crypto.subtle.decrypt(
    { name: 'AES-GCM', iv: nonce, additionalData: messageAad(theirCode, myCode) }, key, ciphertext,
  );
  return new TextDecoder().decode(plain);
}

// ── Linking a new device (docs/social-protocol/linking.md) ───────────────────

const LINK_SALT = enc.encode('moodify-link-v1');

/** A one-time X25519 key pair for a device waiting to be linked. */
export async function newLinkKeyPair() {
  const privateKey = crypto.getRandomValues(new Uint8Array(32));
  return { privateKey, publicKey: await publicOf('X25519', X25519_PKCS8, privateKey) };
}

async function linkAesKey(myPrivate, theirPub, linkId) {
  const mine = await crypto.subtle.importKey('pkcs8', concat(X25519_PKCS8, myPrivate), { name: 'X25519' }, false, ['deriveBits']);
  const theirs = await crypto.subtle.importKey('raw', theirPub, { name: 'X25519' }, false, []);
  const shared = new Uint8Array(await crypto.subtle.deriveBits({ name: 'X25519', public: theirs }, mine, 256));
  const ikm = await crypto.subtle.importKey('raw', shared, 'HKDF', false, ['deriveKey']);
  return crypto.subtle.deriveKey({ name: 'HKDF', hash: 'SHA-256', salt: LINK_SALT, info: enc.encode(`link:${linkId}`) },
    ikm, { name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt']);
}

/** On the signed-in phone: encrypt the identity seed to the new device's one-time key. */
export async function sealSeedForLink({ seed, devicePub, linkId, senderPrivate, nonce = crypto.getRandomValues(new Uint8Array(12)) }) {
  // senderPrivate / nonce are fixed only by test vectors; normally both are random.
  const sender = senderPrivate
    ? { privateKey: senderPrivate, publicKey: await publicOf('X25519', X25519_PKCS8, senderPrivate) }
    : await newLinkKeyPair();
  const key = await linkAesKey(sender.privateKey, devicePub, linkId);
  const ciphertext = new Uint8Array(await crypto.subtle.encrypt({ name: 'AES-GCM', iv: nonce, additionalData: enc.encode(`moodify-link:v1:${linkId}`) }, key, seed));
  return { senderPub: sender.publicKey, nonce, ciphertext };
}

/** On the new device: recover the seed. */
export async function openLinkedSeed({ devicePrivate, senderPub, linkId, nonce, ciphertext }) {
  const key = await linkAesKey(devicePrivate, senderPub, linkId);
  return new Uint8Array(await crypto.subtle.decrypt({ name: 'AES-GCM', iv: nonce, additionalData: enc.encode(`moodify-link:v1:${linkId}`) }, key, ciphertext));
}

/**
 * Two words both screens show, from SHA-256(devicePub). If someone swapped the
 * key in transit, the words wouldn't match.
 */
export async function linkCheckWords(devicePub) {
  const digest = new Uint8Array(await crypto.subtle.digest('SHA-256', devicePub));
  const bits = (digest[0] << 16) | (digest[1] << 8) | digest[2];
  return [BIP39_ENGLISH[(bits >> 13) & 2047], BIP39_ENGLISH[(bits >> 2) & 2047]];
}

// ── Library sync (docs/social-protocol/sync.md) ─────────────────────────────
// Records are encrypted with a key only your devices can derive from the seed.
// The server sees an opaque item id (an HMAC), a timestamp and ciphertext.

/** The two sync keys: `enc` (AES-256-GCM) and `id` (HMAC-SHA256 for item ids). */
export async function syncKeysFromSeed(seed) {
  if (!(seed instanceof Uint8Array) || seed.length !== 32) throw new Error('seed must be 32 bytes');
  return { enc: await hkdf(seed, 'sync-enc'), id: await hkdf(seed, 'sync-id') };
}

/** The server's id for one item: HMAC(idKey, "collection\nid"), first 16 bytes, base64url (22 chars). */
export async function syncItemId(idKey, collection, id) {
  const key = await crypto.subtle.importKey('raw', idKey, { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']);
  const mac = new Uint8Array(await crypto.subtle.sign('HMAC', key, enc.encode(`${collection}\n${id}`)));
  return btoa(String.fromCharCode(...mac.slice(0, 16))).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

const syncAad = (code, item) => enc.encode(`moodify-sync:v1:${code}:${item}`);

export async function sealRecord({ encKey, code, item, plaintext, nonce = crypto.getRandomValues(new Uint8Array(12)) }) {
  const key = await crypto.subtle.importKey('raw', encKey, 'AES-GCM', false, ['encrypt']);
  const ciphertext = new Uint8Array(await crypto.subtle.encrypt({ name: 'AES-GCM', iv: nonce, additionalData: syncAad(code, item) }, key, enc.encode(plaintext)));
  return { nonce, ciphertext };
}

/** Throws if the record was altered, moved to another item, or isn't yours. */
export async function openRecord({ encKey, code, item, nonce, ciphertext }) {
  const key = await crypto.subtle.importKey('raw', encKey, 'AES-GCM', false, ['decrypt']);
  const plain = await crypto.subtle.decrypt({ name: 'AES-GCM', iv: nonce, additionalData: syncAad(code, item) }, key, ciphertext);
  return new TextDecoder().decode(plain);
}
