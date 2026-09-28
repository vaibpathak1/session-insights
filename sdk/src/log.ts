/**
 * Debug logging. Silent unless `debug: true`. Uses console.warn/info only, never
 * console.error, so the SDK's own messages can never be captured as CONSOLE_ERROR events.
 */
export interface Logger {
  readonly enabled: boolean;
  warn(message: string, detail?: unknown): void;
  info(message: string, detail?: unknown): void;
  /** Logs `message` the first time `key` is seen, then never again for this logger. */
  once(key: string, message: string, detail?: unknown): void;
}

const PREFIX = '[SessionInsights]';

export function createLogger(enabled: boolean): Logger {
  const seen = new Set<string>();
  const write = (level: 'warn' | 'info', message: string, detail?: unknown): void => {
    if (!enabled) return;
    try {
      if (detail === undefined) console[level](PREFIX, message);
      else console[level](PREFIX, message, detail);
    } catch {
      // a broken console must not break the SDK
    }
  };
  return {
    enabled,
    warn: (message, detail) => write('warn', message, detail),
    info: (message, detail) => write('info', message, detail),
    once(key, message, detail) {
      if (seen.has(key)) return;
      seen.add(key);
      write('warn', message, detail);
    },
  };
}

export const silentLogger: Logger = createLogger(false);
