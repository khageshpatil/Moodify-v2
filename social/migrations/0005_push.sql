-- Wake-up pings (docs/social-protocol/chat.md §3).

-- One push token per device. The ping carries no content; the device fetches
-- and decrypts its inbox itself.
CREATE TABLE IF NOT EXISTS push_tokens (
  code TEXT NOT NULL,
  device TEXT NOT NULL,
  platform TEXT NOT NULL,
  token TEXT NOT NULL,
  updated_at INTEGER NOT NULL,
  PRIMARY KEY (code, device)
);
CREATE INDEX IF NOT EXISTS push_tokens_token ON push_tokens (token);
