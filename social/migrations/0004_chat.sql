-- M5 chat (docs/social-protocol/chat.md).

-- A copy of each message you send is kept in your own inbox, so your other
-- devices see the conversation too. `peer` is who it was sent to (NULL for
-- messages from someone else).
ALTER TABLE messages ADD COLUMN peer TEXT;

-- Each device reads the inbox from its own position. A message is deleted once
-- every device seen in the last 30 days has confirmed it (or after 30 days).
CREATE TABLE IF NOT EXISTS inbox_devices (
  code TEXT NOT NULL,
  device TEXT NOT NULL,
  acked_up_to INTEGER NOT NULL DEFAULT 0,
  seen_at INTEGER NOT NULL,
  PRIMARY KEY (code, device)
);
