-- M1: identities. Only what's needed to find someone: no email, phone or photos.
CREATE TABLE IF NOT EXISTS accounts (
  code TEXT PRIMARY KEY,            -- MOOD-XXXX-XXXX, derived from sign_pub
  sign_pub TEXT NOT NULL UNIQUE,    -- Ed25519 public key, base64url
  box_pub TEXT NOT NULL,            -- X25519 public key, base64url (for end-to-end encryption)
  display_name TEXT NOT NULL,
  created_at INTEGER NOT NULL       -- epoch seconds
);

-- One-time sign-in challenges; deleted on use, and expired ones are swept.
CREATE TABLE IF NOT EXISTS challenges (
  nonce TEXT PRIMARY KEY,
  expires_at INTEGER NOT NULL
);
