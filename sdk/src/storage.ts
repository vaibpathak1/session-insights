/** Minimal string store. Browser storage when usable, otherwise in memory. */
export interface KeyValueStore {
  get(key: string): string | null;
  set(key: string, value: string): void;
}

export function memoryStore(): KeyValueStore {
  const map = new Map<string, string>();
  return {
    get: (key) => map.get(key) ?? null,
    set: (key, value) => void map.set(key, value),
  };
}

/**
 * Wraps `localStorage` / `sessionStorage`. Access can throw (blocked cookies, sandboxed
 * iframes, quota); on the first failure the store switches to memory for the rest of the page.
 */
export function browserStore(kind: 'localStorage' | 'sessionStorage'): KeyValueStore {
  const fallback = memoryStore();
  let storage: Storage | null;
  try {
    storage = globalThis[kind];
    const probe = '__si_probe__';
    storage.setItem(probe, probe);
    storage.removeItem(probe);
  } catch {
    storage = null;
  }
  return {
    get(key) {
      if (storage) {
        try {
          return storage.getItem(key);
        } catch {
          storage = null;
        }
      }
      return fallback.get(key);
    },
    set(key, value) {
      fallback.set(key, value); // keep memory in sync in case storage fails later
      if (storage) {
        try {
          storage.setItem(key, value);
        } catch {
          storage = null;
        }
      }
    },
  };
}
