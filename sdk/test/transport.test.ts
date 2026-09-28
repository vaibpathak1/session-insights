import { gunzipSync } from 'node:zlib';
import { afterEach, beforeEach, describe, expect, it, vi, type Mock } from 'vitest';
import { Client } from '../src/client';
import { resolveConfig } from '../src/config';
import { createLogger } from '../src/log';
import { MAX_QUEUE_BYTES, MAX_SNAPSHOT_CHUNK_BYTES, Outbox } from '../src/outbox';
import { RRWEB_FULL_SNAPSHOT, RRWEB_META } from '../src/recorder';
import type { SealedChunk } from '../src/replay';
import {
  BEACON_BUDGET_BYTES,
  MAX_FAILURES_BEFORE_FIRST_SUCCESS,
  Sender,
  type FatalReason,
} from '../src/sender';
import { backoffMs, classify, parseRetryAfter, Transport, utf8Length } from '../src/transport';
import type { EventBatch, ReplayBatch, TelemetryEvent } from '../src/wire';
import { loadSdk, OPTIONS } from './helpers';

const log = createLogger(false);
const KEY = 'sk_test_a+b/c=';
const S1 = '11111111-1111-4111-8111-111111111111';
const S2 = '22222222-2222-4222-8222-222222222222';
const ANON = '33333333-3333-4333-8333-333333333333';
const utf8 = (s: string) => new TextEncoder().encode(s).length;

interface Call {
  url: string;
  headers: Record<string, string>;
  body: string;
  keepalive: boolean;
}

let fetchMock: Mock;
let host: string;
let hostCounter = 0;
let calls: Call[];
/** Responses to return, in order; the last one repeats. `'network'` rejects. */
let responses: Array<number | 'network' | { status: number; retryAfter: string }>;

function toText(body: unknown, gzipped: boolean): string {
  if (typeof body === 'string') return body;
  const bytes = Buffer.from(body as ArrayBuffer);
  return gzipped ? gunzipSync(bytes).toString('utf8') : bytes.toString('utf8');
}

beforeEach(() => {
  calls = [];
  responses = [202];
  host = `c${++hostCounter}.test`;
  fetchMock = vi.fn((url: string, init: RequestInit) => {
    if (new URL(url).host !== host) {
      return Promise.resolve({ status: 202, headers: new Headers() } as Response);
    }
    const headers = Object.fromEntries(new Headers(init.headers).entries());
    calls.push({
      url,
      headers,
      body: toText(init.body, headers['content-encoding'] === 'gzip'),
      keepalive: init.keepalive === true,
    });
    const next = responses.length > 1 ? responses.shift()! : responses[0]!;
    if (next === 'network') return Promise.reject(new TypeError('Failed to fetch'));
    const status = typeof next === 'number' ? next : next.status;
    const h = new Headers(typeof next === 'number' ? {} : { 'Retry-After': next.retryAfter });
    return Promise.resolve({ status, headers: h } as Response);
  });
  vi.stubGlobal('fetch', fetchMock);
});

afterEach(() => {
  vi.useRealTimers();
});

function event(i: number, extra: Partial<TelemetryEvent> = {}): TelemetryEvent {
  return {
    clientEventId: `00000000-0000-4000-8000-${String(i).padStart(12, '0')}`,
    type: 'CLICK',
    ts: 1_700_000_000_000 + i,
    ...extra,
  };
}

function chunk(sessionId: string, chunkSeq: number, size = 100): SealedChunk {
  const body = JSON.stringify({
    sessionId,
    chunkSeq,
    events: [{ type: 3, data: 'x'.repeat(size) }],
  });
  return { sessionId, chunkSeq, body, bytes: body.length };
}

