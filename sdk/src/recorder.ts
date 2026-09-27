import { record } from '@rrweb/record';
import { BLOCK_SELECTOR, maskInput, rememberSensitiveFields } from './privacy';

/** rrweb event types used by the SDK (`@rrweb/types` EventType). */
export const RRWEB_FULL_SNAPSHOT = 2;
export const RRWEB_META = 4;

/** The part of an rrweb event the SDK relies on; the rest is passed through as-is. */
export interface RrwebEvent {
  type: number;
  timestamp: number;
}

export interface RecorderOptions {
  maskAllText: boolean;
  emit: (event: RrwebEvent) => void;
  /** rrweb observer errors; they are swallowed so they never reach the page. */
  onError: (error: unknown) => void;
}

export interface Recorder {
  stop(): void;
  /** Emits a new Meta + FullSnapshot (new session, or recovery after dropped data). */
  takeFullSnapshot(): void;
}

/** Starts rrweb with the SDK's privacy settings. Returns null if rrweb could not start. */
export function startRecorder(options: RecorderOptions): Recorder | null {
  rememberSensitiveFields(document);
  let stop: (() => void) | undefined;
  try {
    stop = startRrweb(options);
  } catch (e) {
    options.onError(e);
    return null;
  }
  if (!stop) return null;
  const stopRrweb = stop;
  return {
    stop: () => {
      try {
        stopRrweb();
      } catch (e) {
        options.onError(e);
      }
    },
    takeFullSnapshot: () => {
      try {
        record.takeFullSnapshot(true);
      } catch (e) {
        options.onError(e);
      }
    },
  };
}

function startRrweb(options: RecorderOptions): (() => void) | undefined {
  return record<RrwebEvent>({
    emit: options.emit,
    // FR-SDK-2: every input masked; unmasking is per element via maskInputFn
    maskAllInputs: true,
    maskInputFn: maskInput,
    blockSelector: BLOCK_SELECTOR,
    // '*' masks every text node (rrweb's default maskTextFn keeps whitespace only)
    ...(options.maskAllText ? { maskTextSelector: '*' } : {}),
    // v1: no canvas, no cross-origin iframes, no fonts or inlined images
    recordCanvas: false,
    recordCrossOriginIframes: false,
    collectFonts: false,
    inlineImages: false,
    slimDOMOptions: 'all',
    sampling: { scroll: 150, input: 'last' },
    errorHandler: (error: unknown) => {
      options.onError(error);
      return true; // handled: rrweb must not rethrow into the page
    },
  });
}
