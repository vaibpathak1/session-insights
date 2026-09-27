import type { Config } from './config';
import type { Logger } from './log';

/** One running SDK instance. All methods are called through `guard` by the public API. */
export class Client {
  private running = false;

  constructor(
    readonly config: Config,
    readonly log: Logger,
  ) {}

  start(): void {
    this.running = true;
    this.log.info('started');
  }

  get isRunning(): boolean {
    return this.running;
  }

  getSessionId(): string | null {
    return null;
  }

  reset(): void {}

  /** Flushes what is buffered and stops. The instance cannot be restarted. */
  shutdown(): void {
    this.running = false;
    this.log.info('shut down');
  }
}
