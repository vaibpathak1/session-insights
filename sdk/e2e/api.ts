/**
 * The session read API for the pipeline e2e (Phase 5b), with the dev login (ADR-0013):
 * username = an app user's email (the dev seed's admin), password = DEV_ADMIN_PASSWORD.
 * scripts/e2e-pipeline.sh exports the settings from the repository .env.
 */
const API_URL = process.env.E2E_API_URL ?? 'http://localhost:8080';
const API_USER = process.env.E2E_API_USER ?? 'admin@example.com';

function auth(): string {
  const password = process.env.DEV_ADMIN_PASSWORD;
  if (!password) throw new Error('DEV_ADMIN_PASSWORD is not set (see .env.example)');
  return `Basic ${Buffer.from(`${API_USER}:${password}`).toString('base64')}`;
}

/** GET a path under /api/v1; returns the status and the raw body (for privacy checks). */
export async function apiGet(path: string): Promise<{ status: number; body: string }> {
  const res = await fetch(`${API_URL}/api/v1${path}`, { headers: { Authorization: auth() } });
  return { status: res.status, body: await res.text() };
}
