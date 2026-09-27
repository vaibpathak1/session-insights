import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { installCapture, type CapturedEvent } from '../src/capture';
import { guard } from '../src/guard';
import { createLogger } from '../src/log';

let events: CapturedEvent[];
let uninstall: (() => void) | null;
let emit: (e: CapturedEvent) => void;

function install(maskAllText = false) {
  uninstall = installCapture({
    maskAllText,
    emit: (e) => emit(e),
    log: createLogger(false),
  });
}

function click(selector: string) {
  document.querySelector(selector)!.dispatchEvent(new MouseEvent('click', { bubbles: true }));
}

const ofType = (type: CapturedEvent['type']) => events.filter((e) => e.type === type);

beforeEach(() => {
  events = [];
  emit = (e) => events.push(e);
  history.replaceState(null, '', '/start');
  document.title = 'Start';
});

afterEach(() => {
  uninstall?.();
  uninstall = null;
  document.body.innerHTML = '';
});

describe('CLICK', () => {
  it('sends a selector and the visible text', () => {
    document.body.innerHTML =
      '<main><button id="buy" class="btn primary">Buy <b>now</b></button></main>';
    install();
    click('#buy b');
    expect(ofType('CLICK')).toEqual([
      expect.objectContaining({
        url: 'http://localhost:3000/start',
        path: '/start',
        targetSelector: 'button#buy > b',
        targetText: 'now',
      }),
    ]);
  });

  it('builds a positional selector without ids', () => {
    document.body.innerHTML = '<ul class="menu"><li>a</li><li class="x y z">b</li></ul>';
    install();
    click('li.x');
    expect(ofType('CLICK')[0]!.targetSelector).toBe('body > ul.menu > li.x.y:nth-of-type(2)');
  });

  it('never reads text from blocked elements, blocked descendants or fields', () => {
    document.body.innerHTML = `
      <div id="card">Balance <span data-si-block>9,999.00</span> shown</div>
      <div data-si-block><button id="inside">Secret label</button></div>
      <input id="field" value="typed value">
      <textarea id="ta">textarea content</textarea>`;
    install();
    click('#card');
    click('#inside');
    click('#field');
    click('#ta');
    const clicks = ofType('CLICK');
    expect(clicks[0]!.targetText).toBe('Balance shown');
    expect(clicks.slice(1).every((c) => c.targetText === undefined)).toBe(true);
    expect(JSON.stringify(events)).not.toMatch(/9,999|Secret label|typed value|textarea content/);
  });

  it('masks click text when maskAllText is on', () => {
    document.body.innerHTML = '<button id="b">Pay Alice</button>';
    install(true);
    click('#b');
    expect(ofType('CLICK')[0]!.targetText).toBe('*** *****');
  });
});

describe('NAVIGATION', () => {
  it('records the initial load, History API changes, popstate and hashchange once each', () => {
    install();
    history.pushState({ a: 1 }, '', '/products');
    history.replaceState({ a: 2 }, '', '/products'); // state only: no navigation
    history.replaceState(null, '', '/products?page=2');
    location.hash = '#reviews'; // popstate, then hashchange (same URL: recorded once)
    window.dispatchEvent(new PopStateEvent('popstate')); // same URL again: ignored
    return new Promise<void>((resolve) => setTimeout(resolve, 0)).then(() => {
      expect(ofType('NAVIGATION').map((e) => [e.props?.trigger, e.path])).toEqual([
        ['load', '/start'],
        ['pushState', '/products'],
        ['replaceState', '/products'],
        ['popstate', '/products'],
      ]);
    });
  });

  it('records a hashchange that arrives without popstate', () => {
    install();
    History.prototype.replaceState.call(history, null, '', '/start#section'); // bypasses our patch
    window.dispatchEvent(new HashChangeEvent('hashchange'));
    expect(ofType('NAVIGATION').slice(-1)[0]?.props).toEqual({ trigger: 'hashchange' });
  });

  it('keeps History API behaviour: return value, state and host errors', () => {
    install();
    expect(history.pushState({ n: 1 }, '')).toBeUndefined();
    expect(history.state).toEqual({ n: 1 });
    // a cross-origin URL throws SecurityError for the host, as without the SDK
    expect(() => history.pushState(null, '', 'https://elsewhere.test/')).toThrow();
  });

  it('restores patched methods on uninstall, unless someone wrapped them after us', () => {
    const originalPush = history.pushState;
    const originalReplace = history.replaceState;
    install();
    expect(history.pushState).not.toBe(originalPush);
    const theirs = function (this: History, ...args: Parameters<History['replaceState']>) {
      return originalReplace.apply(this, args);
    };
    history.replaceState = theirs;
    uninstall!();
    uninstall = null;
    expect(history.pushState).toBe(originalPush);
    expect(history.replaceState).toBe(theirs);
    history.replaceState = originalReplace;
  });
});

