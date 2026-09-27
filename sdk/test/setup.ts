import { beforeEach, vi } from 'vitest';

// No test may reach the network: by default fetch never settles and beacons are refused.
// Tests that exercise transport install their own stubs.
beforeEach(() => {
  vi.stubGlobal(
    'fetch',
    vi.fn(() => new Promise<Response>(() => {})),
  );
  Object.defineProperty(navigator, 'sendBeacon', {
    configurable: true,
    writable: true,
    value: vi.fn(() => false),
  });
});