function harness(opts: { random?: () => number } = {}) {
  const outbox = new Outbox('0.1.0');
  const fatal: FatalReason[] = [];
  let drained = 0;
  const transport = new Transport({ collectorUrl: `https://${host}`, siteKey: KEY, log });
  const sender = new Sender(
    outbox,
    transport,
    { onFatal: (r) => fatal.push(r), onDrained: () => drained++ },
    log,
    opts.random ?? (() => 0.5),
  );
  return { outbox, sender, transport, fatal, drained: () => drained };
}

const settle = () => new Promise((r) => setTimeout(r, 0));

describe('transport', () => {
  it('always sends the key as ?k= and never as a header', async () => {
    const { outbox, sender } = harness();
    outbox.addEvent(S1, ANON, event(1));
    outbox.addChunk(chunk(S1, 0));
    sender.flush();
    await vi.waitFor(() => expect(calls).toHaveLength(2));
    for (const c of calls) {
      expect(new URL(c.url).searchParams.get('k')).toBe(KEY);
      expect(Object.keys(c.headers).some((h) => h.toLowerCase().includes('key'))).toBe(false);
      expect(c.body).not.toContain(KEY);
    }
    expect(calls.map((c) => new URL(c.url).pathname)).toEqual(['/v1/events', '/v1/replay']);
  });

  it('gzips with CompressionStream when available', async () => {
    const { outbox, sender } = harness();
    outbox.addEvent(S1, ANON, event(1));
    sender.flush();
    await vi.waitFor(() => expect(calls).toHaveLength(1));
    expect(calls[0]!.headers['content-encoding']).toBe('gzip');
    expect(calls[0]!.headers['content-type']).toMatch(/^text\/plain/);
    expect(JSON.parse(calls[0]!.body)).toEqual({
      sessionId: S1,
      anonymousId: ANON,
      sdkVersion: '0.1.0',
      events: [event(1)],
    } satisfies EventBatch);
  });

  it('sends plain JSON when CompressionStream is missing', async () => {
    vi.stubGlobal('CompressionStream', undefined);
    const { outbox, sender } = harness();
    outbox.addChunk(chunk(S1, 0));
    sender.flush();
    await vi.waitFor(() => expect(calls).toHaveLength(1));
    expect(calls[0]!.headers['content-encoding']).toBeUndefined();
    expect((JSON.parse(calls[0]!.body) as ReplayBatch).chunkSeq).toBe(0);
  });

  it('splits events into batches per session and of at most 500', async () => {
    const { outbox, sender } = harness();
    for (let i = 0; i < 501; i++) outbox.addEvent(S1, ANON, event(i));
    outbox.addEvent(S2, ANON, event(999));
    sender.flush();
    await vi.waitFor(() => expect(calls).toHaveLength(3));
    const batches = calls.map((c) => JSON.parse(c.body) as EventBatch);
    expect(batches.map((b) => [b.sessionId, b.events.length])).toEqual([
      [S1, 500],
      [S1, 1],
      [S2, 1],
    ]);
  });
});

