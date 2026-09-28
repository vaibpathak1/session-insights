/**
 * Wire contracts, mirroring platform-common `io.sessioninsights.common.wire` (EventBatch,
 * TelemetryEvent, ReplayBatch). contracts/fixtures/*.json keep the two sides in sync.
 * The site key is never in the body: it is sent as `?k=` (ADR-0010).
 */

/** Event types the SDK sends; a subset of platform-common `EventType`. */
export type EventType = 'CLICK' | 'NAVIGATION' | 'CONSOLE_ERROR' | 'EXCEPTION';

export interface TelemetryEvent {
  /** Idempotency key downstream (ADR-0004). */
  clientEventId: string;
  type: EventType;
  /** Client clock, epoch milliseconds. */
  ts: number;
  url?: string;
  path?: string;
  title?: string;
  targetSelector?: string;
  targetText?: string;
  errorMessage?: string;
  errorStack?: string;
  eventName?: string;
  props?: Record<string, unknown>;
}

/** `POST /v1/events` body. */
export interface EventBatch {
  sessionId: string;
  anonymousId: string;
  sdkVersion: string;
  events: TelemetryEvent[];
}

/** `POST /v1/replay` body: one chunk of raw rrweb events (the only form the collector accepts). */
export interface ReplayBatch {
  sessionId: string;
  chunkSeq: number;
  events: unknown[];
}

/** Collector limit: events per batch (`collector.limits.max-events`). */
export const MAX_EVENTS_PER_BATCH = 500;

/** Field sizes the SDK sends; at or below the collector's limits (CollectorProperties). */
export const FIELD_LIMITS = {
  url: 2048,
  path: 1024,
  title: 512,
  targetSelector: 256,
  targetText: 100,
  errorMessage: 1024,
  errorStack: 4096,
} as const;

/** Truncates to `max` UTF-16 units without splitting a surrogate pair. */
export function truncate(value: string, max: number): string {
  if (value.length <= max) return value;
  const end = /[\uD800-\uDBFF]/.test(value.charAt(max - 1)) ? max - 1 : max;
  return value.slice(0, end);
}
