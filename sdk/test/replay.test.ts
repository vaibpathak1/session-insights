import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { Client } from '../src/client';
import { resolveConfig } from '../src/config';
import { createLogger } from '../src/log';
import { RRWEB_FULL_SNAPSHOT, RRWEB_META } from '../src/recorder';
import { ReplayBuffer, type SealedChunk } from '../src/replay';
import { SESSION_KEY } from '../src/session';
import type { ReplayBatch } from '../src/wire';
import { OPTIONS } from './helpers';

const log = createLogger(false);
const parse = (c: SealedChunk) => JSON.parse(c.body) as ReplayBatch;
const types = (c: SealedChunk) => parse(c).events.map((e) => (e as { type: number }).type);

describe('ReplayBuffer', () => {
  let seq: number;
  let chunks: SealedChunk[];
  let buffer: ReplayBuffer;
  const S = '11111111-1111-4111-8111-111111111111';

  beforeEach(() => {
    seq = 0;
    chunks = [];
    buffer = new ReplayBuffer(
      () => seq++,
      (c) => chunks.push(c),
      1000,
    );
  });

  it('seals a ReplayBatch with increasing chunkSeq', () => {
    buffer.add(S, { type: RRWEB_META, timestamp: 1 });
    buffer.add(S, { type: RRWEB_FULL_SNAPSHOT, timestamp: 2 });
    buffer.seal();
    buffer.add(S, { type: 3, timestamp: 3 });
    buffer.seal();
    buffer.seal(); // empty: no chunk
    expect(chunks.map(parse)).toEqual([
      {
        sessionId: S,
        chunkSeq: 0,
        events: [
          { type: RRWEB_META, timestamp: 1 },
          { type: RRWEB_FULL_SNAPSHOT, timestamp: 2 },
        ],
      },
      { sessionId: S, chunkSeq: 1, events: [{ type: 3, timestamp: 3 }] },
    ]);
    expect(chunks[0]!.bytes).toBe(chunks[0]!.body.length);
  });

  it('starts a new chunk at every Meta event', () => {
    buffer.add(S, { type: 3, timestamp: 1 });
    buffer.add(S, { type: RRWEB_META, timestamp: 2 });
    buffer.add(S, { type: RRWEB_FULL_SNAPSHOT, timestamp: 3 });
    buffer.seal();
    expect(chunks.map(types)).toEqual([[3], [RRWEB_META, RRWEB_FULL_SNAPSHOT]]);
  });

  it('seals when the chunk reaches the size limit', () => {
    const big = { type: 3, timestamp: 1, data: 'x'.repeat(600) };
    buffer.add(S, big);
    expect(chunks).toHaveLength(0);
    buffer.add(S, big);
    expect(chunks).toHaveLength(1);
    expect(parse(chunks[0]!).events).toHaveLength(2);
  });
});

describe('client replay chunks (rrweb in jsdom)', () => {
  let client: Client;

  function start(overrides: Partial<typeof OPTIONS & { sampleRate: number }> = {}) {
    client = new Client(resolveConfig({ ...OPTIONS, ...overrides }, log)!, log);
    client.start();
    return client;
  }

  beforeEach(() => {
    localStorage.clear();
    sessionStorage.clear();
    document.body.innerHTML = '<main><p id="p">hello</p></main>';
  });

  afterEach(() => {
    client?.shutdown();
    vi.useRealTimers();
  });

  it('sends chunk 0 with the full snapshot right away, then numbered chunks', async () => {
    start();
    expect(client.pendingChunks).toHaveLength(1);
    const first = client.pendingChunks[0]!;
    expect(first.chunkSeq).toBe(0);
    expect(first.sessionId).toBe(client.getSessionId());
    expect(types(first)).toEqual([RRWEB_META, RRWEB_FULL_SNAPSHOT]);

    document.getElementById('p')!.textContent = 'changed';
    await new Promise((r) => setTimeout(r, 0)); // MutationObserver callbacks
    client.flush();
    expect(client.pendingChunks.map((c) => c.chunkSeq)).toEqual([0, 1]);
    expect(types(client.pendingChunks[1]!).every((t) => t === 3)).toBe(true);
  });

  it('continues chunkSeq after a reload in the same tab', () => {
    start();
    client.shutdown();
    const stored = JSON.parse(sessionStorage.getItem(SESSION_KEY)!) as { nextChunkSeq: number };
    expect(stored.nextChunkSeq).toBe(1);
    start(); // same tab, same session: new full snapshot gets chunkSeq 1
    expect(client.pendingChunks.map((c) => c.chunkSeq)).toEqual([1]);
    expect(types(client.pendingChunks[0]!)).toEqual([RRWEB_META, RRWEB_FULL_SNAPSHOT]);
  });

  it('a new session restarts at chunkSeq 0 with a full snapshot', () => {
    start();
    const before = client.getSessionId();
    client.reset();
    const after = client.pendingChunks.filter((c) => c.sessionId !== before);
    expect(after.map((c) => [c.chunkSeq, types(c)])).toEqual([
      [0, [RRWEB_META, RRWEB_FULL_SNAPSHOT]],
    ]);
  });

  it('records nothing and patches nothing when the session is not sampled', () => {
    const consoleError = console.error;
    const pushState = history.pushState;
    start({ sampleRate: 0 });
    history.pushState(null, '', '/elsewhere');
    document.body.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    client.flush();
    expect(client.pendingChunks).toEqual([]);
    expect(client.pendingEvents).toEqual([]);
    expect(console.error).toBe(consoleError);
    expect(history.pushState).toBe(pushState);
  });

  it('stamps derived events with id, time and identity', () => {
    start();
    document.getElementById('p')!.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    const [nav, click] = client.pendingEvents;
    expect(nav!.event.type).toBe('NAVIGATION');
    expect(click).toMatchObject({
      sessionId: client.getSessionId(),
      event: { type: 'CLICK', targetText: 'hello', targetSelector: 'p#p' },
    });
    expect(click!.event.clientEventId).toMatch(/^[0-9a-f-]{36}$/);
    expect(typeof click!.event.ts).toBe('number');
  });
});