describe('retries', () => {
  beforeEach(() => {
    vi.stubGlobal('CompressionStream', undefined); // keep timing deterministic
    vi.useFakeTimers();
  });

  it.each([503, 429, 502, 'network' as const])('retries after %s with backoff', async (first) => {
    responses = [first, 202];
    const { outbox, sender } = harness({ random: () => 1 });
    outbox.addEvent(S1, ANON, event(1));
    sender.flush();
    await vi.advanceTimersByTimeAsync(0);
    expect(calls).toHaveLength(1);
    await vi.advanceTimersByTimeAsync(999); // attempt 0: up to 1 s (random = 1)
    expect(calls).toHaveLength(1);
    await vi.advanceTimersByTimeAsync(1);
    expect(calls).toHaveLength(2);
    expect(outbox.isEmpty).toBe(true);
  });

  it.each([
    ['delay-seconds', 429, (_now: number) => '7', 7000],
    ['HTTP date', 503, (now: number) => new Date(now + 3000).toUTCString(), 3000],
  ])('honours Retry-After as %s', async (_label, status, header, waitMs) => {
    const now = Date.parse('2026-09-27T10:00:00Z'); // whole second: HTTP dates have 1 s resolution
    vi.setSystemTime(now);
    responses = [{ status, retryAfter: header(now) }, 202];
    const { outbox, sender } = harness({ random: () => 0 });
    outbox.addEvent(S1, ANON, event(1));
    sender.flush();
    await vi.advanceTimersByTimeAsync(0);
    await vi.advanceTimersByTimeAsync(waitMs - 1);
    expect(calls).toHaveLength(1);
    await vi.advanceTimersByTimeAsync(1);
    expect(calls).toHaveLength(2);
  });

  it.each([400, 413])(
    'never retries %s: the request is dropped, the rest continue',
    async (status) => {
      responses = [status, 202];
      const { outbox, sender, fatal } = harness();
      outbox.addEvent(S1, ANON, event(1));
      outbox.addChunk(chunk(S1, 0));
      sender.flush();
      await vi.advanceTimersByTimeAsync(60_000);
      expect(calls.map((c) => new URL(c.url).pathname)).toEqual(['/v1/events', '/v1/replay']);
      expect(outbox.isEmpty).toBe(true);
      expect(fatal).toEqual([]);
    },
  );

  it.each([401, 403])('never retries %s and stops sending', async (status) => {
    responses = [status];
    const { outbox, sender, fatal } = harness();
    outbox.addEvent(S1, ANON, event(1));
    outbox.addChunk(chunk(S1, 0));
    sender.flush();
    await vi.advanceTimersByTimeAsync(60_000);
    sender.flush();
    await vi.advanceTimersByTimeAsync(60_000);
    expect(calls).toHaveLength(1);
    expect(fatal).toEqual([{ status }]);
  });

  it('stops after repeated failures before any success (CORS hides 401/403)', async () => {
    responses = ['network'];
    const { outbox, sender, fatal } = harness({ random: () => 1 });
    outbox.addEvent(S1, ANON, event(1));
    sender.flush();
    await vi.advanceTimersByTimeAsync(10 * 60_000);
    expect(calls).toHaveLength(MAX_FAILURES_BEFORE_FIRST_SUCCESS);
    expect(fatal).toEqual([{ unreachable: true }]);
  });

  it('keeps retrying an outage once the collector has accepted something', async () => {
    responses = [202, 'network'];
    const { outbox, sender, fatal } = harness({ random: () => 1 });
    outbox.addEvent(S1, ANON, event(1));
    sender.flush();
    await vi.advanceTimersByTimeAsync(0);
    outbox.addEvent(S1, ANON, event(2));
    sender.flush();
    await vi.advanceTimersByTimeAsync(10 * 60_000);
    expect(calls.length).toBeGreaterThan(MAX_FAILURES_BEFORE_FIRST_SUCCESS + 1);
    expect(fatal).toEqual([]);
  });
});

describe('backoff and Retry-After parsing', () => {
  it('uses full jitter under an exponential ceiling capped at 30 s', () => {
    expect(backoffMs(0, () => 1)).toBe(1000);
    expect(backoffMs(3, () => 1)).toBe(8000);
    expect(backoffMs(20, () => 1)).toBe(30_000);
    expect(backoffMs(3, () => 0)).toBe(0);
  });

  it('parses seconds and HTTP dates, capped at 10 minutes', () => {
    const now = Date.parse('2026-09-27T10:00:00Z');
    expect(parseRetryAfter('5', now)).toBe(5000);
    expect(parseRetryAfter('Sun, 27 Sep 2026 10:00:30 GMT', now)).toBe(30_000);
    expect(parseRetryAfter('86400', now)).toBe(600_000);
    expect(parseRetryAfter('soon', now)).toBeUndefined();
    expect(parseRetryAfter(null, now)).toBeUndefined();
  });

  it('classifies statuses', () => {
    expect(classify(202, null, 0).kind).toBe('ok');
    expect(classify(401, null, 0).kind).toBe('fatal');
    expect(classify(403, null, 0).kind).toBe('fatal');
    expect(classify(400, null, 0).kind).toBe('drop');
    expect(classify(413, null, 0).kind).toBe('drop');
    expect(classify(415, null, 0).kind).toBe('drop');
    expect(classify(429, '2', 0)).toEqual({ kind: 'retry', status: 429, retryAfterMs: 2000 });
    expect(classify(503, null, 0)).toEqual({ kind: 'retry', status: 503 });
  });
});

