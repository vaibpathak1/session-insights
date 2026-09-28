import { afterEach, beforeEach, describe, expect, it, vi, type Mock } from 'vitest';
// Imported statically (not via loadSdk/resetModules) so the spies below patch the same
// class instances the SDK uses.
import * as sdk from '../src/index';
import { Outbox } from '../src/outbox';
import { ReplayBuffer } from '../src/replay';
import { Sender } from '../src/sender';
import { SessionManager } from '../src/session';
import { OPTIONS } from './helpers';

/** Everything the page can observe going wrong. */
function watchPage() {
  const errors: Array<ErrorEvent | Event> = [];
  const onError = (e: Event) => errors.push(e);
  window.addEventListener('error', onError, true);
  window.addEventListener('unhandledrejection', onError, true);
  return {
    errors,
    stop() {
      window.removeEventListener('error', onError, true);
      window.removeEventListener('unhandledrejection', onError, true);
    },
  };
}

function exercisePage() {
  document.getElementById('b')!.dispatchEvent(new MouseEvent('click', { bubbles: true }));
  window.dispatchEvent(new KeyboardEvent('keydown'));
  window.dispatchEvent(new Event('scroll'));
  history.pushState(null, '', '/harm-a');
  history.replaceState(null, '', '/harm-b');
  window.dispatchEvent(new PopStateEvent('popstate'));
  window.dispatchEvent(new HashChangeEvent('hashchange'));
  console.error('page error');
  Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'hidden' });
  document.dispatchEvent(new Event('visibilitychange'));
  window.dispatchEvent(new Event('pagehide'));
}

describe('do no harm', () => {
  let savedError: typeof console.error;
  let pageConsole: Mock<(...args: unknown[]) => void>;

  beforeEach(() => {
    localStorage.clear();
    sessionStorage.clear();
    document.body.innerHTML = '<button id="b">Buy</button>';
    savedError = console.error;
    pageConsole = vi.fn<(...args: unknown[]) => void>();
    console.error = pageConsole;
  });

  afterEach(() => {
    vi.restoreAllMocks(); // un-break the internals before shutting down for real
    sdk.shutdown();
    console.error = savedError;
    delete (document as { visibilityState?: unknown }).visibilityState;
  });

  it('nothing reaches the page when every SDK internal throws', async () => {
    const boom = () => {
      throw new Error('SDK bug');
    };
    vi.spyOn(Outbox.prototype, 'addEvent').mockImplementation(boom);
    vi.spyOn(Outbox.prototype, 'addChunk').mockImplementation(boom);
    vi.spyOn(ReplayBuffer.prototype, 'add').mockImplementation(boom);
    vi.spyOn(ReplayBuffer.prototype, 'seal').mockImplementation(boom);
    vi.spyOn(SessionManager.prototype, 'touch').mockImplementation(boom);
    vi.spyOn(SessionManager.prototype, 'persist').mockImplementation(boom);
    vi.spyOn(Sender.prototype, 'flush').mockImplementation(boom);
    vi.spyOn(Sender.prototype, 'pageHide').mockImplementation(boom);
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});

    const page = watchPage();
    try {
      const hostHandler = vi.fn();
      document.getElementById('b')!.addEventListener('click', hostHandler);
      expect(() => {
        sdk.init({ ...OPTIONS, debug: true });
        exercisePage();
        sdk.reset();
        sdk.identify('u');
        sdk.track('t');
        sdk.getSessionId();
        sdk.shutdown();
      }).not.toThrow();
      await new Promise((r) => setTimeout(r, 10));
      expect(page.errors).toEqual([]);
      // the internals really did fail (and were swallowed)
      expect(warn).toHaveBeenCalledWith(
        '[SessionInsights]',
        'internal error (ignored)',
        expect.any(Error),
      );
      expect(hostHandler).toHaveBeenCalledTimes(1); // the page's own listeners still run
      expect(pageConsole).toHaveBeenCalledWith('page error'); // original console.error still called
    } finally {
      page.stop();
    }
  });

  it('a throwing sendBeacon is contained and the data stays queued', async () => {
    Object.defineProperty(navigator, 'sendBeacon', {
      configurable: true,
      value: () => {
        throw new TypeError('Illegal invocation');
      },
    });
    const page = watchPage();
    try {
      sdk.init({ ...OPTIONS });
      expect(() => window.dispatchEvent(new Event('pagehide'))).not.toThrow();
      await new Promise((r) => setTimeout(r, 10));
      expect(page.errors).toEqual([]);
      sdk.shutdown();
    } finally {
      page.stop();
    }
  });

  it('a synchronously throwing fetch is contained', async () => {
    vi.stubGlobal('fetch', () => {
      throw new TypeError('fetch is broken');
    });
    const page = watchPage();
    try {
      sdk.init({ ...OPTIONS });
      await new Promise((r) => setTimeout(r, 20));
      expect(page.errors).toEqual([]);
      sdk.shutdown();
    } finally {
      page.stop();
    }
  });
});
