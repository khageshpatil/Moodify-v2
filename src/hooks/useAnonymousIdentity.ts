import { useState, useEffect, useCallback } from 'react';
import { toast } from './use-toast';

// Storage keys
const STORAGE_KEY_IDENTITY = 'moodify_identity';
const STORAGE_KEY_EXPORT_CODE = 'moodify_identity_export_code';

// Identity interface
export interface MoodifyIdentity {
  anonId: string;
  displayName: string;
  avatarSeed: string;
  createdAt: number;
}

// Helper functions for identity management
const generateUUID = (): string => {
  // Generate UUID v4
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = Math.random() * 16 | 0;
    const v = c === 'x' ? r : (r & 0x3 | 0x8);
    return v.toString(16);
  });
};

const generateAvatarSeed = (): string => {
  // Generate random seed for avatar
  const chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789';
  let seed = '';
  for (let i = 0; i < 16; i++) {
    seed += chars.charAt(Math.floor(Math.random() * chars.length));
  }
  return seed;
};

const generateSuggestedNickname = (): string => {
  const adjectives = ['Cool', 'Chill', 'Groovy', 'Funky', 'Smooth', 'Jazzy', 'Mellow', 'Vibey', 'Cosmic', 'Dreamy'];
  const nouns = ['Melody', 'Rhythm', 'Beat', 'Tune', 'Vibe', 'Wave', 'Echo', 'Soul', 'Sound', 'Note'];
  const randomAdj = adjectives[Math.floor(Math.random() * adjectives.length)];
  const randomNoun = nouns[Math.floor(Math.random() * nouns.length)];
  const randomNum = Math.floor(Math.random() * 100);
  return `${randomAdj}${randomNoun}${randomNum}`;
};

// Base58 encoding (Bitcoin-style)
const BASE58_ALPHABET = '123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz';

const encodeBase58 = (buffer: Uint8Array): string => {
  let num = BigInt(0);
  for (let i = 0; i < buffer.length; i++) {
    num = num * BigInt(256) + BigInt(buffer[i]);
  }
  
  let encoded = '';
  while (num > 0) {
    const remainder = Number(num % BigInt(58));
    encoded = BASE58_ALPHABET[remainder] + encoded;
    num = num / BigInt(58);
  }
  
  // Add leading zeros
  for (let i = 0; i < buffer.length && buffer[i] === 0; i++) {
    encoded = BASE58_ALPHABET[0] + encoded;
  }
  
  return encoded;
};

const decodeBase58 = (str: string): Uint8Array => {
  let num = BigInt(0);
  for (let i = 0; i < str.length; i++) {
    const char = str[i];
    const charIndex = BASE58_ALPHABET.indexOf(char);
    if (charIndex === -1) throw new Error('Invalid Base58 character');
    num = num * BigInt(58) + BigInt(charIndex);
  }
  
  const bytes: number[] = [];
  while (num > 0) {
    bytes.unshift(Number(num % BigInt(256)));
    num = num / BigInt(256);
  }
  
  // Add leading zeros
  for (let i = 0; i < str.length && str[i] === BASE58_ALPHABET[0]; i++) {
    bytes.unshift(0);
  }
  
  return new Uint8Array(bytes);
};

// Simple checksum using XOR
const calculateChecksum = (data: string): number => {
  let checksum = 0;
  for (let i = 0; i < data.length; i++) {
    checksum ^= data.charCodeAt(i);
  }
  return checksum;
};

// Export identity as recovery code
const exportIdentityToCode = (identity: MoodifyIdentity): string => {
  try {
    // Pack data: anonId + avatarSeed + displayName + checksum
    const data = `${identity.anonId}|${identity.avatarSeed}|${identity.displayName}`;
    const checksum = calculateChecksum(data);
    const dataWithChecksum = `${data}|${checksum}`;
    
    // Convert to bytes and encode
    const encoder = new TextEncoder();
    const bytes = encoder.encode(dataWithChecksum);
    const encoded = encodeBase58(bytes);
    
    return encoded;
  } catch (err) {
    console.error('Failed to export identity:', err);
    throw new Error('Failed to export identity');
  }
};

// Import identity from recovery code
const importIdentityFromCode = (code: string): MoodifyIdentity => {
  try {
    // Decode
    const bytes = decodeBase58(code);
    const decoder = new TextDecoder();
    const decoded = decoder.decode(bytes);
    
    // Parse data
    const parts = decoded.split('|');
    if (parts.length !== 4) throw new Error('Invalid recovery code format');
    
    const [anonId, avatarSeed, displayName, checksumStr] = parts;
    const checksum = parseInt(checksumStr);
    
    // Verify checksum
    const data = `${anonId}|${avatarSeed}|${displayName}`;
    const expectedChecksum = calculateChecksum(data);
    
    if (checksum !== expectedChecksum) {
      throw new Error('Invalid recovery code checksum');
    }
    
    // Validate UUID format
    const uuidRegex = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
    if (!uuidRegex.test(anonId)) {
      throw new Error('Invalid identity format');
    }
    
    return {
      anonId,
      avatarSeed,
      displayName,
      createdAt: Date.now(),
    };
  } catch (err) {
    console.error('Failed to import identity:', err);
    throw new Error('Invalid recovery code');
  }
};

