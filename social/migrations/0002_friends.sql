-- M2: friends, blocks, reports, and the encrypted inbox (song shares now, chat in M5).

-- One row per pair, stored with a < b. Pending until the other person accepts.
CREATE TABLE IF NOT EXISTS friendships (
  a TEXT NOT NULL,
  b TEXT NOT NULL,
  status TEXT NOT NULL CHECK (status IN ('pending', 'accepted')),
  requested_by TEXT NOT NULL,
  note TEXT,
  created_at INTEGER NOT NULL,
  accepted_at INTEGER,
  PRIMARY KEY (a, b)
);
CREATE INDEX IF NOT EXISTS friendships_by_b ON friendships (b);
CREATE INDEX IF NOT EXISTS friendships_by_requester ON friendships (requested_by, created_at);

-- Silent blocks: the blocked person sees nothing change.
CREATE TABLE IF NOT EXISTS blocks (
  blocker TEXT NOT NULL,
  blocked TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  PRIMARY KEY (blocker, blocked)
);

-- An ignored request can't be re-sent to the same person for 7 days.
CREATE TABLE IF NOT EXISTS ignored_requests (
  from_code TEXT NOT NULL,
  to_code TEXT NOT NULL,
  until INTEGER NOT NULL,
  PRIMARY KEY (from_code, to_code)
);

-- Reports for manual review. Evidence is plain text the reporter chose to include.
CREATE TABLE IF NOT EXISTS reports (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  reporter TEXT NOT NULL,
  reported TEXT NOT NULL,
  reason TEXT NOT NULL,
  evidence TEXT,
  created_at INTEGER NOT NULL
);

-- End-to-end encrypted messages: the server only ever sees ciphertext.
-- Deleted once the recipient acknowledges them, or after 30 days.
CREATE TABLE IF NOT EXISTS messages (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  sender TEXT NOT NULL,
  recipient TEXT NOT NULL,
  nonce TEXT NOT NULL,
  ciphertext TEXT NOT NULL,
  created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS messages_by_recipient ON messages (recipient, id);
