import { Moon, Sun } from 'lucide-react';
import { useEffect, useState } from 'react';

type ThemeMode = 'light' | 'dark';

const STORAGE_KEY = 'moodify-theme';

const getInitialTheme = (): ThemeMode => {
  if (typeof window === 'undefined') return 'dark';
  const saved = window.localStorage.getItem(STORAGE_KEY);
  if (saved === 'light' || saved === 'dark') return saved;
  return window.matchMedia('(prefers-color-scheme: light)').matches ? 'light' : 'dark';
};

export const ThemeToggle = () => {
  const [theme, setTheme] = useState<ThemeMode>(getInitialTheme);

  useEffect(() => {
    document.documentElement.dataset.theme = theme;
    window.localStorage.setItem(STORAGE_KEY, theme);
  }, [theme]);

  const nextTheme = theme === 'dark' ? 'light' : 'dark';

  return (
    <button
      type="button"
      className="theme-toggle top-action-button"
      onClick={() => setTheme(nextTheme)}
      aria-label={`Switch to ${nextTheme} mode`}
      aria-pressed={theme === 'light'}
      title={`Switch to ${nextTheme} mode`}
    >
      {theme === 'dark' ? <Sun size={17} aria-hidden="true" /> : <Moon size={17} aria-hidden="true" />}
    </button>
  );
};
