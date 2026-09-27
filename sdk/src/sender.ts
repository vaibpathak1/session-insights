import { detach } from './guard';
import type { Logger } from './log';
import type { Outbox, Outgoing } from './outbox';
import { backoffMs, type Transport } from './transport';

/** Browsers cap in-flight beacon/keepalive bodies at ~64 KB per page; stay under it. */
export const BEACON_BUDGET_BYTES = 60 * 1024;
/**
 * In a browser a 401/403 without CORS headers looks like a network error, so repeated
 * failures before any success are treated like a refusal (bad key, origin or CSP).
 */
export const MAX_FAILURES_BEFORE_FIRST_SUCCESS = 5;

export type FatalReason = { status: number } | { unreachable: true };

export interface SenderHooks {
  /** 401/403, or never reachable: the client stops for this page. */
  onFatal(reason: FatalReason): void;
  /** The queue was fully sent. */
  onDrained(): void;
}

const encoder = typeof TextEncoder !== 'undefined' ? new TextEncoder() : null;
const utf8Length = (s: string): number => (encoder ? encoder.encode(s).length : s.length * 3);

/** Sends the outbox one request at a time, with retries (task 3.7). */
export class Sender {
  private running = false;
  private stopped = false;
  private final = false;
  private attempt = 0;
  private failures = 0;
  private everSucceeded = false;
  private retryTimer: ReturnType<typeof setTimeout> | null = null;

  constructor(
    private readonly outbox: Outbox,
    private readonly transport: Transport,
    private readonly hooks: SenderHooks,
    private readonly log: Logger,
    private readonly random: () => number = Math.random,
  ) {}

  /** Starts sending unless a send or a scheduled retry is already pending. */
  flush(): void {
    if (this.stopped || this.running || this.retryTimer !== null) return;
    this.running = true;
    detach(
      this.run().finally(() => {
        this.running = false;
      }),
      this.log,
    );
  }

  /** Sends what is queued once more without retrying, then stops (shutdown). */
  finish(): void {
    this.final = true;
    if (this.retryTimer !== null) {
      clearTimeout(this.retryTimer);
      this.retryTimer = null;
    }
    this.flush();
  }

  /** Page hide: beacons within the ~64 KB budget; what does not fit stays queued. */
  pageHide(): void {
    if (this.stopped) return;
    const planned = this.outbox.forBeacon(BEACON_BUDGET_BYTES, utf8Length);
    let sent = 0;
    for (const request of planned) {
      if (!this.transport.beacon(request.path, request.body)) break;
      this.outbox.remove(request);
      sent++;
    }
    const left = this.outbox.size;
    if (left.events > 0 || left.chunks > 0) {
      this.log.info(
        `page hide: ${sent} beacon(s) sent; not sent (budget): ${left.events} event(s), ${left.chunks} replay chunk(s)`,
      );
    }
  }

  stop(): void {
    this.stopped = true;
    if (this.retryTimer !== null) clearTimeout(this.retryTimer);
    this.retryTimer = null;
  }

  private async run(): Promise<void> {
    for (;;) {
      if (this.stopped) return;
      const request = this.outbox.next();
      if (!request) {
        this.hooks.onDrained();
        // onDrained may queue more (a recovery snapshot); send it in this run
        if (!this.outbox.isEmpty) continue;
        if (this.final) this.stop();
        return;
      }
      const result = await this.transport.send(request.path, request.body);
      if (this.stopped) return;
      switch (result.kind) {
        case 'ok':
          this.succeeded(request);
          continue;
        case 'drop':
          this.log.warn(`collector refused a ${request.path} request (${result.status}); dropped`);
          this.outbox.remove(request);
          continue;
        case 'fatal':
          this.stop();
          this.hooks.onFatal({ status: result.status });
          return;
        case 'retry':
          this.failures++;
          if (!this.everSucceeded && this.failures >= MAX_FAILURES_BEFORE_FIRST_SUCCESS) {
            this.stop();
            this.hooks.onFatal({ unreachable: true });
            return;
          }
          if (this.final) {
            this.stop();
            return;
          }
          this.scheduleRetry(result.retryAfterMs ?? backoffMs(this.attempt++, this.random));
          return;
      }
    }
  }

  private succeeded(request: Outgoing): void {
    this.outbox.remove(request);
    this.everSucceeded = true;
    this.attempt = 0;
    this.failures = 0;
  }

  private scheduleRetry(delayMs: number): void {
    this.log.info(`send failed; retrying in ${delayMs} ms`);
    this.retryTimer = setTimeout(() => {
      this.retryTimer = null;
      this.flush();
    }, delayMs);
  }
}
