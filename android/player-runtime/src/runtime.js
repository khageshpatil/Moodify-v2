import 'fast-text-encoding';
import { URL, URLSearchParams } from 'whatwg-url';
import { JsAnalyzer } from '../node_modules/youtubei.js/dist/src/utils/javascript/JsAnalyzer.js';
import { JsExtractor } from '../node_modules/youtubei.js/dist/src/utils/javascript/JsExtractor.js';
import { nsigMatcher } from '../node_modules/youtubei.js/dist/src/utils/javascript/matchers.js';
import { getNsigProcessorFn } from '../node_modules/youtubei.js/dist/src/utils/Utils.js';

globalThis.URL = URL;
globalThis.URLSearchParams = URLSearchParams;

if (typeof globalThis.structuredClone !== 'function') {
  globalThis.structuredClone = function cloneAstNode(value, seen = new Map()) {
    if (value === null || typeof value !== 'object') return value;
    if (seen.has(value)) return seen.get(value);
    const clone = Array.isArray(value) ? [] : {};
    seen.set(value, clone);
    for (const key of Object.keys(value)) clone[key] = cloneAstNode(value[key], seen);
    return clone;
  };
}

const MAX_PLAYER_CACHE_ENTRIES = 4;
const playerCache = new Map();
const extractionDefinitions = [{ friendlyName: 'nsigFunction', match: nsigMatcher }];

function getPlayerData(playerId, source) {
  const existing = playerCache.get(playerId);
  if (existing) {
    playerCache.delete(playerId);
    playerCache.set(playerId, existing);
    return existing;
  }

  const analyzer = new JsAnalyzer(source, { extractions: extractionDefinitions });
  const result = new JsExtractor(analyzer).buildScript({
    disallowSideEffectInitializers: true,
  });
  if (!result.exported.includes('nsigFunction')) {
    throw new Error('Pinned youtubei.js parser did not find the current signature/n transform');
  }

  const playerData = {
    output: result.output,
  };
  playerCache.set(playerId, playerData);
  if (playerCache.size > MAX_PLAYER_CACHE_ENTRIES) {
    playerCache.delete(playerCache.keys().next().value);
  }
  return playerData;
}

function createSafeProcessor(n, parameter, signature) {
  const markers = {
    n: '__MOODIFY_N_MARKER__',
    parameter: '__MOODIFY_PARAMETER_MARKER__',
    signature: '__MOODIFY_SIGNATURE_MARKER__',
  };
  const template = getNsigProcessorFn(markers.n, markers.parameter, markers.signature);
  const markerCall = `return process("${markers.n}", "${markers.parameter}", "${markers.signature}");`;
  const safeCall = `return process(${JSON.stringify(n ?? '')}, ${JSON.stringify(parameter ?? '')}, ${JSON.stringify(signature ?? '')});`;
  if (!template.includes(markerCall)) {
    throw new Error('Unexpected youtubei.js transform wrapper format');
  }
  return template.replace(markerCall, safeCall);
}

function transform(playerId, playerScript, signature, parameter, n) {
  if (!playerId || !/^[A-Za-z0-9_-]{1,80}$/.test(playerId)) {
    throw new Error('Invalid YouTube player id');
  }
  if (playerScript.length > 8 * 1024 * 1024) {
    throw new Error('YouTube player script exceeds the 8 MiB limit');
  }

  const player = getPlayerData(playerId, playerScript);
  const processor = createSafeProcessor(n, parameter, signature);
  const result = new Function(`${player.output}\n${processor}`)();
  if (!result || typeof result !== 'object') {
    throw new Error('YouTube player transform returned an invalid result');
  }

  return JSON.stringify({
    ok: true,
    signature: typeof result.sig === 'string' ? result.sig : null,
    n: typeof result.n === 'string' ? result.n : null,
  });
}

globalThis.MoodifyYoutubeScriptResolver = Object.freeze({ transform });
