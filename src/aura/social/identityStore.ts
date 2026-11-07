/**
 * Keeps the identity seed in IndexedDB, encrypted with a non-extractable
 * AES-GCM key that also lives in IndexedDB. Script on the page can use the key
 * but never read its bytes, so a copied database alone is useless.
 */
export interface StoredIdentity {
  seed: Uint8Array;
  displayName: string;
  registered: boolean;
  backedUp: boolean;
}

interface SealedRecord {
  key: CryptoKey;
  iv: Uint8Array;
  sealed: ArrayBuffer;
  displayName: string;
  registered: boolean;
  backedUp: boolean;
}

const DB = 'moodify-identity';
const STORE = 'identity';
const ID = 'me';

function openDb(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(DB, 1);
    request.onupgradeneeded = () => request.result.createObjectStore(STORE);
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
}

async function tx<T>(mode: IDBTransactionMode, run: (store: IDBObjectStore) => IDBRequest<T>): Promise<T> {
  const db = await openDb();
  return new Promise((resolve, reject) => {
    const request = run(db.transaction(STORE, mode).objectStore(STORE));
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  }).finally(() => db.close()) as Promise<T>;
}

export async function loadIdentity(): Promise<StoredIdentity | null> {
  const record = await tx<SealedRecord | undefined>('readonly', (s) => s.get(ID));
  if (!record) return null;
  try {
    const seed = new Uint8Array(await crypto.subtle.decrypt({ name: 'AES-GCM', iv: record.iv }, record.key, record.sealed));
    return { seed, displayName: record.displayName, registered: record.registered, backedUp: record.backedUp };
  } catch {
    return null;
  }
}

export async function saveIdentity(identity: StoredIdentity): Promise<void> {
  const key = await crypto.subtle.generateKey({ name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt']);
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const sealed = await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, key, identity.seed);
  const record: SealedRecord = { key, iv, sealed, displayName: identity.displayName, registered: identity.registered, backedUp: identity.backedUp };
  await tx('readwrite', (s) => s.put(record, ID));
}

export async function updateIdentity(patch: Partial<Omit<StoredIdentity, 'seed'>>): Promise<void> {
  const record = await tx<SealedRecord | undefined>('readonly', (s) => s.get(ID));
  if (!record) return;
  await tx('readwrite', (s) => s.put({ ...record, ...patch }, ID));
}

export async function clearIdentity(): Promise<void> {
  await tx('readwrite', (s) => s.delete(ID));
}
