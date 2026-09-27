import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { version } from '../src/index';

describe('package', () => {
  it('reports the package version', () => {
    // vitest runs from sdk/; under jsdom import.meta.url is not a file: URL
    const pkg = JSON.parse(readFileSync('package.json', 'utf8')) as { version: string };
    expect(version).toBe(pkg.version);
  });
});