describe('page hide (beacon)', () => {
  let beacons: Array<{ url: string; type: string; body: string }>;

  beforeEach(() => {
    beacons = [];
    Object.defineProperty(navigator, 'sendBeacon', {
      configurable: true,
      writable: true,
      value: vi.fn((url: string, data: Blob) => {
        beacons.push({ url, type: data.type, body: '' });
        void data
          .text()
          .then((t) => (beacons.find((b) => b.url === url && b.body === '')!.body = t));
        return true;
      }),
    });
  });

  it('beacons text/plain with ?k=, uncompressed', async () => {
    const { outbox, sender } = harness();
    outbox.addEvent(S1, ANON, event(1));
    sender.pageHide();
    await settle();
    expect(beacons).toHaveLength(1);
    expect(beacons[0]!.type).toMatch(/^text\/plain/);
    expect(new URL(beacons[0]!.url).searchParams.get('k')).toBe(KEY);
    expect((JSON.parse(beacons[0]!.body) as EventBatch).events).toEqual([event(1)]);
    expect(outbox.isEmpty).toBe(true);
  });

  it('stays within the 64 KB budget: newest events first, then replay oldest-first, tail dropped', async () => {
    const { outbox, sender } = harness();
    const pad = 'p'.repeat(400);
    for (let i = 0; i < 200; i++) outbox.addEvent(S1, ANON, event(i, { targetText: pad }));
    outbox.addChunk(chunk(S1, 0, 5_000));
    outbox.addChunk(chunk(S1, 1, 200_000));
    outbox.addChunk(chunk(S1, 2, 1_000));
    sender.pageHide();
    await settle();

    const total = beacons.reduce((n, b) => n + utf8(b.body), 0);
    expect(total).toBeLessThanOrEqual(BEACON_BUDGET_BYTES);
    expect(total).toBeLessThanOrEqual(64 * 1024);

    const sentEvents = beacons
      .filter((b) => b.url.includes('/v1/events'))
      .flatMap((b) => (JSON.parse(b.body) as EventBatch).events.map((e) => e.ts));
    const newest = Math.max(...sentEvents);
    expect(newest).toBe(event(199).ts); // the most recent events made it
    expect(sentEvents.length).toBeLessThan(200); // not all fit

    const sentChunks = beacons
      .filter((b) => b.url.includes('/v1/replay'))
      .map((b) => (JSON.parse(b.body) as ReplayBatch).chunkSeq);
    expect(sentChunks.every((seq) => seq === 0)).toBe(true); // no gap: 1 is too big, so 2 waits
    // what did not fit stays queued (the page may come back)
    expect(outbox.size.chunks).toBeGreaterThanOrEqual(2);
  });

  it('stops when the browser refuses a beacon and keeps the rest queued', () => {
    (navigator.sendBeacon as Mock).mockReturnValue(false);
    const { outbox, sender } = harness();
    outbox.addEvent(S1, ANON, event(1));
    sender.pageHide();
    expect(outbox.size.events).toBe(1);
  });

  it('falls back to fetch keepalive without sendBeacon', async () => {
    Object.defineProperty(navigator, 'sendBeacon', { configurable: true, value: undefined });
    const { outbox, sender } = harness();
    outbox.addEvent(S1, ANON, event(1));
    sender.pageHide();
    await settle();
    expect(calls).toHaveLength(1);
    expect(calls[0]!.keepalive).toBe(true);
    expect(calls[0]!.headers['content-encoding']).toBeUndefined();
    expect(new URL(calls[0]!.url).searchParams.get('k')).toBe(KEY);
  });
});