describe('CONSOLE_ERROR', () => {
  it('always calls the original console.error with the same arguments', () => {
    const original = vi.fn();
    const saved = console.error;
    console.error = original;
    try {
      install();
      const err = new TypeError('bad thing');
      console.error('Checkout failed:', err, { code: 42 });
      expect(original).toHaveBeenCalledWith('Checkout failed:', err, { code: 42 });
      expect(ofType('CONSOLE_ERROR')).toEqual([
        expect.objectContaining({
          errorMessage: 'Checkout failed: TypeError: bad thing {"code":42}',
          errorStack: err.stack,
        }),
      ]);
      uninstall!();
      uninstall = null;
      expect(console.error).toBe(original);
    } finally {
      console.error = saved;
    }
  });

  it('keeps logging large or circular objects cheap', () => {
    const saved = console.error;
    console.error = () => {};
    try {
      install();
      const circular: Record<string, unknown> = {};
      circular.self = circular;
      console.error(circular);
      console.error(Object.fromEntries(Array.from({ length: 1000 }, (_, i) => [`k${i}`, i])));
      expect(ofType('CONSOLE_ERROR').map((e) => e.errorMessage)).toEqual([
        '[unserialisable]',
        '[large object]',
      ]);
    } finally {
      console.error = saved;
    }
  });

  it('caps error events so an error loop cannot flood', () => {
    const saved = console.error;
    console.error = () => {};
    try {
      install();
      for (let i = 0; i < 100; i++) console.error('loop', i);
      expect(ofType('CONSOLE_ERROR')).toHaveLength(30);
    } finally {
      console.error = saved;
    }
  });
});

describe('EXCEPTION', () => {
  it('captures error events and keeps the page’s own handlers', () => {
    const onerror = vi.fn();
    window.onerror = onerror;
    try {
      install();
      const err = new RangeError('out of range');
      window.dispatchEvent(
        new ErrorEvent('error', { message: 'Uncaught RangeError: out of range', error: err }),
      );
      expect(onerror).toHaveBeenCalled();
      expect(ofType('EXCEPTION')).toEqual([
        expect.objectContaining({
          errorMessage: 'Uncaught RangeError: out of range',
          errorStack: err.stack,
        }),
      ]);
    } finally {
      window.onerror = null;
    }
  });

  it('captures unhandled rejections', () => {
    install();
    const rejection = Object.assign(new Event('unhandledrejection'), { reason: new Error('nope') });
    window.dispatchEvent(rejection);
    const plain = Object.assign(new Event('unhandledrejection'), { reason: { status: 500 } });
    window.dispatchEvent(plain);
    expect(ofType('EXCEPTION').map((e) => e.errorMessage)).toEqual([
      'Unhandled rejection: Error: nope',
      'Unhandled rejection: {"status":500}',
    ]);
  });
});

describe('SDK internal errors are not captured', () => {
  it('ignores console.error and errors raised inside SDK code', () => {
    const saved = console.error;
    console.error = () => {};
    try {
      install();
      guard(() => {
        console.error('from inside the SDK');
        window.dispatchEvent(
          new ErrorEvent('error', { message: 'inside', error: new Error('inside') }),
        );
      }, undefined);
      expect(events.filter((e) => e.type !== 'NAVIGATION')).toEqual([]);
    } finally {
      console.error = saved;
    }
  });

  it('a failing SDK listener neither throws into the page nor is recorded as an exception', () => {
    document.body.innerHTML = '<button id="b">x</button>';
    install();
    const pageErrors: ErrorEvent[] = [];
    const onError = (e: ErrorEvent) => pageErrors.push(e);
    window.addEventListener('error', onError);
    emit = () => {
      throw new Error('SDK bug');
    };
    try {
      expect(() => click('#b')).not.toThrow();
      expect(() => history.pushState(null, '', '/next')).not.toThrow();
      expect(pageErrors).toEqual([]);
    } finally {
      window.removeEventListener('error', onError);
    }
  });
});
