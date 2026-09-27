import { readFileSync } from 'node:fs';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { loadSdk, OPTIONS } from './helpers';

describe('public API', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('reports the package version', async () => {
    const sdk = await loadSdk();
    // vitest runs from sdk/; under jsdom import.meta.url is not a file: URL
    const pkg = JSON.parse(readFileSync('package.json', 'utf8')) as { version: string };
    expect(sdk.version).toBe(pkg.version);
  });

  it('every method is safe before init', async () => {
    const sdk = await loadSdk();
    expect(() => {
      sdk.identify('u1', { plan: 'pro' });
      sdk.track('signup');
      sdk.reset();
      sdk.shutdown();
    }).not.toThrow();
    expect(sdk.getSessionId()).toBeNull();
  });

  it('init twice is a no-op with a debug warning', async () => {
    const sdk = await loadSdk();
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    sdk.init({ ...OPTIONS, debug: true });
    const first = sdk.getSessionId();
    expect(() => sdk.init({ ...OPTIONS, debug: true })).not.toThrow();
    expect(sdk.getSessionId()).toBe(first);
    expect(warn).toHaveBeenCalledWith(
      '[SessionInsights]',
      expect.stringContaining('init called again'),
    );
    sdk.shutdown();
  });

  it('never throws on invalid options', async () => {
    const sdk = await loadSdk();
    const hostile = Object.defineProperty({}, 'debug', {
      get() {
        throw new Error('boom');
      },
    });
    const bad: unknown[] = [
      undefined,
      null,
      42,
      {},
      { siteKey: '', collectorUrl: OPTIONS.collectorUrl },
      { siteKey: 'k', collectorUrl: 'not a url' },
      { siteKey: 'k', collectorUrl: 'ftp://x.test' },
      { ...OPTIONS, sampleRate: 'half', flushIntervalMs: NaN },
      hostile,
    ];
    for (const options of bad) {
      expect(() => sdk.init(options as never)).not.toThrow();
      sdk.shutdown();
    }
  });

  it('an invalid init does not block a later valid one', async () => {
    const sdk = await loadSdk();
    sdk.init({ siteKey: '', collectorUrl: '' });
    sdk.init({ ...OPTIONS });
    expect(() => sdk.shutdown()).not.toThrow();
  });

  it('identify and track are debug-logged no-ops', async () => {
    const sdk = await loadSdk();
    const info = vi.spyOn(console, 'info').mockImplementation(() => {});
    sdk.init({ ...OPTIONS, debug: true });
    sdk.identify('u1');
    sdk.track('clicked', { a: 1 });
    expect(info).toHaveBeenCalledWith('[SessionInsights]', expect.stringContaining('identify()'));
    expect(info).toHaveBeenCalledWith('[SessionInsights]', expect.stringContaining('track()'));
    sdk.shutdown();
  });

  it('logs nothing without debug', async () => {
    const sdk = await loadSdk();
    const warn = vi.spyOn(console, 'warn');
    const info = vi.spyOn(console, 'info');
    sdk.init({ ...OPTIONS });
    sdk.init({ ...OPTIONS });
    sdk.identify('u1');
    sdk.shutdown();
    expect(warn).not.toHaveBeenCalled();
    expect(info).not.toHaveBeenCalled();
  });
});
