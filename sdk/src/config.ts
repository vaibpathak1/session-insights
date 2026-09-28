import type { Logger } from './log';

/** Options for `SessionInsights.init`. */
export interface InitOptions {
  /** Public site key (`sk_…`). Sent as the `?k=` query parameter, never as a header. */
  siteKey: string;
  /** Collector base URL, e.g. `https://collect.example.com`. */
  collectorUrl: string;
  /** Fraction of sessions recorded, 0–1. Decided once per session. Default 1. */
  sampleRate?: number;
  /** Mask all page text, not only inputs. Default false. */
  maskAllText?: boolean;
  /** How often buffered data is sent, in ms (1000–60000). Default 5000. */
  flushIntervalMs?: number;
  /** Log SDK diagnostics to the console (warn/info). Default false. */
  debug?: boolean;
}

export interface Config {
  readonly siteKey: string;
  /** Normalised: no trailing slash. */
  readonly collectorUrl: string;
  readonly sampleRate: number;
  readonly maskAllText: boolean;
  readonly flushIntervalMs: number;
  readonly debug: boolean;
}

export const DEFAULT_FLUSH_INTERVAL_MS = 5000;
const MIN_FLUSH_INTERVAL_MS = 1000;
const MAX_FLUSH_INTERVAL_MS = 60000;

/** Validates options. Returns null (and logs why) when the SDK cannot start. */
export function resolveConfig(options: unknown, log: Logger): Config | null {
  if (typeof options !== 'object' || options === null) {
    log.warn('init: options object is required');
    return null;
  }
  const o = options as Partial<Record<keyof InitOptions, unknown>>;
  if (typeof o.siteKey !== 'string' || o.siteKey.trim() === '') {
    log.warn('init: siteKey is required');
    return null;
  }
  const collectorUrl = normaliseUrl(o.collectorUrl);
  if (collectorUrl === null) {
    log.warn('init: collectorUrl must be an absolute http(s) URL');
    return null;
  }

  let sampleRate = 1;
  if (o.sampleRate !== undefined) {
    if (typeof o.sampleRate === 'number' && Number.isFinite(o.sampleRate)) {
      sampleRate = Math.min(1, Math.max(0, o.sampleRate));
    } else {
      log.warn('init: sampleRate must be a number between 0 and 1; using 1');
    }
  }

  let flushIntervalMs = DEFAULT_FLUSH_INTERVAL_MS;
  if (o.flushIntervalMs !== undefined) {
    if (typeof o.flushIntervalMs === 'number' && Number.isFinite(o.flushIntervalMs)) {
      flushIntervalMs = Math.round(
        Math.min(MAX_FLUSH_INTERVAL_MS, Math.max(MIN_FLUSH_INTERVAL_MS, o.flushIntervalMs)),
      );
    } else {
      log.warn(`init: flushIntervalMs must be a number; using ${DEFAULT_FLUSH_INTERVAL_MS}`);
    }
  }

  return {
    siteKey: o.siteKey.trim(),
    collectorUrl,
    sampleRate,
    maskAllText: o.maskAllText === true,
    flushIntervalMs,
    debug: o.debug === true,
  };
}

function normaliseUrl(value: unknown): string | null {
  if (typeof value !== 'string') return null;
  try {
    const url = new URL(value);
    if (url.protocol !== 'https:' && url.protocol !== 'http:') return null;
    url.search = '';
    url.hash = '';
    return url.toString().replace(/\/+$/, '');
  } catch {
    return null;
  }
}
