import type { Config } from './config';
import { listen } from './dom';
import { guarded } from './guard';
import type { Logger } from './log';
import { SessionManager } from './session';
import { browserStore } from './storage';

/** User input that counts as session activity (FR-SES-1). */
const ACTIVITY_EVENTS = ['pointerdown', 'keydown', 'input', 'scroll', 'touchstart', 'mousemove'];

/** One running SDK instance. All methods are called through `guard` by the public API. */
export class Client {
  private running = false;
  private session: SessionManager | null = null;
  private readonly disposers: Array<() => void> = [];

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
    this.log.info(
      `started; session ${this.session.sessionId} ${this.session.sampled ? 'sampled' : 'not sampled'}`,
    );
  }

  get isRunning(): boolean {
    return this.running;
  }

  getSessionId(): string | null {
    return this.running && this.session ? this.session.sessionId : null;
  }

  /** New anonymous id and new session. */
  reset(): void {
    if (!this.running || !this.session) return;
    this.session.reset();
    this.log.info(`reset; new session ${this.session.sessionId}`);
  }

  /** Flushes what is buffered and stops. The instance cannot be restarted. */
  shutdown(): void {
    if (!this.running) return;
    this.running = false;
    this.session?.persist();
    this.dispose();
    this.log.info('shut down');
  }

  private onActivity(): void {
    if (!this.running || !this.session) return;
    if (this.session.touch()) {
      this.log.info(`session expired after inactivity; new session ${this.session.sessionId}`);
    }
  }

  private onPageHide(): void {
    this.session?.persist();
  }

  private dispose(): void {
    for (const d of this.disposers.splice(0)) {
      try {
        d();
      } catch {
        // keep removing the rest
      }
    }
  }
}
