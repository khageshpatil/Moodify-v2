import { JsAnalyzer } from './node_modules/youtubei.js/dist/src/utils/javascript/JsAnalyzer.js';
import { JsExtractor } from './node_modules/youtubei.js/dist/src/utils/javascript/JsExtractor.js';
import { nsigMatcher } from './node_modules/youtubei.js/dist/src/utils/javascript/matchers.js';

const userAgent =
  'Mozilla/5.0 (Android 15; Mobile) AppleWebKit/537.36 Chrome/141.0.0.0 Mobile Safari/537.36';
const report = (stage) => {
  const memory = process.memoryUsage();
  console.log(JSON.stringify({
    stage,
    rssMiB: Math.round(memory.rss / 1024 / 1024),
    heapUsedMiB: Math.round(memory.heapUsed / 1024 / 1024),
    externalMiB: Math.round(memory.external / 1024 / 1024),
  }));
};

report('start');
const apiResponse = await fetch('https://www.youtube.com/iframe_api', {
  headers: { 'user-agent': userAgent },
});
if (!apiResponse.ok) throw new Error('iframe_api HTTP ' + apiResponse.status);
const apiSource = (await apiResponse.text()).replaceAll('\\/', '/');
const playerId = apiSource.match(/\/s\/player\/([A-Za-z0-9_-]{1,80})\//)?.[1];
if (!playerId) throw new Error('Could not detect player ID');

const scriptResponse = await fetch(
  'https://www.youtube.com/s/player/' + playerId + '/player_es6.vflset/en_US/base.js',
  { headers: { 'user-agent': userAgent } },
);
if (!scriptResponse.ok) throw new Error('player script HTTP ' + scriptResponse.status);
let source = await scriptResponse.text();
report('script loaded (' + source.length + ' chars; ' + playerId + ')');

let analyzer = new JsAnalyzer(source, {
  extractions: [{ friendlyName: 'nsigFunction', match: nsigMatcher }],
});
report('AST analyzed');
const extracted = new JsExtractor(analyzer).buildScript({
  disallowSideEffectInitializers: true,
});
if (!extracted.exported.includes('nsigFunction')) throw new Error('n transform missing');
report('transform extracted (' + extracted.output.length + ' chars)');

analyzer = null;
source = '';
global.gc?.();
report('after GC');
