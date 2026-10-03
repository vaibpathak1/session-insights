/**
 * End-to-end privacy test (Phase 3). Drives the demo site in Chromium with the real SDK,
 * then reads telemetry.events.v1 and replay.chunks.v1 from Kafka and checks that what was
 * typed into sensitive or blocked fields appears nowhere. Run via scripts/e2e-sdk.sh.
 *
 * With E2E_PIPELINE=1 (scripts/e2e-pipeline.sh, Phase 4) it then follows the session through
 * event-processor into ClickHouse and object storage and applies the same privacy bar there.
 */
import { randomBytes } from 'node:crypto';
import { expect, test } from '@playwright/test';
import { consumeTopic, type KafkaRecord } from './kafka';
import { apiGet } from './api';
import { clickhouse, s3Get, s3List, unzstd } from './stores';

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

const PIPELINE = process.env.E2E_PIPELINE === '1';

interface Envelope {
  tenantId: string;
  sessionId: string;
  event?: { type: string };
  chunkSeq?: number;
  events?: Array<{ type: number }>;
}

test('sensitive, blocked and normal inputs never reach Kafka (or the stores) unmasked', async ({
  page,
}) => {
  if (PIPELINE) test.setTimeout(360_000);
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

  if (!PIPELINE) return;

  // ---- Phase 4: the same session in ClickHouse and object storage (event-processor) ----
  const tenantId = (JSON.parse(events[0]!.value) as Envelope).tenantId;
  const ids = { t: tenantId, s: sessionId! };
  const uniqueEvents = events.length - summary.duplicateEvents;
  const uniqueChunks = [...new Set(chunkSeqs)];

  interface ManifestRow {
    chunk_seq: number;
    object_key: string;
    event_count: number;
    has_full_snapshot: number;
  }
  let rows: Record<string, unknown>[] = [];
  let manifest: ManifestRow[] = [];
  const storeDeadline = Date.now() + 60_000;
  while (Date.now() < storeDeadline) {
    rows = await clickhouse(
      'SELECT * FROM events FINAL WHERE tenant_id = {t:UUID} AND session_id = {s:UUID} ORDER BY ts',
      ids,
    );
    manifest = await clickhouse<ManifestRow>(
      'SELECT chunk_seq, object_key, event_count, has_full_snapshot FROM replay_chunks FINAL' +
        ' WHERE tenant_id = {t:UUID} AND session_id = {s:UUID} ORDER BY chunk_seq',
      ids,
    );
    if (rows.length >= uniqueEvents && manifest.length >= uniqueChunks.length) break;
    await new Promise((r) => setTimeout(r, 1000));
  }

  const prefix = `tenants/${tenantId}/sessions/${sessionId}/`;
  const listed = (await s3List(prefix)).sort();
  const objects: string[] = [];
  for (const m of manifest) {
    const bytes = await s3Get(m.object_key);
    objects.push(bytes ? unzstd(bytes) : '');
  }
  const stored = [...rows.map((r) => JSON.stringify(r)), ...objects].join('\n');
  const pipelineSummary = {
    clickhouseRows: rows.length,
    rowEventTypes: [...new Set(rows.map((r) => r.event_type as string))].sort(),
    manifestChunkSeqs: manifest.map((m) => m.chunk_seq),
    objectsListed: listed.length,
    storedReplayEvents: objects.reduce(
      (n, o) => n + (o ? (JSON.parse(o) as unknown[]).length : 0),
      0,
    ),
    secretsFound: Object.entries(SECRETS)
      .filter(([, secret]) => stored.includes(secret))
      .map(([k]) => k),
    normalInputMasked: stored.includes('*'.repeat(SECRETS.normalInput.length)),
    unmaskedNicknameStored: stored.includes(UNMASKED_NICKNAME),
  };
  console.log(`E2E pipeline summary:\n${JSON.stringify(pipelineSummary, null, 2)}`);

  // every event exactly once (FINAL), with its tenant/session
  expect(rows).toHaveLength(uniqueEvents);
  expect(rows.every((r) => r.tenant_id === tenantId && r.session_id === sessionId)).toBe(true);
  expect(pipelineSummary.rowEventTypes).toEqual(expect.arrayContaining([...types]));
  // manifest: every chunk, in order from 0, chunk 0 has the full snapshot, objects exist
  expect(pipelineSummary.manifestChunkSeqs).toEqual(uniqueChunks);
  expect(manifest[0]!.chunk_seq).toBe(0);
  expect(manifest[0]!.has_full_snapshot).toBe(1);
  expect(listed).toEqual(manifest.map((m) => m.object_key).sort());
  expect(listed.every((k) => k.startsWith(prefix) && /\/\d{6}\.json\.zst$/.test(k))).toBe(true);
  expect(objects.every((o) => o.length > 0)).toBe(true);
  expect(
    manifest.every((m, i) => (JSON.parse(objects[i]!) as unknown[]).length === m.event_count),
  ).toBe(true);

  // The privacy bar, applied to what is stored.
  expect(pipelineSummary.secretsFound).toEqual([]);
  expect(stored).not.toContain(SECRETS.normalInput);
  expect(pipelineSummary.normalInputMasked).toBe(true);
  expect(pipelineSummary.unmaskedNicknameStored).toBe(true);

  // ---- Phase 5b: the same session through the read API (dev auth, ADR-0013) ----
  interface ApiSession {
    id: string;
    status: string;
    durationMs: number;
    pageCount: number;
    errorCount: number;
    entryUrl: string | null;
  }
  interface ApiEvent {
    id: string;
    type: string;
    ts: string;
  }
  interface Chunk {
    seq: number;
    hasFullSnapshot: boolean;
  }
  const bodies: string[] = [];
  const read = async <T>(path: string): Promise<T> => {
    const { status, body } = await apiGet(path);
    expect(status, `GET ${path}`).toBe(200);
    bodies.push(body);
    return JSON.parse(body) as T;
  };

  const list = await read<{ items: ApiSession[] }>('/sessions?limit=200');
  expect(list.items.map((s) => s.id)).toContain(sessionId);

  const apiEvents: ApiEvent[] = [];
  let after: string | null = null;
  do {
    const page: { items: ApiEvent[]; nextCursor: string | null } = await read(
      `/sessions/${sessionId}/events?limit=7${after ? `&after=${after}` : ''}`,
    );
    apiEvents.push(...page.items);
    after = page.nextCursor;
  } while (after);
  expect(apiEvents).toHaveLength(uniqueEvents);
  expect(new Set(apiEvents.map((e) => e.id)).size).toBe(uniqueEvents);
  const eventTimes = apiEvents.map((e) => Date.parse(e.ts));
  expect(eventTimes).toEqual([...eventTimes].sort((a, b) => a - b));

  const apiReplay = await read<{ chunks: Chunk[] }>(`/sessions/${sessionId}/replay`);
  expect(apiReplay.chunks.map((c) => c.seq)).toEqual(uniqueChunks);
  expect(apiReplay.chunks[0]!.hasFullSnapshot).toBe(true);
  const chunk0 = await read<Array<{ type: number }>>(`/sessions/${sessionId}/replay/0`);
  expect(chunk0.slice(0, 2).map((e) => e.type)).toEqual([4, 2]);
  for (const c of apiReplay.chunks.slice(1)) await read(`/sessions/${sessionId}/replay/${c.seq}`);

  // Counters appear when the session closes (processor started with a short idle timeout)
  const idleSeconds = Number(process.env.E2E_SESSION_IDLE_SECONDS ?? 20);
  const closeDeadline = Date.now() + (idleSeconds + 60) * 1000;
  let detail: { session: ApiSession } = await read(`/sessions/${sessionId}`);
  while (detail.session.status !== 'closed' && Date.now() < closeDeadline) {
    await new Promise((r) => setTimeout(r, 2000));
    detail = await read(`/sessions/${sessionId}`);
  }
  // expected counters, from the ClickHouse rows read above (task 5.4 definitions)
  const utc = (v: unknown) => Date.parse(`${String(v).replace(' ', 'T')}Z`);
  const times = rows.map((r) => utc(r.ts));
  const navigations = rows
    .filter((r) => r.event_type === 'NAVIGATION')
    .filter((r) => (r.props as { trigger?: string } | null)?.trigger !== 'replaceState')
    .map((r) => String(r.url).split('#')[0]);
  const expectedPages = navigations.filter((u, i) => i === 0 || u !== navigations[i - 1]).length;
  const expectedErrors = rows.filter((r) =>
    ['EXCEPTION', 'CONSOLE_ERROR'].includes(r.event_type as string),
  ).length;
  const apiSummary = {
    status: detail.session.status,
    durationMs: detail.session.durationMs,
    pageCount: detail.session.pageCount,
    errorCount: detail.session.errorCount,
    expected: {
      durationMs: Math.max(...times) - Math.min(...times),
      pageCount: expectedPages,
      errorCount: expectedErrors,
    },
    apiEvents: apiEvents.length,
    chunks: apiReplay.chunks.length,
  };
  console.log(`E2E API summary:\n${JSON.stringify(apiSummary, null, 2)}`);
  expect(detail.session.status).toBe('closed');
  expect(detail.session.durationMs).toBe(apiSummary.expected.durationMs);
  expect(detail.session.pageCount).toBe(expectedPages);
  expect(detail.session.errorCount).toBe(expectedErrors);

  // Another session id (random, or another tenant's) is simply not found
  expect((await apiGet(`/sessions/${crypto.randomUUID()}`)).status).toBe(404);

  // The privacy bar, applied to everything the API returned
  const served = bodies.join('\n');
  expect(Object.entries(SECRETS).filter(([, secret]) => served.includes(secret))).toEqual([]);
  expect(served).not.toContain(SECRETS.normalInput);
  expect(served).toContain('*'.repeat(SECRETS.normalInput.length));
});
