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

## First deploy (once)

1. `npx wrangler login`
2. `npx wrangler d1 create moodify-social`, then paste the printed id into `wrangler.toml`.
3. `npx wrangler d1 migrations apply moodify-social --remote`
4. `npx wrangler secret put SESSION_SECRET`. Use 32 or more random characters, and never reuse the dev one.
5. `npx wrangler deploy`
6. Build the Android release with `-PsocialBaseUrl=https://moodify-social.<your-subdomain>.workers.dev`, or a custom domain.

Release builds without `socialBaseUrl` still let people create and back up a code on their phone. It registers automatically once a server address is configured.
