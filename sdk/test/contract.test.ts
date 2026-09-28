/**
 * Shared contract fixtures (task 3.9). The SDK's real code paths (capture → outbox for
 * events; rrweb → replay buffer for replay) produce contracts/fixtures/*.json, which
 * collector-service's ContractFixturesTest validates with the real BatchValidator.
 *
 * Regenerate after an intentional contract change:  UPDATE_FIXTURES=1 npx vitest run test/contract.test.ts
 */
import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { afterAll, beforeAll, describe, expect, it, vi } from 'vitest';
import { installCapture, type CapturedEvent } from '../src/capture';
import { createLogger } from '../src/log';
import { Outbox } from '../src/outbox';
import { startRecorder } from '../src/recorder';
import { ReplayBuffer, type SealedChunk } from '../src/replay';

const FIXTURES = resolve('..', 'contracts', 'fixtures'); // vitest runs from sdk/
const UPDATE = process.env.UPDATE_FIXTURES === '1';

const SESSION_ID = '0f8e2c1a-5b7d-4e3f-9a1b-2c3d4e5f6a7b';
const ANONYMOUS_ID = '6a7b8c9d-0e1f-4a2b-8c3d-4e5f6a7b8c9d';
const T0 = Date.parse('2026-09-27T10:00:00.000Z');
const SDK_VERSION = '0.1.0';

const PAGE = `
  <main>
    <h1>Checkout</h1>
    <form data-si-unmask><input name="coupon" value="WELCOME10"></form>
    <input type="password" name="password" value="hunter2">
    <input name="email" value="jane@example.com">
    <button id="pay" class="btn primary">Pay now</button>
  </main>`;

function errorWithStack(message: string, stack: string): Error {
  const e = new Error(message);
  e.stack = stack;
  return e;
}

function check(name: string, actual: string): void {
  const file = resolve(FIXTURES, name);
  const pretty = `${JSON.stringify(JSON.parse(actual), null, 2)}\n`;
  if (UPDATE || !existsSync(file)) {
    writeFileSync(file, pretty);
    if (!UPDATE) throw new Error(`created ${file}; review and commit it`);
  }
  expect(JSON.parse(readFileSync(file, 'utf8'))).toEqual(JSON.parse(actual));
}

describe('contract fixtures', () => {
  beforeAll(() => {
    vi.useFakeTimers({ now: T0, toFake: ['Date'] });
    history.replaceState(null, '', '/checkout?step=2');
    document.title = 'Checkout';
    document.body.innerHTML = PAGE;
  });
  afterAll(() => {
    vi.useRealTimers();
  });

  it('EventBatch: click, navigation, console error, exception', () => {
    const captured: CapturedEvent[] = [];
    const savedError = console.error;
    console.error = () => {};
    const uninstall = installCapture({
      maskAllText: false,
      emit: (e) => captured.push(e),
      log: createLogger(false),
    });
    try {
      document.getElementById('pay')!.click();
      console.error(
        'Payment failed:',
        errorWithStack(
          'card declined',
          'Error: card declined\n    at pay (https://shop.example/assets/app.js:120:15)',
        ),
      );
      window.dispatchEvent(
        new ErrorEvent('error', {
          message: 'Uncaught TypeError: cart.total is not a function',
          error: errorWithStack(
            'cart.total is not a function',
            'TypeError: cart.total is not a function\n    at render (https://shop.example/assets/app.js:88:9)',
          ),
        }),
      );
    } finally {
      uninstall();
      console.error = savedError;
    }

    const outbox = new Outbox(SDK_VERSION);
    captured.forEach((event, i) => {
      outbox.addEvent(SESSION_ID, ANONYMOUS_ID, {
        clientEventId: `7c9e6679-7425-40de-944b-e07fc1f90a${String(i).padStart(2, '0')}`,
        ts: T0 + i * 1000,
        ...event,
      });
    });
    const request = outbox.next()!;
    expect(request.path).toBe('events');
    expect(captured.map((e) => e.type)).toEqual([
      'NAVIGATION',
      'CLICK',
      'CONSOLE_ERROR',
      'EXCEPTION',
    ]);
    check('event-batch.json', request.body);
  });

  it('ReplayBatch: chunk 0 with the (masked) full snapshot', () => {
    let sealed: SealedChunk | undefined;
    const buffer = new ReplayBuffer(
      () => 0,
      (c) => (sealed = c),
    );
    const recorder = startRecorder({
      maskAllText: false,
      emit: (e) => buffer.add(SESSION_ID, e),
      onError: (e) => {
        throw e;
      },
    })!;
    recorder.stop();
    buffer.seal();
    expect(sealed).toBeDefined();
    expect(sealed!.body).not.toContain('hunter2');
    expect(sealed!.body).not.toContain('jane@example.com');
    expect(sealed!.body).toContain('WELCOME10'); // data-si-unmask
    // rrweb stamps events with a Date.now reference taken at import (before fake timers):
    // pin timestamps so the fixture is stable; the contract is the shape, not the clock
    const batch = JSON.parse(sealed!.body) as { events: Array<{ timestamp: number }> };
    batch.events.forEach((e, i) => (e.timestamp = T0 + i));
    check('replay-batch.json', JSON.stringify(batch));
  });
});
