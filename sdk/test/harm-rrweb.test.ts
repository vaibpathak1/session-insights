import { describe, expect, it, vi } from 'vitest';
import { loadSdk, OPTIONS } from './helpers';

vi.mock('@rrweb/record', () => {
  const record = Object.assign(
    () => {
      throw new Error('rrweb failed to start');
    },
    { takeFullSnapshot: () => {} },
  );
  return { record };
});

describe('rrweb failing to start', () => {
  it('does not throw into the page; the rest of the SDK keeps working', async () => {
    const sdk = await loadSdk();
    expect(() => sdk.init({ ...OPTIONS })).not.toThrow();
    expect(sdk.getSessionId()).toMatch(/^[0-9a-f-]{36}$/);
    expect(() => sdk.shutdown()).not.toThrow();
  });
});
