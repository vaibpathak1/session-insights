import { defineConfig, devices } from '@playwright/test';

// Local only: needs compose + collector-service running and a fresh dev site key
// (scripts/e2e-sdk.sh checks all of that, then runs this).
export default defineConfig({
  testDir: 'e2e',
  timeout: 120_000,
  workers: 1,
  retries: 0,
  reporter: [['list']],
  use: { baseURL: 'http://localhost:5173', ...devices['Desktop Chrome'] },
  webServer: {
    command: 'npm --prefix ../examples/demo-site run dev',
    url: 'http://localhost:5173',
    reuseExistingServer: false, // always pick up the freshly rotated key from .env.local
    timeout: 60_000,
  },
});
