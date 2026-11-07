# Moodify identity protocol, v1

This is the contract between the Android app, the web app and `moodify-social`. Every client must derive the same keys, code and recovery words from a seed.

**Reference implementations:**
- **Server:** `social/src/identity.mjs`
- **JavaScript client:** `social/src/client-keys.mjs`
- **Android:** `android/.../data/social/MoodifyIdentity.kt`

The test vectors below appear in both test suites (`social/test/identity.test.mjs`, `MoodifyIdentityTest.kt`).

## 1. Seed and keys

| Step | Definition |
|---|---|
| Seed | 32 random bytes from a CSPRNG. The seed **is** the account. It leaves the device only as the recovery key. |
| Signing key (Ed25519) | `signPrivate = HKDF-SHA256(ikm = seed, salt = "moodify-identity-v1", info = "sign", L = 32)` |
| Box key (X25519) | `boxPrivate = HKDF-SHA256(ikm = seed, salt = "moodify-identity-v1", info = "box", L = 32)` (standard X25519 clamping) |
| Public keys | Raw 32-byte Ed25519 / X25519 public keys, sent as unpadded base64url |

## 2. Moodify code

```
code = "MOOD-" + c[0..4] + "-" + c[4..8]
c    = Crockford base32 of the first 40 bits of SHA-256(signPub)
```

- **Alphabet:** `0123456789ABCDEFGHJKMNPQRSTVWXYZ`. There is no I, L, O or U.
- **When reading typed codes:** convert to upper case, strip the optional `MOOD` prefix and any separators, and read I and L as 1 and O as 0.
- **Clashes:** codes are 40 bits, so a collision is rare but possible. The server answers `409 code_taken`, and a new identity then makes a fresh seed.

## 3. Recovery key

The recovery key is the seed encoded as 24 words, using standard BIP-39 with the English list (`english.txt`, SHA-256 `2f5eed53…dbda`):
- 256 bits of seed plus an 8-bit checksum (the first byte of SHA-256(seed))
- split into 24 groups of 11 bits

When reading the recovery key back:
- Case and extra whitespace are ignored.
- Unknown words and checksum mismatches are rejected with a plain-language error.

## 4. Signed messages

Clients sign the following exact UTF-8 strings with the Ed25519 key. Changing either one requires a new protocol version.

| Purpose | Message |
|---|---|
| Register | `moodify-register:v1:{challenge}:{signPub}:{boxPub}:{displayName}` (the display name exactly as sent) |
| Sign in | `moodify-login:v1:{challenge}` |

## 5. HTTP API (M1)

All bodies are JSON. Errors look like `{"error": {"code", "message"}}`.

| Method & path | Auth | Body → response |
|---|---|---|
| `POST /v1/auth/challenge` | — | → `{challenge, expiresIn}`. Single use, valid for 5 minutes. |
| `POST /v1/accounts` | — | `{challenge, signPub, boxPub, displayName, signature}` → `201` (created) or `200` (this key already has an account) with `{account, token, expiresAt}`. `409 code_taken` if another key owns the code. |
| `POST /v1/auth/verify` | — | `{challenge, signPub, signature}` → `{account, token, expiresAt}`. `404 no_account` means the client should register. |
| `GET /v1/me` | Bearer | → `{account}` |
| `PATCH /v1/me` | Bearer | `{displayName}` → `{account}` |
| `DELETE /v1/me` | Bearer | → `204`. The account is gone immediately, and existing tokens stop working. |
| `GET /v1/users/{code}` | Bearer | → `{user: {code, displayName, boxPub}}`. Typed codes are normalised. |

- **Session tokens:** `v1.{code}.{expiresAtSeconds}.{HMAC-SHA256}`, valid for 24 hours. They're stateless; every request re-checks that the account still exists.
- **Display names:** 2–24 characters after whitespace is collapsed. Control characters, zero-width characters and bidi overrides are rejected.

## 6. Test vectors

| Seed (hex) | signPub | boxPub | Code | Words |
|---|---|---|---|---|
| `00` × 32 | `EYsBlHrxePEKD7ngmtSa7jg8MKXsCsHYZC20i6zPdbc` | `07z1Ib3v3qu6imxXC_OZDNrx5mgF0frXwgtSJfeJa1g` | `MOOD-CQHV-YGY4` | `abandon` × 23, `art` |
| `000102…1e1f` | `kym6gtUG-avd0mU7zvp5gkhDp7Arl2-Eup33YxNEhL0` | `yDOz07GJVN7MAu4yizPb3sBBfdtCdqo5pjCVUwsPBE4` | `MOOD-67AF-HHK3` | `abandon amount liar amount expire adjust cage candy arch gather drum bullet absurd math era live bid rhythm alien crouch range attend journey unaware` |

The signature of `moodify-login:v1:test-challenge-0001` by the second seed is:

```
wYIaZ4gzdu7DNWFn502WW_xohqqlCRycDDjc-TAAIeE2kRYCr-92eL7Sxh8lHLzu6IwBLa3EcxIll5CcEGhwAw
```

## 7. Storage on the phone (Android)

- **The seed** is encrypted with AES-256-GCM under an Android Keystore key (`moodify_identity_v1`), which can't leave the device.
- **Backups:** `moodify_identity.xml` is excluded from both cloud backup and device transfer. Identities move between phones only by recovery key.
