# Linking a new device, v1 (M3)

This is how a signed-in phone moves its identity to a new device, such as the web app. It doesn't require typing the 24 words, and the server never sees the seed.

## Flow

1. **The new device asks for a link code.** It makes a one-time X25519 key pair and calls `POST /v1/link {devicePub}`. The server replies `{linkId, expiresIn: 300}`, where `linkId` is 8 Crockford characters. The new device shows:
   - the code, as `K7P2-QXAB`
   - a QR code containing `moodify://link/{linkId}?k={devicePub}`
   - two **check words**: `linkCheckWords(devicePub)` (§3)
2. **The phone gets the new device's key.** There are two ways:
   - **QR code:** the phone's camera opens Moodify with the key inside the link, so the server isn't trusted for it.
   - **Typed code:** the phone fetches the key with `GET /v1/link/{linkId}`.

   Either way, the phone shows the check words it computed itself. The user confirms they match the new device's screen.
3. **The phone sends the identity.** It encrypts the seed (§2) and posts it with `POST /v1/link/{linkId}/seal {senderPub, nonce, ciphertext}`. This requires a signed-in phone (Bearer token), and works once per link.
4. **The new device collects it.** It polls `GET /v1/link/{linkId}/result`. That returns `202` while waiting, then `{senderPub, nonce, ciphertext}` exactly once, after which the server deletes the row. The new device decrypts the seed and derives the same identity.

## 2. Encryption

```
shared = X25519(senderPrivate, devicePub)
key    = HKDF-SHA256(ikm = shared, salt = "moodify-link-v1", info = "link:{linkId}", L = 32)
blob   = AES-256-GCM(key, nonce = 12 random bytes, aad = "moodify-link:v1:{linkId}", plaintext = seed)
```

The sender uses a fresh one-time key each time.

**Test vector:**

| Input | Value |
|---|---|
| Seed | `000102…1f` |
| devicePub | `07z1Ib3v3qu6imxXC_OZDNrx5mgF0frXwgtSJfeJa1g` |
| linkId | `K7P2QXAB` |
| senderPrivate | `07` × 32 |
| nonce | `0c 0b … 01` |

Expected output:

| Output | Value |
|---|---|
| senderPub | `E75P6uryBMf9M1j8nAByGIHRdCeBKCJ-xnTzf3_pe20` |
| ciphertext | `oT3rhHnBf5HDuqY1phRoklo3PqqDXeBomq9T7Tbd6sxKcWkJmOIBb5zvUNHExPGA` |
| Check words | `dream · seven` |

## 3. Check words

Take the first 24 bits of SHA-256(devicePub). Bits 23–13 index BIP-39 word 1, and bits 12–2 index word 2.

If the server or anyone in between swapped `devicePub` for their own key, the phone's words would differ from the ones on the real new device. The user would then cancel the link.

## Limits

- **Expiry:** a link code expires after 5 minutes.
- **Single use:** each code can be sealed once, and its result read once.
- **Cleanup:** expired rows are swept whenever a new link is created.
