/// <reference types="vitest/config" />
import { readFileSync } from 'node:fs';
import { defineConfig } from 'vite';

const pkg = JSON.parse(readFileSync(new URL('./package.json', import.meta.url), 'utf8')) as {
  version: string;
  dependencies: Record<string, string>;
};

// Two library builds (`vite build --mode esm|iife`):
// - esm:  for bundlers; rrweb stays an external dependency, not minified.
// - iife: self-contained `window.SessionInsights` for a <script> tag; minified. This is
//         what lands on a page, so it is what size-limit measures.
export default defineConfig(({ mode }) => {
  const iife = mode === 'iife';
  return {
    define: { __SDK_VERSION__: JSON.stringify(pkg.version) },
    build: {
      target: 'es2020',
      emptyOutDir: !iife, // esm runs first and cleans dist/
      minify: iife,
      sourcemap: true,
      lib: {
        entry: 'src/index.ts',
        name: 'SessionInsights',
        formats: [iife ? 'iife' : 'es'],
        fileName: () => (iife ? 'session-insights.iife.js' : 'session-insights.js'),
      },
      rolldownOptions: iife ? {} : { external: Object.keys(pkg.dependencies) },
    },
    test: {
      environment: 'jsdom',
      include: ['test/**/*.test.ts'],
      setupFiles: ['test/setup.ts'],
      restoreMocks: true,
      unstubGlobals: true,
    },
  };
});
