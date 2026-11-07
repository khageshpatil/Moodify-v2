# moodify-social

This Cloudflare Worker will run Moodify's friends, presence, chat and listening rooms. It's separate from the music Worker in `worker/`, so neither can take the other down.

- **Design:** `docs/SOCIAL_AND_WEB_DESIGN.md`
- **Protocol:** `docs/social-protocol/`

**Status: M1 (identity).** You can register with a Moodify code, sign in by signature, rename yourself, delete your account, and look people up by code.

## Run locally (no Cloudflare account needed)

```sh
cd social
echo "SESSION_SECRET=$(node -e "console.log(require('crypto').randomBytes(30).toString('base64url'))")" > .dev.vars
npx wrangler d1 migrations apply moodify-social --local
npx wrangler dev --port 8790 --ip 127.0.0.1
```

Debug Android builds call `http://10.0.2.2:8790`, which is this server as seen from the emulator. To use a different address, build with `-PsocialBaseUrl=…`.

## Tests

```sh
npm test   # node --test: identity vectors, tokens, and the full API against an in-memory store
```

## Deployment

Live at **https://moodify-social.moodify-kp.workers.dev** (D1 database `moodify-social`, `f57a4ad6-a00f-4bde-91f4-8270e1fb0b53`).

**Automatic (every merge to `main`):** Cloudflare Workers Builds, connected on the `moodify-social` worker
(Settings > Build) with:

| Setting | Value |
|---|---|
| Branch | `main` (non-production branch builds off) |
| Root directory | `social` |
| Build command | *(empty)* |
| Deploy command | `npx wrangler d1 migrations apply moodify-social --remote && npx wrangler deploy` |

**By hand:** `npx wrangler login`, then the same two commands from this folder.

**Secrets** (Settings > Variables and Secrets, or `npx wrangler secret put NAME`); they survive deploys:

| Secret | What |
|---|---|
| `SESSION_SECRET` | 32+ random characters; never reuse the dev one |
| `FCM_SERVICE_ACCOUNT` | Firebase service-account JSON, for Android wake-up pings |
| `VAPID_PUBLIC_KEY`, `VAPID_PRIVATE_KEY` | Browser wake-up pings; make a pair with `node scripts/vapid-keys.mjs` |

Clients: Android release builds default to the live address (override with `-PsocialBaseUrl=…`); the web app reads `VITE_SOCIAL_URL` (set in Vercel).
