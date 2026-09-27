/**
 * End-to-end privacy test (Phase 3). Drives the demo site in Chromium with the real SDK,
 * then reads telemetry.events.v1 and replay.chunks.v1 from Kafka and checks that what was
 * typed into sensitive or blocked fields appears nowhere. Run via scripts/e2e-sdk.sh.
 */
import { randomBytes } from 'node:crypto';
import { expect, test } from '@playwright/test';
import { consumeTopic, type KafkaRecord } from './kafka';

const TOPIC_EVENTS = 'telemetry.events.v1';
const TOPIC_REPLAY = 'replay.chunks.v1';

// Unique per run, so a hit can only come from this run. Short numeric values (a 3-digit CVC,
// a 6-digit code) also match inside timestamps and ids, so those fields get distinctive
// text markers; the card number is realistic (16 digits cannot collide).
const run = randomBytes(4).toString('hex');
const SECRETS = {
  password: `Pw-${run}-s3cret`,
  passwordAfterToggle: `Tg-${run}`,
  card: '4242424242424242',
  cvc: `cvc${run}`,
  otp: `otp${run}`,
  blockedText: 'account balance 9,999.00',
  normalInput: `normal-${run}-value`,
};
const UNMASKED_NICKNAME = `nick-${run}`;

interface Envelope {
  sessionId: string;
  event?: { type: string };
  chunkSeq?: number;
  events?: Array<{ type: number }>;
}

test('sensitive, blocked and normal inputs never reach Kafka unmasked', async ({ page }) => {
  // Long tasks on this near-empty demo page are attributable to the SDK.
  await page.addInitScript(() => {
    const w = window as unknown as { __longTasks: number[] };
    w.__longTasks = [];
    try {
      new PerformanceObserver((list) => {
        for (const e of list.getEntries()) w.__longTasks.push(e.duration);
      }).observe({ type: 'longtask', buffered: true });
    } catch {
      // not supported
    }
  });

  await page.goto('/');
  const sessionId = await page.evaluate(() =>
    (
      window as unknown as { SessionInsights: { getSessionId(): string | null } }
    ).SessionInsights.getSessionId(),
  );
  expect(sessionId).toMatch(/^[0-9a-f-]{36}$/);

  // home: a normal (masked) input, an unmasked one
  await page.locator('#search').pressSequentially(SECRETS.normalInput);
  await page.locator('#nickname').pressSequentially(UNMASKED_NICKNAME);

  // login (inside data-si-unmask): password, then "show password" and more typing
  await page.getByRole('link', { name: 'Login' }).click();
  await page.locator('#email').pressSequentially(`e2e-${run}@example.com`);
  await page.locator('#password').pressSequentially(SECRETS.password);
  await page.locator('#toggle-password').click();
  await page.locator('#password').pressSequentially(SECRETS.passwordAfterToggle);

  // checkout: card, CVC, one-time code
  await page.getByRole('link', { name: 'Checkout' }).click();
  await page.locator('#card-number').pressSequentially(SECRETS.card);
  await page.locator('#card-cvc').pressSequentially(SECRETS.cvc);
  await page.locator('#otp').pressSequentially(SECRETS.otp);

  // back home: error buttons, hash navigation
  await page.getByRole('link', { name: 'Home' }).click();
  await page.locator('#throw-error').click();
  await page.locator('#console-error').click();
  await page.locator('#reject-promise').click();
  await page.getByRole('link', { name: 'FAQ (hash)' }).click();

  await page.waitForTimeout(2500); // one flush interval (demo uses 2 s)
  const longTasks = await page.evaluate(
    () => (window as unknown as { __longTasks: number[] }).__longTasks,
  );
  // A last navigation right before closing: only the page-hide beacon can deliver it.
  const lastPath = `/bye-${run}`;
  await page.evaluate((path) => history.pushState({}, '', path), lastPath);
  await page.close({ runBeforeUnload: true }); // pagehide → sendBeacon

  // Wait until the pipeline delivered everything we expect for this session.
  let events: KafkaRecord[] = [];
  let replay: KafkaRecord[] = [];
  let types = new Set<string>();
  const deadline = Date.now() + 60_000;
  while (Date.now() < deadline) {
    events = consumeTopic(TOPIC_EVENTS).filter((r) => r.key === sessionId);
    replay = consumeTopic(TOPIC_REPLAY).filter((r) => r.key === sessionId);
    types = new Set(events.map((r) => (JSON.parse(r.value) as Envelope).event?.type ?? '?'));
    const done =
      ['CLICK', 'NAVIGATION', 'CONSOLE_ERROR', 'EXCEPTION'].every((t) => types.has(t)) &&
      replay.length > 0 &&
      events.some((r) => r.value.includes(lastPath));
    if (done) break;
    await new Promise((r) => setTimeout(r, 2000));
  }

  const all = [...events, ...replay].map((r) => r.value).join('\n');
  const chunkSeqs = replay
    .map((r) => (JSON.parse(r.value) as Envelope).chunkSeq!)
    .sort((a, b) => a - b);
  const summary = {
    sessionId,
    telemetryRecords: events.length,
    eventTypes: Object.fromEntries(
      [...types].map((t) => [
        t,
        events.filter((r) => (JSON.parse(r.value) as Envelope).event?.type === t).length,
      ]),
    ),
    replayRecords: replay.length,
    chunkSeqs,
    replayBytes: replay.reduce((n, r) => n + r.value.length, 0),
    secretsFound: Object.entries(SECRETS)
      .filter(([, s]) => all.includes(s))
      .map(([k]) => k),
    normalInputMasked: all.includes('*'.repeat(SECRETS.normalInput.length)),
    unmaskedNicknameRecorded: all.includes(UNMASKED_NICKNAME),
    lastNavigationViaBeacon: events.some((r) => r.value.includes(lastPath)),
    duplicateEvents:
      events.length -
      new Set(
        events.map(
          (r) => (JSON.parse(r.value) as { event: { clientEventId: string } }).event.clientEventId,
        ),
      ).size,
    longTasksMs: longTasks.map((d) => Math.round(d)),
  };
  console.log(`E2E consumer summary:\n${JSON.stringify(summary, null, 2)}`);

  expect([...types]).toEqual(
    expect.arrayContaining(['CLICK', 'NAVIGATION', 'CONSOLE_ERROR', 'EXCEPTION']),
  );
  expect(replay.length).toBeGreaterThan(0);
  expect(chunkSeqs[0]).toBe(0);
  const first = JSON.parse(
    replay.find((r) => (JSON.parse(r.value) as Envelope).chunkSeq === 0)!.value,
  ) as Envelope;
  expect(first.events!.slice(0, 2).map((e) => e.type)).toEqual([4, 2]); // Meta + FullSnapshot

  expect(summary.lastNavigationViaBeacon).toBe(true);
  expect(summary.duplicateEvents).toBe(0); // a beacon never resends an in-flight keepalive send

  // The privacy bar.
  expect(summary.secretsFound).toEqual([]);
  expect(all).not.toContain(SECRETS.normalInput);
  expect(summary.normalInputMasked).toBe(true);
  // Sanity: input recording really happened (the opt-in field is visible), so the absence
  // of secrets above is not vacuous.
  expect(summary.unmaskedNicknameRecorded).toBe(true);
});
