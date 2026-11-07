// Prints a new VAPID key pair for browser notifications (docs/social-protocol/chat.md §3).
// Local: add both lines to .dev.vars. Deployed: `wrangler secret put VAPID_PRIVATE_KEY` and
// `wrangler secret put VAPID_PUBLIC_KEY`. Keep the pair: a new pair stops pings to every
// browser until it opens Moodify again and re-subscribes.
import { bytesToBase64Url } from '../src/identity.mjs';

const pair = await crypto.subtle.generateKey({ name: 'ECDSA', namedCurve: 'P-256' }, true, ['sign', 'verify']);
const jwk = await crypto.subtle.exportKey('jwk', pair.privateKey);
const raw = new Uint8Array(await crypto.subtle.exportKey('raw', pair.publicKey));
console.log(`VAPID_PRIVATE_KEY=${jwk.d}`);
console.log(`VAPID_PUBLIC_KEY=${bytesToBase64Url(raw)}`);
