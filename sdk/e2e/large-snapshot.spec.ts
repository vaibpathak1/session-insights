/**
 * Large full snapshot (Phase 4b): a page whose DOM makes a ~10 MB first snapshot is sent as
 * one chunk over normal fetch, accepted by the collector (16 MB limit, zstd-compressed into
 * the 4 MB topic), and stored by event-processor as one object. Needs the whole pipeline:
 * run via scripts/e2e-pipeline.sh (E2E_PIPELINE=1); skipped otherwise.
 */
import { expect, test } from '@playwright/test';
import { clickhouse, s3Get, unzstd } from './stores';

test.skip(process.env.E2E_PIPELINE !== '1', 'needs event-processor: run scripts/e2e-pipeline.sh');

interface ManifestRow {
  tenant_id: string;
  chunk_seq: number;
  object_key: string;
  event_count: number;
  has_full_snapshot: number;
  compressed_bytes: number;
}

test('a ~10 MB full snapshot reaches object storage intact', async ({ page }) => {
  test.setTimeout(180_000);
  const replayPosts: { status: number }[] = [];
  page.on('response', (r) => {
    if (r.request().method() === 'POST' && r.url().includes('/v1/replay')) {
      replayPosts.push({ status: r.status() });
    }
  });
  const messages: string[] = [];
  page.on('console', (m) => messages.push(m.text()));

  await page.goto('/?big=10');
  const sessionId = await page.evaluate(() =>
    (
      window as unknown as { SessionInsights: { getSessionId(): string | null } }
    ).SessionInsights.getSessionId(),
  );
  expect(sessionId).toMatch(/^[0-9a-f-]{36}$/);

  let chunk0: ManifestRow | undefined;
  const deadline = Date.now() + 90_000;
  while (!chunk0 && Date.now() < deadline) {
    // the test knows the session, not the tenant: look the tenant up from the manifest
    const rows = await clickhouse<ManifestRow>(
      'SELECT tenant_id, chunk_seq, object_key, event_count, has_full_snapshot, compressed_bytes' +
        ' FROM replay_chunks FINAL WHERE session_id = {s:UUID} AND chunk_seq = 0',
      { s: sessionId! },
    );
    chunk0 = rows[0];
    if (!chunk0) await new Promise((r) => setTimeout(r, 1000));
  }
  expect(chunk0, 'chunk 0 in the manifest').toBeDefined();

  const bytes = await s3Get(chunk0!.object_key);
  expect(bytes).not.toBeNull();
  const json = unzstd(bytes!);
  const events = JSON.parse(json) as Array<{ type: number }>;
  const summary = {
    sessionId,
    objectKey: chunk0!.object_key,
    storedBytes: bytes!.length,
    decompressedBytes: Buffer.byteLength(json),
    events: events.length,
    firstTypes: events.slice(0, 2).map((e) => e.type),
    replayPosts,
    sdkLog: messages.filter((l) => /replay off|queue full|refused/.test(l)),
  };
  console.log(`E2E large-snapshot summary:\n${JSON.stringify(summary, null, 2)}`);

  expect(summary.decompressedBytes).toBeGreaterThan(8 * 1024 * 1024);
  expect(summary.decompressedBytes).toBeLessThan(16 * 1024 * 1024);
  expect(summary.firstTypes).toEqual([4, 2]); // Meta + FullSnapshot, in one chunk
  expect(chunk0!.has_full_snapshot).toBe(1);
  expect(chunk0!.event_count).toBe(events.length);
  expect(chunk0!.compressed_bytes).toBe(bytes!.length);
  expect(json).toContain('catalogue item 996');
  expect(replayPosts[0]).toEqual({ status: 202 });
  expect(summary.sdkLog).toEqual([]);
});
