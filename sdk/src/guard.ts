import type { Logger } from './log';

/**
 * Depth of SDK code currently on the stack. Error capture ignores anything raised while
 * this is > 0, so the SDK never records its own failures.
 */
let depth = 0;

export function inSdk(): boolean {
  return depth > 0;
}

/**
 * Runs `fn` as SDK code: nothing it throws reaches the host page. Every public method and
 * every listener the SDK installs goes through here.
 */
export function guard<T>(fn: () => T, fallback: T, log?: Logger): T {
  depth++;
  try {
    return fn();
  } catch (e) {
    log?.warn('internal error (ignored)', e);
    return fallback;
  } finally {
    depth--;
  }
}

/** `guard` for callbacks: returns a function that never throws. */
export function guarded<A extends unknown[]>(
  fn: (...args: A) => void,
  log?: Logger,
): (...args: A) => void {
  return (...args: A) => guard(() => fn(...args), undefined, log);
}

/** Swallows a rejection from SDK async work so it never becomes an unhandledrejection. */
export function detach(promise: Promise<unknown>, log?: Logger): void {
  promise.catch((e: unknown) => log?.warn('internal async error (ignored)', e));
}
