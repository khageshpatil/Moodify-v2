import { JSDOM } from 'jsdom';
import { BotGuardClient } from 'bgutils-js/botguard';
import { WebPoMinter } from 'bgutils-js/webpo';

const WAA_API_KEY = 'AIzaSyDyT5W0Jh49F30Pqqtyfdf7pDLFKLJoAnw';
const REQUEST_KEY = 'O43z0dpjhgX20SCx4KAo';
const WAA_URL = 'https://jnn-pa.googleapis.com/$rpc/google.internal.waa.v1.Waa/GenerateIT';

let minterPromise = null;
let dom;

const challengeValue = (challenge, snake, camel) => challenge?.[snake] ?? challenge?.[camel];

const installDom = () => {
  if (dom) return;
  dom = new JSDOM('<!doctype html><html><head></head><body></body></html>', {
    url: 'https://www.youtube.com/',
    pretendToBeVisual: true,
  });
  for (const [name, value] of Object.entries({
    window: dom.window,
    document: dom.window.document,
    navigator: dom.window.navigator,
    location: dom.window.location,
    localStorage: dom.window.localStorage,
    sessionStorage: dom.window.sessionStorage,
  })) {
    Object.defineProperty(globalThis, name, { value, configurable: true, writable: true });
  }
};

const getIntegrityToken = async (botguardResponse) => {
  const response = await fetch(WAA_URL, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json+protobuf',
      'x-goog-api-key': WAA_API_KEY,
      'x-user-agent': 'grpc-web-javascript/0.1',
    },
    body: JSON.stringify([REQUEST_KEY, botguardResponse]),
  });
  if (!response.ok) throw new Error(`BotGuard integrity request failed with ${response.status}`);
  const data = await response.json();
  return {
    integrityToken: data?.[0],
    estimatedTtlSecs: data?.[1],
    mintRefreshThreshold: data?.[2],
    websafeFallbackToken: data?.[3],
  };
};

const createMinter = async (yt) => {
  const challengeResponse = await yt.getAttestationChallenge('ENGAGEMENT_TYPE_UNBOUND');
  const challenge = challengeResponse?.bg_challenge;
  if (!challenge) throw new Error('YouTube did not return a BotGuard challenge');

  installDom();
  const globalName = challengeValue(challenge, 'global_name', 'globalName');
  const program = challengeValue(challenge, 'program');
  const inlineScript = challengeValue(challenge, 'interpreter_javascript', 'interpreterJavascript')
    ?.private_do_not_access_or_else_safe_script_wrapped_value;
  const wrappedUrl = challengeValue(challenge, 'interpreter_url', 'interpreterUrl')
    ?.private_do_not_access_or_else_trusted_resource_url_wrapped_value;
  const interpreterJavascript = inlineScript || (wrappedUrl ? await (await fetch(`https:${wrappedUrl}`)).text() : '');
  if (!globalName || !program || !interpreterJavascript) throw new Error('Incomplete BotGuard challenge');

  new Function(interpreterJavascript)();
  const botguard = await BotGuardClient.create({
    program,
    globalName,
    globalObject: globalThis,
  });
  const webPoSignalOutput = [];
  const botguardResponse = await botguard.snapshot({ webPoSignalOutput });
  const integrityToken = await getIntegrityToken(botguardResponse);
  return WebPoMinter.create(integrityToken, webPoSignalOutput);
};

export const getPoToken = async (yt, videoId) => {
  minterPromise ||= createMinter(yt);
  const minter = await minterPromise;
  return minter.mintAsWebsafeString(videoId);
};

export const resetPoToken = () => {
  minterPromise = null;
};
