import { defineConfig } from 'vite';

// Fixed port: the dev site's allowed origin is http://localhost:* (DevSeedData), and the
// e2e test expects http://localhost:5173.
export default defineConfig({
  server: { port: 5173, strictPort: true },
  preview: { port: 5173, strictPort: true },
});
