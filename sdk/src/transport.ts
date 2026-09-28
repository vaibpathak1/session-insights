import type { Logger } from './log';

export type SendResult =
  | { kind: 'ok' }
  /** Network error, 429, 5xx: try again later. */
  | { kind: 'retry'; retryAfterMs?: number; status?: number }
  /** 400, 413 and other 4xx: the request itself is unacceptable; drop it. */
  | { kind: 'drop'; status: number }
  /** 401/403: bad key or origin; stop the SDK for this page. */
  | { kind: 'fatal'; status: number };

export interface PreparedBody {
  payload: string | ArrayBuffer;
  /** Bytes on the wire. */
  bytes: number;
  gzip: boolean;
}

/** Normal sends up to this size use `keepalive` (browsers cap keepalive at ~64 KB in flight). */
export const KEEPALIVE_MAX_BYTES = 32 * 1024;

const encoder = typeof TextEncoder !== 'undefined' ? new TextEncoder() : null;
export const utf8Length = (s: string): number =>
  encoder ? encoder.encode(s).length : s.length * 3;

export interface TransportOptions {
  collectorUrl: string;
  siteKey: string;
  log: Logger;
  now?: () => number;
}

/** Longest `Retry-After` the SDK honours. */
const MAX_RETRY_AFTER_MS = 10 * 60 * 1000;

/**
 * HTTP to the collector (task 3.7). The site key is always the `?k=` query parameter, never a
 * header (ADR-0010). Bodies are `text/plain` so an uncompressed request is a CORS "simple
 * request"; gzip adds `Content-Encoding`, which the collector's preflight allows.
 */
export class Transport {
  private readonly now: () => number;

  constructor(private readonly options: TransportOptions) {
    this.now = options.now ?? Date.now;
  }

  url(path: 'events' | 'replay'): string {
    return `${this.options.collectorUrl}/v1/${path}?k=${encodeURIComponent(this.options.siteKey)}`;
  }

  /** Compresses the body when possible (async, off the main thread in CompressionStream). */
  async prepare(body: string): Promise<PreparedBody> {
    const compressed = await gzip(body);
    if (compressed) {
      return { payload: compressed, bytes: compressed.byteLength, gzip: true };
    }
    return { payload: body, bytes: utf8Length(body), gzip: false };
  }

  /**
   * Posts a prepared body. `keepalive` lets a small request outlive the page, so a page-hide
   * beacon need not resend it; browsers refuse keepalive bodies over ~64 KB in total.
   */
  async post(
    path: 'events' | 'replay',
    body: PreparedBody,
    keepalive: boolean,
  ): Promise<SendResult> {
    const headers: Record<string, string> = { 'Content-Type': 'text/plain;charset=UTF-8' };
    if (body.gzip) headers['Content-Encoding'] = 'gzip';
    let response: Response;
    try {
      response = await fetch(this.url(path), {
        method: 'POST',
        headers,
        body: body.payload,
        keepalive,
        credentials: 'omit',
        mode: 'cors',
      });
    } catch {
      return { kind: 'retry' }; // network error, or CORS refusal (opaque; see README)
    }
    return classify(response.status, response.headers.get('Retry-After'), this.now());
  }

  /**
   * Page-hide send: `sendBeacon` with an uncompressed `text/plain` body (a beacon cannot set
   * headers). Falls back to `fetch` with `keepalive`. Returns false if the browser refused
   * (e.g. over the ~64 KB in-flight quota).
   */
  beacon(path: 'events' | 'replay', body: string): boolean {
    const url = this.url(path);
    try {
      const nav = typeof navigator !== 'undefined' ? navigator : undefined;
      if (nav && typeof nav.sendBeacon === 'function') {
        return nav.sendBeacon(url, new Blob([body], { type: 'text/plain;charset=UTF-8' }));
      }
      if (typeof fetch !== 'function') return false;
      return this.keepalive(url, body);
    } catch {
      return false; // some browsers throw instead of returning false
    }
  }

  private keepalive(url: string, body: string): boolean {
    fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'text/plain;charset=UTF-8' },
      body,
      keepalive: true,
      credentials: 'omit',
      mode: 'cors',
    }).catch(() => this.options.log.info('keepalive send failed on page hide'));
    return true;
  }
}

export function classify(status: number, retryAfter: string | null, now: number): SendResult {
  if (status >= 200 && status < 300) return { kind: 'ok' };
  if (status === 401 || status === 403) return { kind: 'fatal', status };
  if (status === 429 || status >= 500) {
    const retryAfterMs = parseRetryAfter(retryAfter, now);
    return retryAfterMs === undefined
      ? { kind: 'retry', status }
      : { kind: 'retry', status, retryAfterMs };
  }
  return { kind: 'drop', status };
}

/** `Retry-After` as delay-seconds or an HTTP date; capped at 10 minutes. */
export function parseRetryAfter(value: string | null, now: number): number | undefined {
  if (!value) return undefined;
  const trimmed = value.trim();
  let ms: number;
  if (/^\d+$/.test(trimmed)) {
    ms = Number(trimmed) * 1000;
  } else {
    const date = Date.parse(trimmed);
    if (Number.isNaN(date)) return undefined;
    ms = Math.max(0, date - now);
  }
  return Math.min(ms, MAX_RETRY_AFTER_MS);
}

/** Exponential backoff with full jitter: random in [0, min(30 s, 1 s × 2^attempt)]. */
export function backoffMs(attempt: number, random: () => number = Math.random): number {
  const ceiling = Math.min(30_000, 1000 * 2 ** Math.min(attempt, 10));
  return Math.round(random() * ceiling);
}

/** gzip via CompressionStream when the browser has it; null otherwise. */
export async function gzip(text: string): Promise<ArrayBuffer | null> {
  if (typeof CompressionStream === 'undefined' || typeof Response === 'undefined') return null;
  try {
    const source = new Response(text).body;
    if (!source) return null;
    const stream = source.pipeThrough(new CompressionStream('gzip'));
    return await new Response(stream).arrayBuffer();
  } catch {
    return null; // fall back to plain JSON
  }
}