// Load identity from localStorage
const loadIdentity = (): MoodifyIdentity | null => {
  try {
    const stored = localStorage.getItem(STORAGE_KEY_IDENTITY);
    if (!stored) return null;
    
    const identity = JSON.parse(stored);
    
    // Validate
    if (!identity.anonId || !identity.displayName || !identity.avatarSeed) {
      return null;
    }
    
    return identity;
  } catch (err) {
    console.error('Failed to load identity:', err);
    return null;
  }
};

// Save identity to localStorage
const saveIdentity = (identity: MoodifyIdentity): void => {
  try {
    localStorage.setItem(STORAGE_KEY_IDENTITY, JSON.stringify(identity));
  } catch (err) {
    console.error('Failed to save identity:', err);
    throw new Error('Failed to save identity');
  }
};

// Hook
export const useAnonymousIdentity = () => {
  const [identity, setIdentity] = useState<MoodifyIdentity | null>(null);
  const [isOnboardingRequired, setIsOnboardingRequired] = useState<boolean>(false);
  const [isLoading, setIsLoading] = useState<boolean>(true);

  // Load identity on mount
  useEffect(() => {
    const loaded = loadIdentity();
    if (loaded) {
      setIdentity(loaded);
      setIsOnboardingRequired(false);
    } else {
      setIsOnboardingRequired(true);
    }
    setIsLoading(false);
  }, []);

  // Get current identity
  const getIdentity = useCallback((): MoodifyIdentity | null => {
    return identity;
  }, [identity]);

  // Create new identity
  const createIdentity = useCallback((displayName?: string): MoodifyIdentity => {
    const newIdentity: MoodifyIdentity = {
      anonId: generateUUID(),
      displayName: displayName || generateSuggestedNickname(),
      avatarSeed: generateAvatarSeed(),
      createdAt: Date.now(),
    };
    
    saveIdentity(newIdentity);
    setIdentity(newIdentity);
    setIsOnboardingRequired(false);
    
    toast({
      title: 'Welcome to Moodify!',
      description: `You're now known as ${newIdentity.displayName}`,
    });
    
    return newIdentity;
  }, []);

  // Update display name
  const updateDisplayName = useCallback((newName: string): void => {
    if (!identity) {
      throw new Error('No identity exists');
    }
    
    if (!newName || newName.trim().length === 0) {
      toast({
        title: 'Invalid Name',
        description: 'Please enter a valid display name',
        variant: 'destructive',
      });
      return;
    }
    
    if (newName.length > 20) {
      toast({
        title: 'Name Too Long',
        description: 'Display name must be 20 characters or less',
        variant: 'destructive',
      });
      return;
    }
    
    const updatedIdentity = {
      ...identity,
      displayName: newName.trim(),
    };
    
    saveIdentity(updatedIdentity);
    setIdentity(updatedIdentity);
    
    toast({
      title: 'Name Updated',
      description: `You're now known as ${newName.trim()}`,
    });
  }, [identity]);

  // Export identity
  const exportIdentity = useCallback((): string => {
    if (!identity) {
      throw new Error('No identity to export');
    }
    
    try {
      const code = exportIdentityToCode(identity);
      
      // Also save to localStorage for backup
      localStorage.setItem(STORAGE_KEY_EXPORT_CODE, code);
      
      // Copy to clipboard
      if (navigator.clipboard) {
        navigator.clipboard.writeText(code).then(() => {
          toast({
            title: 'Identity Copied!',
            description: 'Your recovery code has been copied to clipboard',
          });
        }).catch(() => {
          toast({
            title: 'Recovery Code Generated',
            description: 'Please save this code safely',
          });
        });
      } else {
        toast({
          title: 'Recovery Code Generated',
          description: 'Please save this code safely',
        });
      }
      
      return code;
    } catch (err) {
      toast({
        title: 'Export Failed',
        description: 'Failed to generate recovery code',
        variant: 'destructive',
      });
      throw err;
    }
  }, [identity]);

  // Import identity
  const importIdentity = useCallback((code: string): void => {
    try {
      const imported = importIdentityFromCode(code.trim());
      
      saveIdentity(imported);
      setIdentity(imported);
      setIsOnboardingRequired(false);
      
      toast({
        title: 'Identity Restored!',
        description: `Welcome back, ${imported.displayName}!`,
      });
    } catch (err) {
      toast({
        title: 'Import Failed',
        description: err instanceof Error ? err.message : 'Invalid recovery code',
        variant: 'destructive',
      });
      throw err;
    }
  }, []);

  // Reset identity
  const resetIdentity = useCallback((): void => {
    if (identity) {
      localStorage.removeItem(STORAGE_KEY_IDENTITY);
      localStorage.removeItem(STORAGE_KEY_EXPORT_CODE);
      setIdentity(null);
      setIsOnboardingRequired(true);
      
      toast({
        title: 'Identity Reset',
        description: 'Your identity has been cleared',
      });
    }
  }, [identity]);

  // Generate suggested nickname
  const getSuggestedNickname = useCallback((): string => {
    return generateSuggestedNickname();
  }, []);

  return {
    identity,
    isOnboardingRequired,
    isLoading,
    getIdentity,
    createIdentity,
    updateDisplayName,
    exportIdentity,
    importIdentity,
    resetIdentity,
    getSuggestedNickname,
  };
};

