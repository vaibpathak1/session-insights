import { detach } from './guard';
import type { Logger } from './log';
import type { Outbox, Outgoing } from './outbox';
import { backoffMs, KEEPALIVE_MAX_BYTES, utf8Length, type Transport } from './transport';

/** Browsers cap in-flight beacon/keepalive bodies at ~64 KB per page; stay under it. */
export const BEACON_BUDGET_BYTES = 60 * 1024;
/**
 * 401/403 are readable (ADR-0012) and stop the SDK at once. Repeated network errors before
 * any success (collector down, CSP, ad blocker, or a collector older than ADR-0012) are
 * treated like a refusal too.
 */
export const MAX_FAILURES_BEFORE_FIRST_SUCCESS = 5;

export type FatalReason = { status: number } | { unreachable: true };

export interface SenderHooks {
  /** 401/403, or never reachable: the client stops for this page. */
  onFatal(reason: FatalReason): void;
  /** The queue was fully sent. */
  onDrained(): void;
}

/** Sends the outbox one request at a time, with retries (task 3.7). */
export class Sender {
  private running = false;
  private stopped = false;
  private final = false;
  private attempt = 0;
  private failures = 0;
  private everSucceeded = false;
  private retryTimer: ReturnType<typeof setTimeout> | null = null;
  /** The request on the wire with `keepalive`: it survives unload, so beacons skip it. */
  private inflight: { request: Outgoing; bytes: number } | null = null;

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

  /**
   * Page hide: beacons within the ~64 KB budget; what does not fit stays queued. A
   * `keepalive` request already in flight is not resent, and its bytes count against the
   * budget (browsers share one quota).
   */
  pageHide(): void {
    if (this.stopped) return;
    const inflight = this.inflight;
    const budget = BEACON_BUDGET_BYTES - (inflight ? inflight.bytes : 0);
    const planned = this.outbox.forBeacon(budget, utf8Length, inflight?.request ?? null);
    let sent = 0;
    for (const request of planned) {
      if (!this.transport.beacon(request.path, request.body)) break;
      this.outbox.remove(request);
      sent++;
    }
    const left = this.outbox.size;
    if (left.events > 0 || left.chunks > 0) {
      this.log.info(
        `page hide: ${sent} beacon(s) sent; still queued: ${left.events} event(s), ${left.chunks} replay chunk(s)`,
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
      const prepared = await this.transport.prepare(request.body);
      if (this.stopped) return;
      // a page-hide beacon may have sent (some of) these items while compressing: re-plan
      if (!this.outbox.contains(request)) continue;
      const keepalive = prepared.bytes <= KEEPALIVE_MAX_BYTES;
      this.inflight = keepalive ? { request, bytes: prepared.bytes } : null;
      let result;
      try {
        result = await this.transport.post(request.path, prepared, keepalive);
      } finally {
        this.inflight = null;
      }
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
