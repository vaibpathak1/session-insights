import { installCapture, type CapturedEvent } from './capture';
import type { Config } from './config';
import { listen } from './dom';
import { guarded } from './guard';
import { uuid } from './ids';
import type { Logger } from './log';
import { RRWEB_FULL_SNAPSHOT, startRecorder, type Recorder, type RrwebEvent } from './recorder';
import { ReplayBuffer, type SealedChunk } from './replay';
import { SessionManager } from './session';
import { browserStore } from './storage';
import type { TelemetryEvent } from './wire';

/** User input that counts as session activity (FR-SES-1). */
const ACTIVITY_EVENTS = ['pointerdown', 'keydown', 'input', 'scroll', 'touchstart', 'mousemove'];

/** A derived event with the identity it was captured under. */
export interface StampedEvent {
  sessionId: string;
  anonymousId: string;
  event: TelemetryEvent;
}

/** One running SDK instance. All methods are called through `guard` by the public API. */
export class Client {
  private running = false;
  private session!: SessionManager;
  private replay!: ReplayBuffer;
  private recorder: Recorder | null = null;
  private uninstallCapture: (() => void) | null = null;
  private readonly disposers: Array<() => void> = [];

  /** Ready to send; drained by the transport (task 3.7). */
  readonly pendingEvents: StampedEvent[] = [];
  readonly pendingChunks: SealedChunk[] = [];

  constructor(
    readonly config: Config,
    readonly log: Logger,
  ) {}

  start(): void {
    this.session = new SessionManager({
      local: browserStore('localStorage'),
      session: browserStore('sessionStorage'),
      sampleRate: this.config.sampleRate,
    });
    this.replay = new ReplayBuffer(
      () => this.session.takeChunkSeq(),
      (chunk) => this.pendingChunks.push(chunk),
    );
    this.running = true;

    const onActivity = guarded(() => this.onActivity(), this.log);
    for (const type of ACTIVITY_EVENTS) {
      this.disposers.push(listen(window, type, onActivity, { capture: true, passive: true }));
    }
    this.disposers.push(
      listen(
        window,
        'pagehide',
        guarded(() => this.onPageHide(), this.log),
      ),
    );
    const timer = setInterval(
      guarded(() => this.flush(), this.log),
      this.config.flushIntervalMs,
    );
    this.disposers.push(() => clearInterval(timer));

    this.applySampling();
    this.log.info(
      `started; session ${this.session.sessionId} ${this.session.sampled ? 'sampled' : 'not sampled'}`,
    );
  }

  get isRunning(): boolean {
    return this.running;
  }

  getSessionId(): string | null {
    return this.running ? this.session.sessionId : null;
  }

  /** New anonymous id and new session. */
  reset(): void {
    if (!this.running) return;
    this.replay.seal();
    this.session.reset();
    this.onNewSession();
    this.log.info(`reset; new session ${this.session.sessionId}`);
  }

  /** Seals buffered replay data. Sending is task 3.7. */
  flush(): void {
    if (!this.running) return;
    this.replay.seal();
  }

  /** Flushes what is buffered and stops. The instance cannot be restarted. */
  shutdown(): void {
    if (!this.running) return;
    this.flush();
    this.stopCapturing();
    this.running = false;
    this.session.persist();
    for (const d of this.disposers.splice(0)) {
      try {
        d();
      } catch {
        // keep removing the rest
      }
    }
    this.log.info('shut down');
  }

  private onActivity(): void {
    if (!this.running) return;
    if (this.session.isIdle()) this.replay.seal(); // seal before the session changes
    if (this.session.touch()) {
      this.onNewSession();
      this.log.info(`session expired after inactivity; new session ${this.session.sessionId}`);
    }
  }

  private onNewSession(): void {
    const wasRecording = this.recorder !== null;
    this.applySampling();
    // a running recorder starts the new session with a full snapshot (chunk 0)
    if (wasRecording && this.recorder) this.recorder.takeFullSnapshot();
  }

  /** Records only sampled sessions; unsampled sessions install nothing and send nothing. */
  private applySampling(): void {
    if (this.session.sampled) this.startCapturing();
    else this.stopCapturing();
  }

  private startCapturing(): void {
    if (!this.uninstallCapture) {
      this.uninstallCapture = installCapture({
        maskAllText: this.config.maskAllText,
        emit: (e) => this.onEvent(e),
        log: this.log,
      });
    }
    if (!this.recorder) {
      this.recorder = startRecorder({
        maskAllText: this.config.maskAllText,
        emit: guarded((e: RrwebEvent) => this.onReplayEvent(e), this.log),
        onError: (e) => this.log.warn('recorder error (ignored)', e),
      });
    }
  }

  private stopCapturing(): void {
    this.uninstallCapture?.();
    this.uninstallCapture = null;
    this.recorder?.stop();
    this.recorder = null;
  }

  private onEvent(captured: CapturedEvent): void {
    if (!this.running || !this.session.sampled || this.session.isIdle()) return;
    this.pendingEvents.push({
      sessionId: this.session.sessionId,
      anonymousId: this.session.anonymousId,
      event: { clientEventId: uuid(), ts: Date.now(), ...captured },
    });
  }

  private onReplayEvent(event: RrwebEvent): void {
    // While idle, events are dropped: the next activity starts a new session with a
    // full snapshot, so nothing is lost for replay.
    if (!this.running || !this.session.sampled || this.session.isIdle()) return;
    this.replay.add(this.session.sessionId, event);
    if (event.type === RRWEB_FULL_SNAPSHOT) this.replay.seal(); // ship snapshots promptly
  }

  private onPageHide(): void {
    this.session.persist();
  }
}
