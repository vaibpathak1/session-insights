import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { isUuid } from '../src/ids';
import { ANONYMOUS_ID_KEY, SESSION_KEY, SESSION_TIMEOUT_MS, SessionManager } from '../src/session';
import { browserStore, memoryStore, type KeyValueStore } from '../src/storage';
import { loadSdk, OPTIONS } from './helpers';

const MINUTE = 60_000;

function clock(start = 1_700_000_000_000) {
  let t = start;
  return { now: () => t, advance: (ms: number) => (t += ms) };
}

function stores() {
  return { local: memoryStore(), session: memoryStore() };
}

describe('SessionManager', () => {
  it('creates and persists ids; a reload in the same tab reuses them', () => {
    const s = stores();
    const c = clock();
    const first = new SessionManager({ ...s, sampleRate: 1, now: c.now });
    expect(isUuid(first.sessionId)).toBe(true);
    expect(isUuid(first.anonymousId)).toBe(true);
    c.advance(10 * MINUTE);
    const reloaded = new SessionManager({ ...s, sampleRate: 1, now: c.now });
    expect(reloaded.sessionId).toBe(first.sessionId);
    expect(reloaded.anonymousId).toBe(first.anonymousId);
  });

  it('rotates the session after 30 minutes of inactivity, keeping the anonymous id', () => {
    const c = clock();
    const m = new SessionManager({ ...stores(), sampleRate: 1, now: c.now });
    const { sessionId, anonymousId } = m;
    m.takeChunkSeq();

    c.advance(SESSION_TIMEOUT_MS); // exactly 30 min: still the same session
    expect(m.touch()).toBe(false);
    expect(m.sessionId).toBe(sessionId);

    c.advance(SESSION_TIMEOUT_MS + 1);
    expect(m.isIdle()).toBe(true);
    expect(m.touch()).toBe(true);
    expect(m.sessionId).not.toBe(sessionId);
    expect(m.anonymousId).toBe(anonymousId);
    expect(m.takeChunkSeq()).toBe(0); // chunkSeq restarts for the new session
  });

  it('starts a new session on reload after the timeout', () => {
    const s = stores();
    const c = clock();
    const first = new SessionManager({ ...s, sampleRate: 1, now: c.now });
    c.advance(31 * MINUTE);
    const reloaded = new SessionManager({ ...s, sampleRate: 1, now: c.now });
    expect(reloaded.sessionId).not.toBe(first.sessionId);
    expect(reloaded.anonymousId).toBe(first.anonymousId);
  });

  it('reset() rotates both the anonymous id and the session', () => {
    const s = stores();
    const m = new SessionManager({ ...s, sampleRate: 1 });
    const { sessionId, anonymousId } = m;
    m.reset();
    expect(m.sessionId).not.toBe(sessionId);
    expect(m.anonymousId).not.toBe(anonymousId);
    expect(s.local.get(ANONYMOUS_ID_KEY)).toBe(m.anonymousId);
  });

  it('continues chunkSeq across a reload in the same session', () => {
    const s = stores();
    const m = new SessionManager({ ...s, sampleRate: 1 });
    expect([m.takeChunkSeq(), m.takeChunkSeq()]).toEqual([0, 1]);
    expect(new SessionManager({ ...s, sampleRate: 1 }).takeChunkSeq()).toBe(2);
  });

  it('ignores a corrupt stored session', () => {
    const s = stores();
    s.session.set(SESSION_KEY, '{"id":"nope"');
    expect(isUuid(new SessionManager({ ...s, sampleRate: 1 }).sessionId)).toBe(true);
  });

  it('throttles lastActivity writes to about every 5 s', () => {
    const c = clock();
    const session = memoryStore();
    const set = vi.spyOn(session, 'set');
    const m = new SessionManager({ local: memoryStore(), session, sampleRate: 1, now: c.now });
    set.mockClear();
    for (let i = 0; i < 100; i++) {
      c.advance(100); // 100 activity events over 10 s
      m.touch();
    }
    expect(set.mock.calls.length).toBeLessThanOrEqual(3);
    expect(set.mock.calls.length).toBeGreaterThanOrEqual(1);
    m.persist();
    const stored = JSON.parse(set.mock.lastCall![1]) as { lastActivity: number };
    expect(stored.lastActivity).toBe(c.now());
  });

  describe('sampling', () => {
    it('sampleRate 0 never samples; 1 always does', () => {
      for (let i = 0; i < 20; i++) {
        expect(new SessionManager({ ...stores(), sampleRate: 0 }).sampled).toBe(false);
        expect(new SessionManager({ ...stores(), sampleRate: 1 }).sampled).toBe(true);
      }
    });

    it('is decided once per session and survives a reload', () => {
      const s = stores();
      const m = new SessionManager({ ...s, sampleRate: 0.25, random: () => 0.1 });
      expect(m.sampled).toBe(true);
      const reloaded = new SessionManager({ ...s, sampleRate: 0.25, random: () => 0.9 });
      expect(reloaded.sampled).toBe(true);
      expect(reloaded.sessionId).toBe(m.sessionId);
    });

    it('is decided again for a new session', () => {
      const c = clock();
      let r = 0.1;
      const m = new SessionManager({ ...stores(), sampleRate: 0.25, now: c.now, random: () => r });
      r = 0.9;
      c.advance(31 * MINUTE);
      m.touch();
      expect(m.sampled).toBe(false);
    });
  });
});

