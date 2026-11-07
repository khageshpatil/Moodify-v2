-- M3: link a new device (e.g. the web app) to an identity from a signed-in phone.
-- The phone sends the seed encrypted to the new device's one-time key; the
-- server only relays ciphertext and forgets it after one read or 5 minutes.
CREATE TABLE IF NOT EXISTS link_sessions (
  id TEXT PRIMARY KEY,           -- 8-character Crockford code shown on the new device
  device_pub TEXT NOT NULL,      -- new device's one-time X25519 public key (base64url)
  sender_pub TEXT,               -- phone's one-time X25519 public key
  nonce TEXT,
  ciphertext TEXT,
  created_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL
);
