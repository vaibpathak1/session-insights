import { Client } from './client';
import { resolveConfig, type InitOptions } from './config';
import { guard } from './guard';
import { createLogger } from './log';

export type { InitOptions } from './config';

/** User traits for `identify` (reserved; see F2). */
export type Traits = Record<string, unknown>;
/** Event properties for `track` (reserved; see F15). */
export type Props = Record<string, unknown>;

/** SDK version, reported to the collector as `sdkVersion`. */
export const version: string = __SDK_VERSION__;

let client: Client | undefined;

/**
 * Starts recording. Safe to call more than once: later calls are ignored while an instance
 * is running (and after the collector refused the key or origin for this page).
 */
export function init(options: InitOptions): void {
  guard(() => {
    if (client) {
      client.log.warn('init called again; ignoring (call shutdown() first to re-initialise)');
      return;
    }
    const log = createLogger(isDebug(options));
    const config = resolveConfig(options, log);
    if (!config) return;
    const created = new Client(config, log);
    client = created;
    guard(() => created.start(), undefined, log);
  }, undefined);
}

/** Reserved for F2 (user identify). Currently a no-op that logs in debug mode. */
export function identify(userId: string, traits?: Traits): void {
  void userId;
  void traits;
  guard(() => client?.log.info('identify() is not available yet (F2); ignored'), undefined);
}

/** Reserved for F15 (custom events). Currently a no-op that logs in debug mode. */
export function track(name: string, props?: Props): void {
  void name;
  void props;
  guard(() => client?.log.info('track() is not available yet (F15); ignored'), undefined);
}

/** Starts a new anonymous identity and a new session (e.g. on logout). */
export function reset(): void {
  guard(() => client?.reset(), undefined, client?.log);
}

/** Flushes buffered data and stops recording. `init` may be called again afterwards. */
export function shutdown(): void {
  guard(
    () => {
      const current = client;
      client = undefined;
      current?.shutdown();
    },
    undefined,
    client?.log,
  );
}

/** The current session id, or null before `init` / when not running. */
export function getSessionId(): string | null {
  return guard(() => client?.getSessionId() ?? null, null, client?.log);
}

function isDebug(options: unknown): boolean {
  try {
    return (
      typeof options === 'object' &&
      options !== null &&
      (options as { debug?: unknown }).debug === true
    );
  } catch {
    return false; // e.g. a throwing getter
  }
}
