/**
 * Client-side encryption utilities for password-protected playlist sharing
 * Uses Web Crypto API for AES-GCM encryption
 */

// Convert string to ArrayBuffer
const stringToArrayBuffer = (str: string): ArrayBuffer => {
  const encoder = new TextEncoder();
  return encoder.encode(str);
};

// Convert ArrayBuffer to string
const arrayBufferToString = (buffer: ArrayBuffer): string => {
  const decoder = new TextDecoder();
  return decoder.decode(buffer);
};

// Convert ArrayBuffer to Base64
const arrayBufferToBase64 = (buffer: ArrayBuffer): string => {
  const bytes = new Uint8Array(buffer);
  let binary = '';
  for (let i = 0; i < bytes.length; i++) {
    binary += String.fromCharCode(bytes[i]);
  }
  return btoa(binary);
};

// Convert Base64 to ArrayBuffer
const base64ToArrayBuffer = (base64: string): ArrayBuffer => {
  const binary = atob(base64);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) {
    bytes[i] = binary.charCodeAt(i);
  }
  return bytes.buffer;
};

// Derive encryption key from password using PBKDF2
const deriveKey = async (password: string, salt: Uint8Array): Promise<CryptoKey> => {
  const passwordBuffer = stringToArrayBuffer(password);
  
  // Import password as key material
  const keyMaterial = await crypto.subtle.importKey(
    'raw',
    passwordBuffer,
    'PBKDF2',
    false,
    ['deriveKey']
  );
  
  // Derive AES-GCM key
  return await crypto.subtle.deriveKey(
    {
      name: 'PBKDF2',
      salt,
      iterations: 100000,
      hash: 'SHA-256',
    },
    keyMaterial,
    { name: 'AES-GCM', length: 256 },
    false,
    ['encrypt', 'decrypt']
  );
};

/**
 * Encrypt data with password
 * @param data - Plain text data to encrypt
 * @param password - User's password
 * @returns Encrypted data as Base64 string (includes salt and IV)
 */
export const encryptWithPassword = async (data: string, password: string): Promise<string> => {
  try {
    // Generate random salt and IV
    const salt = crypto.getRandomValues(new Uint8Array(16));
    const iv = crypto.getRandomValues(new Uint8Array(12));
    
    // Derive key from password
    const key = await deriveKey(password, salt);
    
    // Encrypt data
    const dataBuffer = stringToArrayBuffer(data);
    const encryptedBuffer = await crypto.subtle.encrypt(
      { name: 'AES-GCM', iv },
      key,
      dataBuffer
    );
    
    // Combine salt + iv + encrypted data
    const combined = new Uint8Array(salt.length + iv.length + encryptedBuffer.byteLength);
    combined.set(salt, 0);
    combined.set(iv, salt.length);
    combined.set(new Uint8Array(encryptedBuffer), salt.length + iv.length);
    
    // Convert to Base64
    return arrayBufferToBase64(combined.buffer);
  } catch (error) {
    console.error('Encryption failed:', error);
    throw new Error('Failed to encrypt data');
  }
};

/**
 * Decrypt data with password
 * @param encryptedData - Base64 encrypted data
 * @param password - User's password
 * @returns Decrypted plain text
 */
export const decryptWithPassword = async (
  encryptedData: string,
  password: string
): Promise<string> => {
  try {
    // Convert from Base64
    const combined = new Uint8Array(base64ToArrayBuffer(encryptedData));
    
    // Extract salt, IV, and encrypted data
    const salt = combined.slice(0, 16);
    const iv = combined.slice(16, 28);
    const encryptedBuffer = combined.slice(28);
    
    // Derive key from password
    const key = await deriveKey(password, salt);
    
    // Decrypt data
    const decryptedBuffer = await crypto.subtle.decrypt(
      { name: 'AES-GCM', iv },
      key,
      encryptedBuffer
    );
    
    // Convert to string
    return arrayBufferToString(decryptedBuffer);
  } catch (error) {
    console.error('Decryption failed:', error);
    throw new Error('Incorrect password or corrupted data');
  }
};

/**
 * Hash password for verification (not for encryption)
 * @param password - Password to hash
 * @returns Base64 hash
 */
export const hashPassword = async (password: string): Promise<string> => {
  const buffer = stringToArrayBuffer(password);
  const hashBuffer = await crypto.subtle.digest('SHA-256', buffer);
  return arrayBufferToBase64(hashBuffer);
};

/**
 * Generate secure random string for friend codes
 * @param length - Length of the string
 * @returns Random string
 */
export const generateSecureRandomString = (length: number = 12): string => {
  const chars = 'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789';
  const randomValues = crypto.getRandomValues(new Uint8Array(length));
  let result = '';
  for (let i = 0; i < length; i++) {
    result += chars[randomValues[i] % chars.length];
  }
  return result;
};

/**
 * Test if Web Crypto API is available
 */
export const isCryptoAvailable = (): boolean => {
  return typeof crypto !== 'undefined' && 
         typeof crypto.subtle !== 'undefined' &&
         typeof crypto.getRandomValues !== 'undefined';
};
