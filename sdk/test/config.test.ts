import { describe, expect, it } from 'vitest';
import { DEFAULT_FLUSH_INTERVAL_MS, resolveConfig } from '../src/config';
import { createLogger } from '../src/log';

const log = createLogger(false);

describe('resolveConfig', () => {
  it('applies defaults and normalises the collector URL', () => {
    expect(
      resolveConfig({ siteKey: ' sk_a ', collectorUrl: 'https://c.test/base/?x=1#h' }, log),
    ).toEqual({
      siteKey: 'sk_a',
      collectorUrl: 'https://c.test/base',
      sampleRate: 1,
      maskAllText: false,
      flushIntervalMs: DEFAULT_FLUSH_INTERVAL_MS,
      debug: false,
    });
  });

  it('clamps sampleRate and flushIntervalMs', () => {
    const c = resolveConfig(
      { siteKey: 'k', collectorUrl: 'http://localhost:8081', sampleRate: 7, flushIntervalMs: 5 },
      log,
    );
    expect(c?.sampleRate).toBe(1);
    expect(c?.flushIntervalMs).toBe(1000);
    expect(
      resolveConfig({ siteKey: 'k', collectorUrl: 'http://x.test', sampleRate: -1 }, log)
        ?.sampleRate,
    ).toBe(0);
  });

  it('rejects missing key or non-http collector', () => {
    expect(resolveConfig({ collectorUrl: 'https://c.test' }, log)).toBeNull();
    expect(resolveConfig({ siteKey: 'k', collectorUrl: 'javascript:alert(1)' }, log)).toBeNull();
    expect(resolveConfig({ siteKey: 'k', collectorUrl: '/relative' }, log)).toBeNull();
  });
});
