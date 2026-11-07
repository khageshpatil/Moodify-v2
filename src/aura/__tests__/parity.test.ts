import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { identityFromSeed, seedToWords } from '@social/client-keys.mjs';
import { normalizeCode } from '@social/identity.mjs';
import { MOODS } from '../moods';

/** The web and Android must agree: same identities, same moods, same tokens. */
describe('web ↔ Android parity', () => {
  it('derives the published identity vector through the shared module', async () => {
    const seed = Uint8Array.from({ length: 32 }, (_, i) => i);
    const identity = await identityFromSeed(seed);
    expect(identity.code).toBe('MOOD-67AF-HHK3');
    expect((await seedToWords(seed))[0]).toBe('abandon');
    expect(normalizeCode('mood 67af hhk3')).toBe('MOOD-67AF-HHK3');
  });

  it('has the same moods, in the same order, with the same queries as Android', () => {
    const kotlin = readFileSync(resolve(__dirname, '../../../android/app/src/main/java/com/example/myapplication/ui/designsystem/Moods.kt'), 'utf8');
    const android = [...kotlin.matchAll(/MoodOption\("(\w+)", "([^"]+)", "([^"]+)"/g)].map(([, id, label, query]) => ({ id, label, query }));
    expect(MOODS.map(({ id, label, query }) => ({ id, label, query }))).toEqual(android);
  });

  it('uses design tokens generated from the shared token file', () => {
    const tokens = JSON.parse(readFileSync(resolve(__dirname, '../../../design-tokens/moodify.tokens.json'), 'utf8'));
    const css = readFileSync(resolve(__dirname, '../tokens.css'), 'utf8');
    for (const [name, value] of Object.entries<string>(tokens.color.dark)) {
      expect(css).toContain(`--aura-${name.replace(/[A-Z]/g, (c) => `-${c.toLowerCase()}`)}: ${value};`);
    }
  });
});