describe('browserStore', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('falls back to memory when storage access throws', () => {
    const blocked = {
      getItem: () => {
        throw new DOMException('blocked', 'SecurityError');
      },
      setItem: () => {
        throw new DOMException('blocked', 'SecurityError');
      },
      removeItem: () => {},
    };
    vi.stubGlobal('localStorage', blocked);
    const store = browserStore('localStorage');
    store.set('a', '1');
    expect(store.get('a')).toBe('1');
  });

  it('falls back to memory when the storage getter itself throws', () => {
    const original = Object.getOwnPropertyDescriptor(globalThis, 'sessionStorage');
    Object.defineProperty(globalThis, 'sessionStorage', {
      configurable: true,
      get() {
        throw new DOMException('blocked', 'SecurityError');
      },
    });
    try {
      const store: KeyValueStore = browserStore('sessionStorage');
      store.set('b', '2');
      expect(store.get('b')).toBe('2');
    } finally {
      if (original) Object.defineProperty(globalThis, 'sessionStorage', original);
    }
  });

  it('keeps working if storage starts failing later (quota)', () => {
    const store = browserStore('localStorage');
    store.set('c', '3');
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new DOMException('full', 'QuotaExceededError');
    });
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new DOMException('gone', 'SecurityError');
    });
    store.set('c', '4');
    expect(store.get('c')).toBe('4');
  });
});

describe('client session behaviour', () => {
  beforeEach(() => {
    localStorage.clear();
    sessionStorage.clear();
    vi.useFakeTimers({ now: 1_700_000_000_000 });
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it('rotates the session on activity after 30 min of inactivity', async () => {
    const sdk = await loadSdk();
    sdk.init({ ...OPTIONS });
    const first = sdk.getSessionId();
    expect(isUuid(first)).toBe(true);

    vi.advanceTimersByTime(10 * MINUTE);
    window.dispatchEvent(new Event('pointerdown'));
    expect(sdk.getSessionId()).toBe(first);

    vi.advanceTimersByTime(31 * MINUTE);
    window.dispatchEvent(new Event('keydown'));
    expect(sdk.getSessionId()).not.toBe(first);
    sdk.shutdown();
  });

  it('reset() rotates both ids', async () => {
    const sdk = await loadSdk();
    sdk.init({ ...OPTIONS });
    const session = sdk.getSessionId();
    const anon = localStorage.getItem(ANONYMOUS_ID_KEY);
    sdk.reset();
    expect(sdk.getSessionId()).not.toBe(session);
    expect(localStorage.getItem(ANONYMOUS_ID_KEY)).not.toBe(anon);
    sdk.shutdown();
  });

  it('persists lastActivity on pagehide', async () => {
    const sdk = await loadSdk();
    sdk.init({ ...OPTIONS });
    vi.advanceTimersByTime(1000);
    window.dispatchEvent(new Event('pointerdown')); // within the 5 s write throttle
    window.dispatchEvent(new Event('pagehide'));
    const stored = JSON.parse(sessionStorage.getItem(SESSION_KEY)!) as { lastActivity: number };
    expect(stored.lastActivity).toBe(Date.now());
    sdk.shutdown();
  });

  it('works with storage blocked entirely', async () => {
    vi.stubGlobal('localStorage', undefined);
    vi.stubGlobal('sessionStorage', undefined);
    const sdk = await loadSdk();
    sdk.init({ ...OPTIONS });
    expect(isUuid(sdk.getSessionId())).toBe(true);
    sdk.shutdown();
  });
});
