import { test } from 'node:test';
import assert from 'node:assert/strict';
import { identityFromSeed, seedToWords, sign, wordsToSeed } from '../src/client-keys.mjs';
import { base64UrlToBytes, bytesToBase64Url, cleanDisplayName, normalizeCode, verifySignature } from '../src/identity.mjs';

// Shared with Android (IdentityVectorsTest) and docs/social-protocol/identity.md.
const VECTORS = [
  {
    seed: '00'.repeat(32),
    signPub: 'EYsBlHrxePEKD7ngmtSa7jg8MKXsCsHYZC20i6zPdbc',
    boxPub: '07z1Ib3v3qu6imxXC_OZDNrx5mgF0frXwgtSJfeJa1g',
    code: 'MOOD-CQHV-YGY4',
    words: `${'abandon '.repeat(23)}art`,
  },
  {
    seed: '000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f',
    signPub: 'kym6gtUG-avd0mU7zvp5gkhDp7Arl2-Eup33YxNEhL0',
    boxPub: 'yDOz07GJVN7MAu4yizPb3sBBfdtCdqo5pjCVUwsPBE4',
    code: 'MOOD-67AF-HHK3',
    words: 'abandon amount liar amount expire adjust cage candy arch gather drum bullet absurd math era live bid rhythm alien crouch range attend journey unaware',
  },
];

const fromHex = (hex) => Uint8Array.from(Buffer.from(hex, 'hex'));

test('seeds derive the published keys, code and recovery words', async () => {
  for (const v of VECTORS) {
    const id = await identityFromSeed(fromHex(v.seed));
    assert.equal(bytesToBase64Url(id.signPub), v.signPub);
    assert.equal(bytesToBase64Url(id.boxPub), v.boxPub);
    assert.equal(id.code, v.code);
    assert.equal((await seedToWords(fromHex(v.seed))).join(' '), v.words);
    assert.deepEqual(await wordsToSeed(v.words), fromHex(v.seed));
  }
});

test('recovery words reject typos and wrong lengths', async () => {
  const words = VECTORS[1].words.split(' ');
  await assert.rejects(wordsToSeed(words.slice(0, 23)), /24 words/);
  await assert.rejects(wordsToSeed([...words.slice(0, 23), 'zebra']), /typo/);
  await assert.rejects(wordsToSeed([...words.slice(0, 23), 'moodify']), /isn't a recovery-key word/);
  // Case and extra spaces are forgiven.
  assert.deepEqual(await wordsToSeed(`  ${VECTORS[1].words.toUpperCase()}  `), fromHex(VECTORS[1].seed));
});

test('signatures verify only for the right key and message', async () => {
  const id = await identityFromSeed(fromHex(VECTORS[1].seed));
  const other = await identityFromSeed(fromHex(VECTORS[0].seed));
  const sig = await sign(id.signPrivate, 'moodify-login:v1:abc');
  assert.equal(await verifySignature(id.signPub, 'moodify-login:v1:abc', sig), true);
  assert.equal(await verifySignature(id.signPub, 'moodify-login:v1:abd', sig), false);
  assert.equal(await verifySignature(other.signPub, 'moodify-login:v1:abc', sig), false);
});

test('codes are normalised from what people type', () => {
  assert.equal(normalizeCode('mood-67af-hhk3'), 'MOOD-67AF-HHK3');
  assert.equal(normalizeCode(' 67AF HHK3 '), 'MOOD-67AF-HHK3');
  assert.equal(normalizeCode('MOOD-67AF-HHKE'), 'MOOD-67AF-HHKE');
  assert.equal(normalizeCode('MOOD-0IL0-0000'), 'MOOD-0110-0000');
  assert.equal(normalizeCode('MOOD-67AF-HHK'), null);
  assert.equal(normalizeCode('MOOD-67AF-HHKU'), null); // U isn't in Crockford base32
});

test('display names are trimmed, bounded and free of invisible characters', () => {
  assert.equal(cleanDisplayName('  Asha   K  '), 'Asha K');
  assert.equal(cleanDisplayName('A'), null);
  assert.equal(cleanDisplayName('x'.repeat(25)), null);
  assert.equal(cleanDisplayName('🎧🎧'), '🎧🎧');
  assert.equal(cleanDisplayName(`Asha${String.fromCharCode(0x202e)}K`), null);
  assert.equal(cleanDisplayName(`Ash${String.fromCharCode(0x200b)}a`), null);
});

test('base64url round-trips and rejects junk', () => {
  const bytes = Uint8Array.from([0, 255, 62, 63, 1]);
  assert.deepEqual(base64UrlToBytes(bytesToBase64Url(bytes)), bytes);
  assert.throws(() => base64UrlToBytes('not base64!'));
});
