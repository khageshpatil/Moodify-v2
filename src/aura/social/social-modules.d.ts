// Types for the shared protocol modules in social/src (plain JavaScript).
declare module '@social/client-keys.mjs' {
  export interface Identity { signPrivate: Uint8Array; boxPrivate: Uint8Array; signPub: Uint8Array; boxPub: Uint8Array; code: string }
  export function identityFromSeed(seed: Uint8Array): Promise<Identity>;
  export function sign(signPrivate: Uint8Array, message: string): Promise<Uint8Array>;
  export function seedToWords(seed: Uint8Array): Promise<string[]>;
  export function wordsToSeed(words: string | string[]): Promise<Uint8Array>;
  export function seal(args: { myBoxPrivate: Uint8Array; myCode: string; theirBoxPub: Uint8Array; theirCode: string; plaintext: string; nonce?: Uint8Array }): Promise<{ nonce: Uint8Array; ciphertext: Uint8Array }>;
  export function open(args: { myBoxPrivate: Uint8Array; myCode: string; theirBoxPub: Uint8Array; theirCode: string; nonce: Uint8Array; ciphertext: Uint8Array }): Promise<string>;
  /** Opens a copy of a message I sent them (chat.md §2). */
  export function openOwn(args: { myBoxPrivate: Uint8Array; myCode: string; theirBoxPub: Uint8Array; theirCode: string; nonce: Uint8Array; ciphertext: Uint8Array }): Promise<string>;
  export function newLinkKeyPair(): Promise<{ privateKey: Uint8Array; publicKey: Uint8Array }>;
  export function sealSeedForLink(args: { seed: Uint8Array; devicePub: Uint8Array; linkId: string }): Promise<{ senderPub: Uint8Array; nonce: Uint8Array; ciphertext: Uint8Array }>;
  export function openLinkedSeed(args: { devicePrivate: Uint8Array; senderPub: Uint8Array; linkId: string; nonce: Uint8Array; ciphertext: Uint8Array }): Promise<Uint8Array>;
  export function linkCheckWords(devicePub: Uint8Array): Promise<[string, string]>;
}
declare module '@social/identity.mjs' {
  export function bytesToBase64Url(bytes: Uint8Array): string;
  export function base64UrlToBytes(value: string): Uint8Array;
  export function normalizeCode(input: string): string | null;
  export const signedMessages: {
    register(challenge: string, signPub: string, boxPub: string, displayName: string): string;
    login(challenge: string): string;
  };
}
