-- M7 abuse limits (docs/social-protocol/limits.md). Daily caps are counted from
-- rows that exist anyway; the IP is stored only as a keyed hash and only for a day.
ALTER TABLE accounts ADD COLUMN signup_ip TEXT;
CREATE INDEX IF NOT EXISTS accounts_signup_ip ON accounts (signup_ip, created_at);
ALTER TABLE link_sessions ADD COLUMN ip TEXT;
CREATE INDEX IF NOT EXISTS link_sessions_ip ON link_sessions (ip, created_at);
CREATE INDEX IF NOT EXISTS reports_reporter ON reports (reporter, created_at);
-- Sending a message counts the sender's last hour and prunes old messages: both need indexes.
CREATE INDEX IF NOT EXISTS messages_by_sender ON messages (sender, created_at);
CREATE INDEX IF NOT EXISTS messages_by_age ON messages (created_at);
