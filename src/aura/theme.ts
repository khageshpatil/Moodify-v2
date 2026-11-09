import { useEffect, useState } from 'react';

/** Settings › Appearance, as on Android: follow the device, or always light / dark. */
export type ThemeChoice = 'system' | 'light' | 'dark';
const KEY = 'aura.theme';
const listeners = new Set<(theme: ThemeChoice) => void>();

export function readTheme(): ThemeChoice {
  try {
    const saved = localStorage.getItem(KEY);
    return saved === 'light' || saved === 'system' || saved === 'dark' ? saved : 'dark';
  } catch {
    return 'dark';
  }
}

export function applyTheme(theme: ThemeChoice) {
  document.documentElement.dataset.theme = theme;
  const dark = theme === 'dark' || (theme === 'system' && window.matchMedia?.('(prefers-color-scheme: dark)').matches);
  document.querySelector('meta[name="theme-color"]')?.setAttribute('content', dark ? '#0B0B0F' : '#F8F7FB');
}

export function setTheme(theme: ThemeChoice) {
  try { localStorage.setItem(KEY, theme); } catch { /* private mode: this visit only */ }
  applyTheme(theme);
  listeners.forEach((listener) => listener(theme));
}

/** The current choice; re-renders when it changes. */
export function useTheme(): ThemeChoice {
  const [theme, set] = useState(readTheme);
  useEffect(() => {
    listeners.add(set);
    applyTheme(readTheme());
    const media = window.matchMedia?.('(prefers-color-scheme: dark)');
    const onSystem = () => { if (readTheme() === 'system') applyTheme('system'); };
    media?.addEventListener?.('change', onSystem);
    return () => { listeners.delete(set); media?.removeEventListener?.('change', onSystem); };
  }, []);
  return theme;
}
