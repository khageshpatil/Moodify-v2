#!/usr/bin/env node
/**
 * Generates platform files from design-tokens/moodify.tokens.json so Android
 * and the web share one design language. Run after editing the tokens:
 *
 *   node scripts/build-tokens.mjs          # write the files
 *   node scripts/build-tokens.mjs --check  # fail if they are out of date (CI)
 */
import { readFileSync, writeFileSync, existsSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const tokens = JSON.parse(readFileSync(resolve(root, 'design-tokens/moodify.tokens.json'), 'utf8'));
const HEADER = 'Generated from design-tokens/moodify.tokens.json by scripts/build-tokens.mjs. Do not edit.';

const kebab = (s) => s.replace(/[A-Z]/g, (c) => `-${c.toLowerCase()}`);

function css() {
  const vars = (palette) => Object.entries(palette).map(([k, v]) => `  --aura-${kebab(k)}: ${v};`).join('\n');
  const lines = [
    `/* ${HEADER} */`,
    '',
    // Shared tokens also sit on [data-theme] and .aura so they outrank the classic
    // app's same-named variables (index.css) in every theme, not only dark.
    ':root,',
    ':root[data-theme],',
    '.aura {',
    ...Object.entries(tokens.radius).map(([k, v]) => `  --radius-${k}: ${v}px;`),
    ...Object.entries(tokens.spacing).map(([k, v]) => `  --space-${k}: ${v}px;`),
    ...Object.entries(tokens.moodHues).map(([k, v]) => `  --mood-${k}: ${v};`),
    `  --motion-short: ${tokens.motion.short}ms;`,
    `  --motion-medium: ${tokens.motion.medium}ms;`,
    `  --motion-long: ${tokens.motion.long}ms;`,
    `  --ease-emphasized: cubic-bezier(${tokens.motion.emphasized.join(', ')});`,
    `  --ease-emphasized-decelerate: cubic-bezier(${tokens.motion.emphasizedDecelerate.join(', ')});`,
    `  --font-sans: "${tokens.font.family}", ui-sans-serif, system-ui, sans-serif;`,
    '}',
    '',
    ':root,',
    ':root[data-theme="dark"] {',
    vars(tokens.color.dark),
    '  color-scheme: dark;',
    '}',
    '',
    ':root[data-theme="light"] {',
    vars(tokens.color.light),
    '  color-scheme: light;',
    '}',
    '',
    '@media (prefers-color-scheme: light) {',
    '  :root[data-theme="system"] {',
    vars(tokens.color.light).replace(/^ {2}/gm, '    '),
    '    color-scheme: light;',
    '  }',
    '}',
    '',
    ...Object.entries(tokens.type).map(([name, [size, line, weight, tracking]]) =>
      `.type-${kebab(name)} { font-family: var(--font-sans); font-size: ${size}px; line-height: ${line}px; font-weight: ${weight}; letter-spacing: ${tracking}em; }`),
    '',
  ];
  return lines.join('\n');
}

function kotlin() {
  const color = (hex) => `Color(0xFF${hex.slice(1).toUpperCase()})`;
  const palette = (name, p) => [
    `internal object ${name} {`,
    ...Object.entries(p).map(([k, v]) => `    val ${k} = ${color(v)}`),
    '}',
  ].join('\n');
  const weight = (w) => ({ 400: 'Normal', 500: 'Medium', 600: 'SemiBold', 700: 'Bold', 800: 'ExtraBold' })[w];
  return [
    `// ${HEADER}`,
    'package com.moodify.app.ui.theme',
    '',
    'import androidx.compose.ui.graphics.Color',
    'import androidx.compose.ui.text.font.FontWeight',
    'import androidx.compose.ui.unit.Dp',
    'import androidx.compose.ui.unit.dp',
    '',
    palette('AuraDark', tokens.color.dark),
    '',
    palette('AuraLight', tokens.color.light),
    '',
    '/** Seed hues for mood tiles and placeholders, chosen to read well on both themes. */',
    'internal val MoodHues = listOf(',
    ...Object.entries(tokens.moodHues).map(([k, v]) => `    ${color(v)}, // ${k}`),
    ')',
    '',
    '/** One radius per role. */',
    'object Radii {',
    ...Object.entries(tokens.radius).map(([k, v]) => `    val ${k}: Dp = ${v}.dp`),
    '}',
    '',
    '/** 4dp grid. Screens use [gutter] at the edges and [section] between sections. */',
    'object Spacing {',
    ...Object.entries(tokens.spacing).map(([k, v]) => `    val ${k}: Dp = ${v}.dp`),
    '}',
    '',
    '/** Size / line height (sp), weight and tracking (em) for each type role. */',
    'internal class TypeToken(val size: Int, val line: Int, val weight: FontWeight, val tracking: Double)',
    '',
    'internal object TypeScale {',
    ...Object.entries(tokens.type).map(([k, [s, l, w, t]]) => `    val ${k} = TypeToken(${s}, ${l}, FontWeight.${weight(w)}, ${Number.isInteger(t) ? t.toFixed(1) : t})`),
    '}',
    '',
  ].join('\n');
}

const outputs = {
  'src/aura/tokens.css': css(),
  'android/app/src/main/java/com/example/myapplication/ui/theme/GeneratedTokens.kt': kotlin(),
};

const check = process.argv.includes('--check');
let stale = false;
for (const [path, content] of Object.entries(outputs)) {
  const full = resolve(root, path);
  const current = existsSync(full) ? readFileSync(full, 'utf8') : null;
  if (current === content) continue;
  if (check) {
    console.error(`out of date: ${path}`);
    stale = true;
  } else {
    writeFileSync(full, content);
    console.log(`wrote ${path}`);
  }
}
if (stale) process.exit(1);
