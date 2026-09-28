/**
 * Wrong site key (Phase 4b, ADR-0012): the collector's 401 is readable by the browser, so the
 * SDK stops after its first request and says why in debug mode, instead of retrying until
 * the five-failure fallback. Run via scripts/e2e-sdk.sh (needs compose + collector).
 */
import { expect, test } from '@playwright/test';

test('a wrong site key stops the SDK after one request', async ({ page }) => {
  const posts: { url: string; status?: number }[] = [];
  page.on('request', (r) => {
    if (r.method() === 'POST' && r.url().includes('/v1/')) posts.push({ url: r.url() });
  });
  page.on('response', (r) => {
    const post = posts.find((p) => p.url === r.url() && p.status === undefined);
    if (post && r.request().method() === 'POST') post.status = r.status();
  });
  const messages: string[] = [];
  page.on('console', (m) => messages.push(m.text()));

  await page.goto(`/?siteKey=sk_wrong_${Date.now()}`);
  await expect
    .poll(() => messages.some((l) => l.includes('invalid site key')), { timeout: 15_000 })
    .toBe(true);

  // keep interacting past several flush intervals: nothing more may be sent
  await page.locator('#search').pressSequentially('after the refusal');
  await page.getByRole('link', { name: 'Login' }).click();
  await page.waitForTimeout(8_000);

  console.log(
    `E2E wrong-key summary: ${JSON.stringify({ posts, halt: messages.filter((l) => l.includes('stopped')) })}`,
  );
  expect(posts).toHaveLength(1);
  expect(posts[0]!.status).toBe(401);
  expect(messages.some((l) => l.includes('unreachable'))).toBe(false);
  expect(
    await page.evaluate(() =>
      (
        window as unknown as { SessionInsights: { getSessionId(): string | null } }
      ).SessionInsights.getSessionId(),
    ),
  ).toBeNull();
});