describe('page hide while a normal send is in progress', () => {
  let beaconBodies: string[];

  beforeEach(() => {
    beaconBodies = [];
    Object.defineProperty(navigator, 'sendBeacon', {
      configurable: true,
      writable: true,
      value: vi.fn((_url: string, data: Blob) => {
        void data.text().then((t) => beaconBodies.push(t));
        return true;
      }),
    });
  });

  const sentIds = (bodies: string[]) =>
    bodies.flatMap((b) => (JSON.parse(b) as EventBatch).events.map((e) => e.clientEventId));

  it('uses keepalive for small sends only', async () => {
    vi.stubGlobal('CompressionStream', undefined);
    const { outbox, sender } = harness();
    outbox.addEvent(S1, ANON, event(1));
    outbox.addEvent(S2, ANON, event(2, { targetText: 'x'.repeat(40_000) }));
    sender.flush();
    await vi.waitFor(() => expect(calls).toHaveLength(2));
    expect(calls.map((c) => c.keepalive)).toEqual([true, false]);
  });

  it('does not resend a keepalive request that is already in flight', async () => {
    let release!: () => void;
    fetchMock.mockImplementationOnce((url: string, init: RequestInit) => {
      calls.push({ url, headers: {}, body: '', keepalive: init.keepalive === true });
      return new Promise((resolve) => {
        release = () => resolve({ status: 202, headers: new Headers() });
      });
    });
    const { outbox, sender } = harness();
    outbox.addEvent(S1, ANON, event(1));
    outbox.addEvent(S1, ANON, event(2));
    sender.flush();
    await vi.waitFor(() => expect(calls).toHaveLength(1)); // in flight, keepalive
    outbox.addEvent(S1, ANON, event(3)); // arrives after the send started
    sender.pageHide();
    await settle();
    expect(sentIds(beaconBodies)).toEqual([event(3).clientEventId]);
    release();
    await vi.waitFor(() => expect(outbox.isEmpty).toBe(true));
  });

  it('skips the send if a beacon took its items while compressing', async () => {
    const { outbox, sender, transport } = harness();
    let finishCompressing!: () => void;
    const prepare = transport.prepare.bind(transport);
    vi.spyOn(transport, 'prepare').mockImplementationOnce(
      (body) => new Promise((resolve) => (finishCompressing = () => resolve(prepare(body)))),
    );
    outbox.addEvent(S1, ANON, event(1));
    sender.flush();
    await vi.waitFor(() => expect(finishCompressing).toBeDefined());
    sender.pageHide(); // beacons event 1 while the normal send is still compressing it
    finishCompressing();
    await settle();
    await settle();
    expect(sentIds(beaconBodies)).toEqual([event(1).clientEventId]);
    expect(calls).toEqual([]); // no duplicate over fetch
    expect(outbox.isEmpty).toBe(true);
  });
});

