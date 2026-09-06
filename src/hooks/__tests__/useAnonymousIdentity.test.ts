// Tests for useAnonymousIdentity hook
// To run these tests, first install vitest:
// npm install --save-dev vitest @testing-library/react @testing-library/react-hooks

import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { useAnonymousIdentity } from '../useAnonymousIdentity';

describe('useAnonymousIdentity', () => {
  beforeEach(() => {
    // Clear localStorage before each test
    localStorage.clear();
  });

  afterEach(() => {
    localStorage.clear();
  });

  describe('Identity Creation', () => {
    it('should require onboarding when no identity exists', () => {
      const { result } = renderHook(() => useAnonymousIdentity());
      
      expect(result.current.isOnboardingRequired).toBe(true);
      expect(result.current.identity).toBeNull();
    });

    it('should create a new identity with display name', () => {
      const { result } = renderHook(() => useAnonymousIdentity());
      
      act(() => {
        result.current.createIdentity('TestUser');
      });
      
      expect(result.current.identity).not.toBeNull();
      expect(result.current.identity?.displayName).toBe('TestUser');
      expect(result.current.identity?.anonId).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i);
      expect(result.current.identity?.avatarSeed).toHaveLength(16);
      expect(result.current.isOnboardingRequired).toBe(false);
    });

    it('should create identity with suggested nickname if none provided', () => {
      const { result } = renderHook(() => useAnonymousIdentity());
      
      act(() => {
        result.current.createIdentity();
      });
      
      expect(result.current.identity).not.toBeNull();
      expect(result.current.identity?.displayName).toBeDefined();
      expect(result.current.identity?.displayName.length).toBeGreaterThan(0);
    });

    it('should persist identity to localStorage', () => {
      const { result } = renderHook(() => useAnonymousIdentity());
      
      act(() => {
        result.current.createIdentity('PersistTest');
      });
      
      const stored = localStorage.getItem('moodify_identity');
      expect(stored).not.toBeNull();
      
      const parsed = JSON.parse(stored!);
      expect(parsed.displayName).toBe('PersistTest');
      expect(parsed.anonId).toBeDefined();
      expect(parsed.avatarSeed).toBeDefined();
      expect(parsed.createdAt).toBeDefined();
    });
  });

  describe('Identity Updates', () => {
    it('should update display name', () => {
      const { result } = renderHook(() => useAnonymousIdentity());
      
      act(() => {
        result.current.createIdentity('OriginalName');
      });
      
      const originalAnonId = result.current.identity?.anonId;
      const originalAvatarSeed = result.current.identity?.avatarSeed;
      
      act(() => {
        result.current.updateDisplayName('UpdatedName');
      });
      
      expect(result.current.identity?.displayName).toBe('UpdatedName');
      expect(result.current.identity?.anonId).toBe(originalAnonId);
      expect(result.current.identity?.avatarSeed).toBe(originalAvatarSeed);
    });

    it('should reject empty display names', () => {
      const { result } = renderHook(() => useAnonymousIdentity());
      
      act(() => {
        result.current.createIdentity('TestUser');
      });
      
      const originalName = result.current.identity?.displayName;
      
      act(() => {
        result.current.updateDisplayName('');
      });
      
      expect(result.current.identity?.displayName).toBe(originalName);
    });

    it('should reject display names longer than 20 characters', () => {
      const { result } = renderHook(() => useAnonymousIdentity());
      
      act(() => {
        result.current.createIdentity('TestUser');
      });
      
      const originalName = result.current.identity?.displayName;
      
      act(() => {
        result.current.updateDisplayName('ThisNameIsWayTooLongForOurSystem');
      });
      
      expect(result.current.identity?.displayName).toBe(originalName);
    });
  });

  describe('Identity Export/Import', () => {
    it('should export identity as recovery code', () => {
      const { result } = renderHook(() => useAnonymousIdentity());
      
      act(() => {
        result.current.createIdentity('ExportTest');
      });
      
      let exportCode: string = '';
      act(() => {
        exportCode = result.current.exportIdentity();
      });
      
      expect(exportCode).toBeDefined();
      expect(exportCode.length).toBeGreaterThan(0);
      // Base58 encoded string should only contain valid characters
      expect(exportCode).toMatch(/^[123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz]+$/);
    });

    it('should import identity from recovery code', () => {
      const { result } = renderHook(() => useAnonymousIdentity());
      
      // Create and export
      act(() => {
        result.current.createIdentity('ImportTest');
      });
      
      const originalIdentity = result.current.identity!;
      let exportCode: string = '';
      
      act(() => {
        exportCode = result.current.exportIdentity();
      });
      
      // Reset
      act(() => {
        result.current.resetIdentity();
      });
      
      expect(result.current.identity).toBeNull();
      
      // Import
      act(() => {
        result.current.importIdentity(exportCode);
      });
      
      expect(result.current.identity).not.toBeNull();
      expect(result.current.identity?.displayName).toBe(originalIdentity.displayName);
      expect(result.current.identity?.anonId).toBe(originalIdentity.anonId);
      expect(result.current.identity?.avatarSeed).toBe(originalIdentity.avatarSeed);
    });

    it('should reject invalid recovery codes', () => {
      const { result } = renderHook(() => useAnonymousIdentity());
      
      act(() => {
        result.current.createIdentity('TestUser');
      });
      
      expect(() => {
        act(() => {
          result.current.importIdentity('invalid-code-123');
        });
      }).toThrow();
    });
  });

  describe('Identity Reset', () => {
    it('should reset identity and require onboarding', () => {
      const { result } = renderHook(() => useAnonymousIdentity());
      
      act(() => {
        result.current.createIdentity('ResetTest');
      });
      
      expect(result.current.identity).not.toBeNull();
      expect(result.current.isOnboardingRequired).toBe(false);
      
      act(() => {
        result.current.resetIdentity();
      });
      
      expect(result.current.identity).toBeNull();
      expect(result.current.isOnboardingRequired).toBe(true);
      expect(localStorage.getItem('moodify_identity')).toBeNull();
    });
  });

  describe('Identity Persistence', () => {
    it('should load existing identity from localStorage on mount', () => {
      // Pre-populate localStorage
      const testIdentity = {
        anonId: '12345678-1234-4123-8123-123456789012',
        displayName: 'PreloadedUser',
        avatarSeed: 'abcdef1234567890',
        createdAt: Date.now(),
      };
      
      localStorage.setItem('moodify_identity', JSON.stringify(testIdentity));
      
      const { result } = renderHook(() => useAnonymousIdentity());
      
      expect(result.current.identity).not.toBeNull();
      expect(result.current.identity?.displayName).toBe('PreloadedUser');
      expect(result.current.identity?.anonId).toBe(testIdentity.anonId);
      expect(result.current.isOnboardingRequired).toBe(false);
    });
  });

  describe('Suggested Nicknames', () => {
    it('should generate valid suggested nicknames', () => {
      const { result } = renderHook(() => useAnonymousIdentity());
      
      const nickname = result.current.getSuggestedNickname();
      
      expect(nickname).toBeDefined();
      expect(nickname.length).toBeGreaterThan(0);
      expect(nickname.length).toBeLessThanOrEqual(20);
    });

    it('should generate different nicknames on subsequent calls', () => {
      const { result } = renderHook(() => useAnonymousIdentity());
      
      const nickname1 = result.current.getSuggestedNickname();
      const nickname2 = result.current.getSuggestedNickname();
      const nickname3 = result.current.getSuggestedNickname();
      
      // While there's a small chance they could be the same, it's very unlikely
      const uniqueNicknames = new Set([nickname1, nickname2, nickname3]);
      expect(uniqueNicknames.size).toBeGreaterThan(1);
    });
  });
});

