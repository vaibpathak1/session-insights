/**
 * Runs `fn` when the main thread is idle (or within `timeoutMs`), so batching and
 * serialisation stay off the page's hot path. Falls back to a macrotask.
 */
export function whenIdle(fn: () => void, timeoutMs = 2000): void {
  const ric = (globalThis as { requestIdleCallback?: typeof requestIdleCallback })
    .requestIdleCallback;
  if (typeof ric === 'function') ric(() => fn(), { timeout: timeoutMs });
  else setTimeout(fn, 0);
}