describe('bounded queue', () => {
  it('drops the oldest replay chunks first, then the oldest events', () => {
    const dropped = vi.fn();
    const outbox = new Outbox('0.1.0', dropped, 10_000);
    for (let i = 0; i < 5; i++) outbox.addEvent(S1, ANON, event(i));
    for (let seq = 0; seq < 4; seq++) outbox.addChunk(chunk(S1, seq, 2_500));
    expect(outbox.size.bytes).toBeLessThanOrEqual(10_000);
    expect(outbox.pendingChunks.map((c) => c.chunkSeq)).toEqual([1, 2, 3]);
    expect(outbox.size.events).toBe(5);
    expect(outbox.dropped).toEqual({ events: 0, replayChunks: 1 });
    expect(dropped).toHaveBeenCalledTimes(1);

    for (let i = 5; i < 200; i++) outbox.addEvent(S1, ANON, event(i));
    expect(outbox.size.chunks).toBe(0);
    expect(outbox.dropped.events).toBeGreaterThan(0);
    expect(outbox.pendingEvents[0]!.event.ts).toBeGreaterThan(event(0).ts); // oldest went first
    expect(outbox.pendingEvents.slice(-1)[0]!.event.ts).toBe(event(199).ts);
  });

  it('defaults to 2 MB, with one full snapshot of up to 16 MB outside the bound', () => {
    expect(MAX_QUEUE_BYTES).toBe(2 * 1024 * 1024);
    expect(MAX_SNAPSHOT_CHUNK_BYTES).toBe(16 * 1024 * 1024);
  });

  const snapshot = (seq: number, size: number): SealedChunk => ({
    ...chunk(S1, seq, size),
    fullSnapshot: true,
  });

  it('keeps a full snapshot larger than the bound without evicting anything', () => {
    const dropped = vi.fn();
    const tooLarge = vi.fn();
    const outbox = new Outbox('0.1.0', dropped, 10_000, tooLarge, 100_000);
    for (let i = 0; i < 5; i++) outbox.addEvent(S1, ANON, event(i));
    outbox.addChunk(snapshot(0, 60_000)); // 6x the bound
    outbox.addChunk(chunk(S1, 1, 2_000));
    expect(outbox.pendingChunks.map((c) => c.chunkSeq)).toEqual([0, 1]);
    expect(outbox.size.events).toBe(5);
    expect(outbox.dropped).toEqual({ events: 0, replayChunks: 0 });
    expect(dropped).not.toHaveBeenCalled();
    expect(tooLarge).not.toHaveBeenCalled();
    // the bound still applies to everything else: the snapshot is never the one evicted
    for (let seq = 2; seq < 8; seq++) outbox.addChunk(chunk(S1, seq, 2_500));
    expect(outbox.pendingChunks[0]!.chunkSeq).toBe(0);
    expect(outbox.dropped.replayChunks).toBeGreaterThan(0);
    // events first, then the snapshot, over a normal request
    outbox.remove(outbox.next()!);
    const next = outbox.next()!;
    expect(next.path === 'replay' && next.chunk.chunkSeq).toBe(0);
    outbox.remove(next);
    expect(outbox.size.bytes).toBeLessThanOrEqual(10_000);
  });

  it('drops a full snapshot over the limit once, without asking for another snapshot', () => {
    const dropped = vi.fn();
    const tooLarge = vi.fn();
    const outbox = new Outbox('0.1.0', dropped, 10_000, tooLarge, 50_000);
    outbox.addEvent(S1, ANON, event(1));
    outbox.addChunk(snapshot(0, 60_000));
    expect(outbox.pendingChunks).toEqual([]);
    expect(outbox.size.events).toBe(1);
    expect(outbox.dropped.replayChunks).toBe(1);
    expect(tooLarge).toHaveBeenCalledTimes(1);
    expect(tooLarge.mock.calls[0]![0]).toBeGreaterThan(50_000);
    expect(dropped).not.toHaveBeenCalled(); // no recovery snapshot: it would be too large again
  });

  it('measures the snapshot limit in UTF-8 bytes', () => {
    const tooLarge = vi.fn();
    const outbox = new Outbox('0.1.0', () => {}, 10_000, tooLarge, 30_000);
    const body = JSON.stringify({ sessionId: S1, chunkSeq: 0, events: ['€'.repeat(12_000)] });
    outbox.addChunk({ sessionId: S1, chunkSeq: 0, body, bytes: body.length, fullSnapshot: true });
    expect(tooLarge).toHaveBeenCalledTimes(1); // ~12k UTF-16 units, ~36 kB of UTF-8
  });

  it('a newer full snapshot takes the place outside the bound; the older one counts normally', () => {
    const outbox = new Outbox(
      '0.1.0',
      () => {},
      10_000,
      () => {},
      100_000,
    );
    outbox.addChunk(snapshot(0, 30_000));
    outbox.addChunk(snapshot(1, 30_000));
    expect(outbox.pendingChunks.map((c) => c.chunkSeq)).toEqual([1]); // the old one no longer fits
  });

  it('never beacons a large snapshot', () => {
    const outbox = new Outbox(
      '0.1.0',
      () => {},
      10_000,
      () => {},
      100_000,
    );
    outbox.addEvent(S1, ANON, event(1));
    outbox.addChunk(snapshot(0, 70_000));
    const planned = outbox.forBeacon(BEACON_BUDGET_BYTES, utf8Length);
    expect(planned.map((r) => r.path)).toEqual(['events']);
  });
});

