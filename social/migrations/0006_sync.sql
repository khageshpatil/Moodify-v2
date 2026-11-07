-- Library sync (docs/social-protocol/sync.md).

-- One encrypted record per library item. `item` is an HMAC the server can't
-- reverse; `seq` orders changes per account so devices pull "after" a position.
CREATE TABLE IF NOT EXISTS sync_records (
  code TEXT NOT NULL,
  item TEXT NOT NULL,
  seq INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  nonce TEXT NOT NULL,
  ciphertext TEXT NOT NULL,
  PRIMARY KEY (code, item)
);
CREATE INDEX IF NOT EXISTS sync_records_seq ON sync_records (code, seq);
