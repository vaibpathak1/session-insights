import { listen } from './dom';
import { guard, guarded, inSdk } from './guard';
import type { Logger } from './log';
import { clickText } from './privacy';
import { cssSelector } from './selector';
import { FIELD_LIMITS, truncate, type EventType, type TelemetryEvent } from './wire';

/** A derived event before the SDK stamps it with an id and timestamp. */
export type CapturedEvent = Omit<TelemetryEvent, 'clientEventId' | 'ts'> & { type: EventType };

export interface CaptureOptions {
  maskAllText: boolean;
  emit: (event: CapturedEvent) => void;
  log: Logger;
}

/** Error events (console + exceptions) kept per minute, so an error loop cannot flood. */
const MAX_ERRORS_PER_MINUTE = 30;

type NavigationTrigger = 'load' | 'pushState' | 'replaceState' | 'popstate' | 'hashchange';

/**
 * Installs click, navigation and error capture (task 3.5). Returns the uninstaller.
 * Patched functions always call the original; they are restored only if nobody patched
 * them after us.
 */
export function installCapture(options: CaptureOptions): () => void {
  const { emit, log } = options;
  const disposers: Array<() => void> = [];
  let lastHref: string | null = null;
  let errorWindowStart = 0;
  let errorsInWindow = 0;

  const page = () => ({
    url: truncate(location.href, FIELD_LIMITS.url),
    path: truncate(location.pathname, FIELD_LIMITS.path),
  });

  const navigation = (trigger: NavigationTrigger): void => {
    const href = location.href;
    if (href === lastHref) return; // e.g. replaceState of state only, popstate + hashchange
    lastHref = href;
    emit({
      type: 'NAVIGATION',
      ...page(),
      title: truncate(document.title, FIELD_LIMITS.title),
      props: { trigger },
    });
  };

  const error = (type: 'CONSOLE_ERROR' | 'EXCEPTION', message: string, stack?: string): void => {
    const now = Date.now();
    if (now - errorWindowStart > 60_000) {
      errorWindowStart = now;
      errorsInWindow = 0;
    }
    if (++errorsInWindow > MAX_ERRORS_PER_MINUTE) return;
    emit({
      type,
      ...page(),
      errorMessage: truncate(message, FIELD_LIMITS.errorMessage),
      ...(stack ? { errorStack: truncate(stack, FIELD_LIMITS.errorStack) } : {}),
    });
  };

  /**
   * A guarded listener that ignores events raised while SDK code is on the stack. The
   * check must run before `guard` (which itself marks the stack as SDK code).
   */
  const outsideSdk = (handler: (event: Event) => void) => {
    const safe = guarded(handler, log);
    return (event: Event) => {
      if (!inSdk()) safe(event);
    };
  };

  // CLICK
  disposers.push(
    listen(
      document,
      'click',
      guarded((event: Event) => {
        const target = event.target;
        const element =
          target instanceof Element ? target : target instanceof Node ? target.parentElement : null;
        if (!element) return;
        const text = clickText(element, options.maskAllText, FIELD_LIMITS.targetText);
        emit({
          type: 'CLICK',
          ...page(),
          targetSelector: cssSelector(element),
          ...(text ? { targetText: text } : {}),
        });
      }, log),
      { capture: true, passive: true },
    ),
  );

  // NAVIGATION: initial load, History API, back/forward, hash changes
  navigation('load');
  for (const method of ['pushState', 'replaceState'] as const) {
    disposers.push(
      patch(
        history,
        method,
        (original) =>
          function (this: History, ...args: Parameters<History['pushState']>) {
            const result = original.apply(this, args); // host errors propagate unchanged
            guard(() => navigation(method), undefined, log);
            return result;
          },
      ),
    );
  }
  disposers.push(
    listen(
      window,
      'popstate',
      guarded(() => navigation('popstate'), log),
    ),
  );
  disposers.push(
    listen(
      window,
      'hashchange',
      guarded(() => navigation('hashchange'), log),
    ),
  );

  // CONSOLE_ERROR: always calls the original console.error
  disposers.push(
    patch(
      console,
      'error',
      (original) =>
        function (this: Console, ...args: unknown[]) {
          if (!inSdk()) {
            guard(
              () => {
                const err = args.find((a): a is Error => a instanceof Error);
                error('CONSOLE_ERROR', formatArgs(args), err?.stack);
              },
              undefined,
              log,
            );
          }
          return original.apply(this, args);
        },
    ),
  );

  // EXCEPTION: listeners (not window.onerror), so the page's own handlers keep running.
  // Capture phase: runs even if a page handler stops propagation. Resource load errors
  // also reach a capturing window listener; they are plain Events and are skipped.
  disposers.push(
    listen(
      window,
      'error',
      outsideSdk((event: Event) => {
        if (!(event instanceof ErrorEvent)) return; // resource load errors: not captured in v1
        const err: unknown = event.error;
        const message =
          event.message || (err instanceof Error ? `${err.name}: ${err.message}` : 'Error');
        const stack =
          err instanceof Error && err.stack
            ? err.stack
            : event.filename
              ? `at ${event.filename}:${event.lineno}:${event.colno}`
              : undefined;
        error('EXCEPTION', message, stack);
      }),
      { capture: true },
    ),
  );
  disposers.push(
    listen(
      window,
      'unhandledrejection',
      outsideSdk((event: Event) => {
        const reason: unknown = (event as PromiseRejectionEvent).reason;
        const message =
          reason instanceof Error
            ? `Unhandled rejection: ${reason.name}: ${reason.message}`
            : `Unhandled rejection: ${stringify(reason)}`;
        error('EXCEPTION', message, reason instanceof Error ? reason.stack : undefined);
      }),
      { capture: true },
    ),
  );

  return () => {
    for (const d of disposers.splice(0).reverse()) {
      try {
        d();
      } catch {
        // keep going
      }
    }
  };
}

/**
 * Replaces `target[key]` with `wrap(original)`. The returned function restores the original
 * only if `target[key]` is still our wrapper (someone may have wrapped it after us).
 */
function patch<T extends object, K extends keyof T>(
  target: T,
  key: K,
  wrap: (original: T[K]) => T[K],
): () => void {
  const original = target[key];
  if (typeof original !== 'function') return () => {};
  const wrapper = wrap(original);
  target[key] = wrapper;
  return () => {
    if (target[key] === wrapper) target[key] = original;
  };
}

function formatArgs(args: unknown[]): string {
  return args.map((a) => (a instanceof Error ? `${a.name}: ${a.message}` : stringify(a))).join(' ');
}

const TOO_BIG = new Error('too big');
/** Properties visited at most when serialising a logged value (keeps console.error cheap). */
const MAX_SERIALISED_PROPERTIES = 100;

function stringify(value: unknown): string {
  if (typeof value === 'string') return value;
  if (typeof value === 'bigint' || typeof value === 'symbol') return value.toString();
  if (typeof value === 'function') return '[function]';
  let visited = 0;
  try {
    const json = JSON.stringify(value, (_key, v: unknown) => {
      if (++visited > MAX_SERIALISED_PROPERTIES) throw TOO_BIG;
      return v;
    });
    return truncate(json ?? 'undefined', FIELD_LIMITS.errorMessage);
  } catch (e) {
    return e === TOO_BIG ? '[large object]' : '[unserialisable]';
  }
}
