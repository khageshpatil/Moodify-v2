import { build } from 'esbuild';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const output = resolve(root, '../app/src/main/assets/player/youtubei-player-runtime.js');
const poTokenOutput = resolve(root, '../app/src/main/assets/player/bgutils-po-token-runtime.js');
const noticesOutput = resolve(root, '../app/src/main/assets/licenses/player-runtime-third-party-notices.txt');
await mkdir(dirname(noticesOutput), { recursive: true });

const licenseFiles = [
  ['youtubei.js 18.3.0 — MIT License', resolve(root, 'node_modules/youtubei.js/LICENSE')],
  ['whatwg-url 14.2.0 — MIT License', resolve(root, 'node_modules/whatwg-url/LICENSE.txt')],
  ['fast-text-encoding 1.0.6 — Apache License 2.0', resolve(root, 'node_modules/fast-text-encoding/LICENSE')],
  ['bgutils-js 4.0.3 — MIT License', resolve(root, 'node_modules/bgutils-js/LICENSE')],
];
const noticeSections = await Promise.all(
  licenseFiles.map(async ([title, path]) => title + '\n\n' + (await readFile(path, 'utf8')).trim()),
);
await writeFile(
  noticesOutput,
  [
    'Moodify Android player runtime — third-party notices',
    'This runtime bundles the following packages. Their full license texts follow.',
    '',
    noticeSections.join('\n\n' + '='.repeat(72) + '\n\n'),
    '',
  ].join('\n'),
);

await build({
  entryPoints: [resolve(root, 'src/runtime.js')],
  outfile: output,
  bundle: true,
  format: 'iife',
  platform: 'browser',
  target: 'es2020',
  minify: true,
  legalComments: 'inline',
  banner: {
    js: [
      '/*! Moodify Android player runtime: youtubei.js 18.3.0 (MIT), whatwg-url 14.2.0 (MIT), fast-text-encoding 1.0.6 (Apache-2.0). */',
      'globalThis.window = globalThis;',
    ].join('\n'),
  },
});

await build({
  entryPoints: [resolve(root, 'src/po-token-runtime.js')],
  outfile: poTokenOutput,
  bundle: true,
  format: 'iife',
  platform: 'browser',
  target: 'es2020',
  minify: true,
  legalComments: 'inline',
  banner: {
    js: '/*! Moodify Android PO-token runtime: bgutils-js 4.0.3 (MIT). */',
  },
});

console.log(`Built ${output} and ${poTokenOutput}`);
