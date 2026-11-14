# Moodify social: abuse limits and capacity (M7)

The server runs on Cloudflare's free plan, so the limits protect two things: other people (spam, floods) and the daily quotas (one script must not be able to use up the day's database writes and take the service down for everyone).

## Limits

A limit that is hit answers `429 slow_down` with a message the apps show as is. Live sockets get an `error` event with code `slow_down` or `too_big` instead.

| What | Limit | Kept where |
|---|---|---|
| Sign-in calls (`/v1/auth/challenge`, `/v1/auth/verify`, `/v1/accounts`) | 30 a minute per IP | Worker memory |
| New Moodify codes | 20 a day per IP | D1 count (keyed hash of the IP, cleared after a day) |
| Browser link codes | 20 an hour per IP | D1 count (keyed hash, on the 5-minute link row) |
| Code lookups (`/v1/users/{code}`) | 60 a minute per account | Worker memory |
| Reports | 20 a day per account | D1 count |
| Friend requests | 30 a day per account; 500 friends | D1 (since M2) |
| Messages | 600 an hour per account; 96 KiB each; kept 30 days | D1 (since M2/M5) |
| Library sync | 200 records a push; 20,000 records / 32 MiB per account | D1 (since M6) |
| Listening rooms | 20 opened an hour per account; 20 members; 200 queued songs | Worker memory / room |
| Live connections | 10 open at once per account (the 11th is closed with `4008 too_many_connections`) | User hub |
| Live socket messages | 8 KiB each; 20 at once, then 5 a second (hub) | User hub memory |
| Room socket messages | 16 KiB each; 30 at once, then 10 a second; chat 5 at once, then 1 a second | Room memory |

"Worker memory" limits are per isolate and best-effort: they stop one client hammering the server, not a distributed attack. The daily caps use rows the server keeps anyway, so they cost reads, not writes.

**Sign-in challenges are stateless.** A challenge is 16 random bytes, the expiry and an HMAC (`tokens.mjs`). Handing one out writes nothing. The challenge is recorded only when it is used with a valid signature, which keeps it single-use.

**IP addresses.** Cloudflare sets `CF-Connecting-IP`; clients can't override it. The server stores only `HMAC(SESSION_SECRET, ip)`, truncated, on new accounts (cleared after 24 hours) and on link codes (deleted within minutes). Without the header (local `wrangler dev`, tests) the IP limits are skipped.

## Errors

Workers Logs is on (`[observability]` in `wrangler.toml`). API errors are logged as one JSON line: method, the route with codes and ids masked, the message, and the first stack lines. Bodies, tokens and codes are never logged. Find them in the Cloudflare dashboard › Workers › moodify-social › Logs.

## Load test (2026-11-12, local `wrangler dev`)

The script is `loadtest.mjs` (scratchpad pattern): N users sign up, each befriends K others, holds a live connection and sends presence and messages, and 20 of them share a listening room.

| Run | Message sent (p50 / p95) | Delivered live (p50 / p95) | Errors |
|---|---|---|---|
| 40 users, 6 friends, presence every 15 s (as the apps send) | 31 / 44 ms | 23 / 33 ms | none |
| 120 users, 10 friends, the same | 709 / 2,173 ms | 269 / 568 ms | none |
| 40 users, presence every second (15× the real rate) | ~3 s | ~3 s | none |

Local `wrangler dev` runs everything in one process, so these numbers show where the server's own work queues up, not Cloudflare's speed.

Two fixes came out of the test:
- **Sending a message** used to make nine database and server calls one after another and scanned the messages table twice. It now runs the checks in parallel, writes both copies of the message in one batch, notifies devices after replying, and uses new indexes (`0008_limits.sql`).
- **Position ticks** went to every friend's hub, including friends who were offline. They now go only to friends who are online. Song, play/pause and online changes still go to everyone, and the list of who is online is relearned every 5 minutes.

## What the free plan carries

Rough, per day, for an active user: about 300 API requests, a couple of hours of listening (presence every 15 s to about 3 online friends, roughly 1,500 Durable Object requests), and about 100 messages (about 200 D1 row writes).

The limits that matter:
- **Durable Object requests: 100,000 a day.** This is the first to run out, at roughly **50 daily active users**. Before the online-only change it was about 20.
- **Workers requests: 100,000 a day.** Runs out at about 300 users.
- **D1: 100,000 row writes a day.** Runs out at about 500 users.

Past about 50 daily active users, move to Workers Paid ($5 a month). It raises all three limits far beyond Moodify's needs.
