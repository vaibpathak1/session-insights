import { vi } from 'vitest';

export type Sdk = typeof import('../src/index');

/** A fresh copy of the SDK module (module-level state reset). */
export async function loadSdk(): Promise<Sdk> {
  vi.resetModules();
  return import('../src/index');
}

export const OPTIONS = {
  siteKey: 'sk_test_key',
  collectorUrl: 'https://collector.test',
} as const;
