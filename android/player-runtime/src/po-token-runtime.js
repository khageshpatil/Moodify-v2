import { WebPoMinter } from 'bgutils-js/webpo';
import { parseLooseJSON } from 'bgutils-js/utils';

let botGuardVm = null;
let botGuardShutdown = null;
let webPoSignalOutput = null;
let webPoMinter = null;
let botGuardClientErrorCount = 0;

function parsePage(html) {
  const ytcfgMatch = html.match(/ytcfg\.set\(({.+?})\);/s);
  const challengeMatch = html.match(/window\.ytAtN\(\s*({[\s\S]*?})\s*\)/);
  if (!ytcfgMatch) throw new Error('YouTube homepage did not contain ytcfg');
  if (!challengeMatch) throw new Error('YouTube homepage did not contain a BotGuard challenge');

  const ytcfg = parseLooseJSON(ytcfgMatch[1]);
  const attestation = parseLooseJSON(challengeMatch[1]);
  const challenge = attestation?.R?.bgChallenge ?? attestation?.R?.bg_challenge;
  if (!challenge?.program || !challenge?.globalName || !challenge?.interpreterUrl) {
    throw new Error('YouTube homepage returned an incomplete BotGuard challenge');
  }
  const interpreterUrl = challenge.interpreterUrl.privateDoNotAccessOrElseTrustedResourceUrlWrappedValue;
  if (!interpreterUrl) throw new Error('BotGuard challenge did not include an interpreter URL');

  return JSON.stringify({
    ytcfg,
    challenge: {
      program: challenge.program,
      globalName: challenge.globalName,
      interpreterUrl,
    },
    visitorData: ytcfg.VISITOR_DATA ?? ytcfg.INNERTUBE_CONTEXT?.client?.visitorData ?? null,
  });
}

async function initialize(challengeJson, interpreterJavascript) {
  const payload = JSON.parse(challengeJson);
  const challenge = payload.challenge;
  globalThis.yt = { config_: payload.ytcfg };

  const scriptId = `moodify-botguard-${challenge.globalName}`;
  document.getElementById(scriptId)?.remove();
  const script = document.createElement('script');
  script.id = scriptId;
  script.type = 'text/javascript';
  script.textContent = interpreterJavascript;
  document.head.appendChild(script);

  botGuardVm = globalThis[challenge.globalName];
  if (!botGuardVm || typeof botGuardVm.a !== 'function') {
    throw new Error('BotGuard interpreter did not expose its VM');
  }
  webPoSignalOutput = [];
  webPoMinter = null;
  botGuardClientErrorCount = 0;
  let asyncSnapshotFunction = null;
  let resolveVmFunctions;
  let setupTimeout;
  const vmFunctionsReady = new Promise((resolve, reject) => {
    setupTimeout = setTimeout(() => reject(new Error('BotGuard VM setup timed out')), 15_000);
    resolveVmFunctions = () => {
      clearTimeout(setupTimeout);
      resolve();
    };
  });
  const vmInitialization = Promise.resolve().then(() => botGuardVm.a(
    challenge.program,
    (snapshotFunction, shutdownFunction) => {
      asyncSnapshotFunction = snapshotFunction;
      botGuardShutdown = shutdownFunction;
      resolveVmFunctions();
    },
    true,
    undefined,
    () => {},
    [[], []],
    undefined,
    false,
    [
      () => {},
      () => { botGuardClientErrorCount += 1; },
      () => {},
      () => {},
      () => {},
    ],
  ));
  const [vmInitializationResult] = await Promise.all([vmInitialization, vmFunctionsReady]);
  if (typeof asyncSnapshotFunction !== 'function') {
    throw new Error('BotGuard did not expose an asynchronous snapshot function');
  }
  const botguardResponse = await new Promise((resolve, reject) => {
    const timeout = setTimeout(() => reject(new Error('BotGuard snapshot timed out')), 15_000);
    let settled = false;
    const finish = (callback, value) => {
      if (settled) return;
      settled = true;
      clearTimeout(timeout);
      callback(value);
    };
    try {
      asyncSnapshotFunction(
        (response) => finish(resolve, response),
        [undefined, undefined, webPoSignalOutput, undefined],
      );
    } catch (error) {
      finish(reject, error);
    }
  });
  return JSON.stringify({
    botguardResponse,
    visitorData: payload.visitorData,
    webPoSignalOutputReady: typeof webPoSignalOutput[0] === 'function',
    webPoSignalOutputCount: webPoSignalOutput.length,
    clientErrorCount: botGuardClientErrorCount,
    snapshotFunctionReady: typeof asyncSnapshotFunction === 'function',
    syncSnapshotFunctionReady: typeof vmInitializationResult?.[0] === 'function',
    pageOrigin: location.origin,
    pageVisible: document.visibilityState,
    eventIdPresent: typeof globalThis.yt?.config_?.EVENT_ID === 'string',
    viewportWidth: innerWidth,
    viewportHeight: innerHeight,
    canvasAvailable: !!document.createElement('canvas').getContext('2d'),
  });
}

async function createMinter(integrityTokenJson) {
  if (!webPoSignalOutput) throw new Error('BotGuard session has not been initialized');
  const minterData = JSON.parse(integrityTokenJson);
  webPoMinter = await WebPoMinter.create(minterData, webPoSignalOutput);
  return 'ready';
}

async function mint(contentBinding) {
  if (!webPoMinter) throw new Error('PO-token minter is not ready');
  return webPoMinter.mintAsWebsafeString(contentBinding);
}

async function shutdown() {
  if (typeof botGuardShutdown === 'function') {
    try {
      await botGuardShutdown();
    } catch {
      // The session may already have expired inside BotGuard.
    }
  }
  botGuardVm = null;
  botGuardShutdown = null;
  webPoSignalOutput = null;
  webPoMinter = null;
  botGuardClientErrorCount = 0;
  return 'cleared';
}

globalThis.MoodifyPoTokenRuntime = Object.freeze({
  parsePage,
  initialize,
  createMinter,
  mint,
  shutdown,
});