describe('client end to end (jsdom, stubbed network)', () => {
  let client: Client | undefined;
  const start = (sampleRate: number) => {
    client = new Client(
      resolveConfig({ ...OPTIONS, collectorUrl: `https://${host}`, sampleRate }, log)!,
      log,
    );
    client.start();
    return client;
  };

  beforeEach(() => {
    localStorage.clear();
    sessionStorage.clear();
    document.body.innerHTML = '<button id="b">Go</button>';
  });

  afterEach(() => {
    client?.shutdown();
    client = undefined;
  });

  it('sampling 1 sends replay (chunk 0 right away) and events', async () => {
    const c = start(1);
    await vi.waitFor(() => expect(calls.some((x) => x.url.includes('/v1/replay'))).toBe(true));
    const replay = JSON.parse(calls.find((x) => x.url.includes('/v1/replay'))!.body) as ReplayBatch;
    expect(replay.chunkSeq).toBe(0);
    expect(replay.events.map((e) => (e as { type: number }).type)).toEqual([
      RRWEB_META,
      RRWEB_FULL_SNAPSHOT,
    ]);
    document.getElementById('b')!.click();
    c.flush();
    await vi.waitFor(() => expect(calls.some((x) => x.url.includes('/v1/events'))).toBe(true));
    const batch = JSON.parse(calls.find((x) => x.url.includes('/v1/events'))!.body) as EventBatch;
    expect(batch.sessionId).toBe(c.getSessionId());
    expect(batch.events.map((e) => e.type)).toContain('NAVIGATION');
  });

  it('sampling 0 sends nothing, not even on page hide', async () => {
    const beacon = vi.fn(() => true);
    Object.defineProperty(navigator, 'sendBeacon', { configurable: true, value: beacon });
    const c = start(0);
    document.getElementById('b')!.click();
    c.flush();
    window.dispatchEvent(new Event('pagehide'));
    c.shutdown();
    await settle();
    expect(calls).toEqual([]); // nothing to this test's collector
    expect(beacon).not.toHaveBeenCalled();
  });

  it('a 401 stops the SDK for this page: nothing more is captured or sent', async () => {
    responses = [401];
    const originalError = console.error;
    const sdk = await loadSdk();
    const options = { ...OPTIONS, collectorUrl: `https://${host}` };
    sdk.init(options);
    await vi.waitFor(() => expect(calls).toHaveLength(1));
    await settle();
    expect(sdk.getSessionId()).toBeNull();
    expect(console.error).toBe(originalError);
    sdk.init(options); // still a no-op for this page
    document.getElementById('b')!.click();
    window.dispatchEvent(new Event('pagehide'));
    await settle();
    expect(calls).toHaveLength(1);
  });

  it.each([
    [401, 'invalid site key'],
    [403, 'origin not allowed for this site'],
  ])(
    'a readable %s stops after one request and says why in debug mode',
    async (status, message) => {
      responses = [status];
      const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
      const sdk = await loadSdk();
      sdk.init({ ...OPTIONS, collectorUrl: `https://${host}`, debug: true });
      await vi.waitFor(() => expect(sdk.getSessionId()).toBeNull());
      await new Promise((r) => setTimeout(r, 100)); // no retry was scheduled: nothing more is sent
      expect(calls).toHaveLength(1);
      const logged = warn.mock.calls.map((args) => args.join(' '));
      expect(logged.filter((line) => line.includes(message))).toHaveLength(1);
      expect(logged.some((line) => line.includes('unreachable'))).toBe(false);
      warn.mockRestore();
    },
  );

  it('sends a ~6 MB full snapshot in one normal request, without dropping or re-snapshotting', async () => {
    responses = [202];
    document.body.insertAdjacentHTML(
      'beforeend',
      `<p id="big">${'lorem ipsum '.repeat(512 * 1024)}</p>`,
    );
    const warn = vi.spyOn(console, 'info').mockImplementation(() => {});
    const sdk = await loadSdk();
    sdk.init({ ...OPTIONS, collectorUrl: `https://${host}`, debug: true });
    await vi.waitFor(() => expect(calls.some((x) => x.url.includes('/v1/replay'))).toBe(true), {
      timeout: 10_000,
    });
    await settle();
    const replays = calls.filter((x) => x.url.includes('/v1/replay'));
    expect(replays).toHaveLength(1); // one normal fetch, not split, not beaconed
    const batch = JSON.parse(replays[0]!.body) as ReplayBatch;
    expect(batch.chunkSeq).toBe(0);
    expect(replays[0]!.body.length).toBeGreaterThan(6_000_000);
    expect(warn.mock.calls.flat().join(' ')).not.toContain('queue full');
    warn.mockRestore();
    document.getElementById('big')!.remove();
  });

  it('a full snapshot over 16 MB turns replay off for the page; events keep flowing', async () => {
    responses = [202];
    document.body.insertAdjacentHTML(
      'beforeend',
      `<p id="huge">${'x'.repeat(17 * 1024 * 1024)}</p>`,
    );
    const logged = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const sdk = await loadSdk();
    sdk.init({ ...OPTIONS, collectorUrl: `https://${host}`, debug: true, flushIntervalMs: 1000 });
    document.getElementById('b')!.click();
    await vi.waitFor(() => expect(calls.some((x) => x.url.includes('/v1/events'))).toBe(true), {
      timeout: 10_000,
    });
    document.getElementById('b')!.click();
    await new Promise((r) => setTimeout(r, 1500)); // another flush interval
    expect(calls.filter((x) => x.url.includes('/v1/replay'))).toEqual([]);
    const lines = logged.mock.calls.map((a) => a.join(' ')).filter((l) => l.includes('replay off'));
    expect(lines).toHaveLength(1); // once: no snapshot loop
    expect(sdk.getSessionId()).not.toBeNull(); // still running
    logged.mockRestore();
    document.getElementById('huge')!.remove();
  });

  it('takes a new full snapshot once the queue drains after dropping replay data', async () => {
    responses = [202];
    const c = start(1);
    await vi.waitFor(() => expect(c.pendingChunks).toHaveLength(0));
    const seen = calls.length;
    // simulate the bound dropping replay data
    (c as unknown as { needsSnapshot: boolean }).needsSnapshot = true;
    c.flush();
    await vi.waitFor(() => expect(calls.length).toBeGreaterThan(seen));
    await vi.waitFor(() => {
      const snapshots = calls
        .filter((x) => x.url.includes('/v1/replay'))
        .map((x) =>
          (JSON.parse(x.body) as ReplayBatch).events.map((e) => (e as { type: number }).type),
        );
      expect(snapshots.filter((t) => t.includes(RRWEB_FULL_SNAPSHOT))).toHaveLength(2);
    });
  });
});
