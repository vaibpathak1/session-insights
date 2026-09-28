import { installCapture, type CapturedEvent } from './capture';
import type { Config } from './config';
import { listen } from './dom';
import { guarded } from './guard';
import { uuid } from './ids';
import type { Logger } from './log';
import { MAX_SNAPSHOT_CHUNK_BYTES, Outbox, type QueuedEvent } from './outbox';
import { RRWEB_FULL_SNAPSHOT, startRecorder, type Recorder, type RrwebEvent } from './recorder';
import { ReplayBuffer, type SealedChunk } from './replay';
import { whenIdle } from './schedule';
import { Sender, type FatalReason } from './sender';
import { SessionManager } from './session';
import { browserStore } from './storage';
import { Transport } from './transport';

/** User input that counts as session activity (FR-SES-1). */
const ACTIVITY_EVENTS = ['pointerdown', 'keydown', 'input', 'scroll', 'touchstart', 'mousemove'];

/** One running SDK instance. All methods are called through `guard` by the public API. */
export class Client {
  private running = false;
  private session!: SessionManager;
  private replay!: ReplayBuffer;
  private recorder: Recorder | null = null;
  private uninstallCapture: (() => void) | null = null;
  private outbox!: Outbox;
  private sender!: Sender;
  /** Replay data was dropped from the full queue; take a full snapshot once it drains. */
  private needsSnapshot = false;
  /** A full snapshot exceeded the collector's limit: replay stays off for this page. */
  private replayOff = false;
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
    this.outbox = new Outbox(
      __SDK_VERSION__,
      () => {
        this.needsSnapshot = true;
        this.log.info('queue full: oldest replay data dropped');
      },
      undefined,
      (bytes) => this.stopReplay(bytes),
    );
    this.sender = new Sender(
      this.outbox,
      new Transport({
        collectorUrl: this.config.collectorUrl,
        siteKey: this.config.siteKey,
        log: this.log,
      }),
      {
        onFatal: (reason) => this.halt(reason),
        onDrained: () => this.onDrained(),
      },
      this.log,
    );
    this.replay = new ReplayBuffer(
      () => this.session.takeChunkSeq(),
      (chunk) => this.outbox.addChunk(chunk),
    );
    this.running = true;

    const onActivity = guarded(() => this.onActivity(), this.log);
    for (const type of ACTIVITY_EVENTS) {
      this.disposers.push(listen(window, type, onActivity, { capture: true, passive: true }));
    }
    const onPageHide = guarded(() => this.onPageHide(), this.log);
    this.disposers.push(listen(window, 'pagehide', onPageHide));
    this.disposers.push(
      listen(
        document,
        'visibilitychange',
        guarded(() => {
          if (document.visibilityState === 'hidden') onPageHide();
        }, this.log),
      ),
    );
    const flushWhenIdle = guarded(() => this.flush(), this.log);
    const timer = setInterval(() => whenIdle(flushWhenIdle), this.config.flushIntervalMs);
    this.disposers.push(() => clearInterval(timer));

    this.applySampling();
    this.log.info(
      `started; session ${this.session.sessionId} ${this.session.sampled ? 'sampled' : 'not sampled'}`,
    );
  }

  get isRunning(): boolean {
    return this.running;
  }

  /** Queued, unsent replay chunks (oldest first). */
  get pendingChunks(): readonly SealedChunk[] {
    return this.outbox.pendingChunks;
  }

  /** Queued, unsent events (oldest first). */
  get pendingEvents(): readonly QueuedEvent[] {
    return this.outbox.pendingEvents;
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

  /** Seals buffered replay data and sends everything queued. */
  flush(): void {
    if (!this.running) return;
    this.replay.seal();
    this.sender.flush();
  }

  /** Stops recording and sends what is buffered once, without retries. */
  shutdown(): void {
    if (!this.running) return;
    this.replay.seal();
    this.stop();
    this.sender.finish();
    this.log.info('shut down');
  }

  /** The collector refused the key or origin (or was never reachable): stop for this page. */
  private halt(reason: FatalReason): void {
    if (!this.running) return;
    this.stop();
    this.replay.clear();
    this.outbox.clear();
    this.log.once('halted', `${haltMessage(reason)}; stopped for this page`);
  }

  private stop(): void {
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
    if (!this.recorder && !this.replayOff) {
      const recorder = startRecorder({
        maskAllText: this.config.maskAllText,
        emit: guarded((e: RrwebEvent) => this.onReplayEvent(e), this.log),
        onError: (e) => this.log.warn('recorder error (ignored)', e),
      });
      // the initial snapshot is emitted during startRecorder: it may already have been too large
      if (this.replayOff) recorder?.stop();
      else this.recorder = recorder;
    }
  }

  /**
   * The page's DOM is too large to replay (full snapshot over the collector's limit). Taking
   * another snapshot would fail the same way, so replay stops for this page; events go on.
   */
  private stopReplay(bytes: number): void {
    this.replayOff = true;
    this.needsSnapshot = false;
    this.recorder?.stop();
    this.recorder = null;
    this.replay.clear();
    this.log.once(
      'snapshot-too-large',
      `full snapshot too large (${(bytes / 1024 / 1024).toFixed(1)} MB > ${MAX_SNAPSHOT_CHUNK_BYTES / 1024 / 1024} MB); replay off for this page`,
    );
  }

  private stopCapturing(): void {
    this.uninstallCapture?.();
    this.uninstallCapture = null;
    this.recorder?.stop();
    this.recorder = null;
  }

  private onEvent(captured: CapturedEvent): void {
    if (!this.running || !this.session.sampled || this.session.isIdle()) return;
    this.outbox.addEvent(this.session.sessionId, this.session.anonymousId, {
      clientEventId: uuid(),
      ts: Date.now(),
      ...captured,
    });
  }

  private onReplayEvent(event: RrwebEvent): void {
    // While idle, events are dropped: the next activity starts a new session with a
    // full snapshot, so nothing is lost for replay.
    if (!this.running || this.replayOff || !this.session.sampled || this.session.isIdle()) return;
    this.replay.add(this.session.sessionId, event);
    if (event.type === RRWEB_FULL_SNAPSHOT) {
      this.replay.seal(); // ship snapshots (chunk 0) promptly, not at the next interval
      this.sender.flush();
    }
  }

  /** Page is being hidden or unloaded: beacon what fits in the budget. */
  private onPageHide(): void {
    if (!this.running) return;
    this.replay.seal();
    this.sender.pageHide();
    this.session.persist();
  }

  private onDrained(): void {
    if (this.needsSnapshot && this.recorder) {
      this.needsSnapshot = false;
      this.recorder.takeFullSnapshot(); // replay can resume after dropped chunks
    }
  }
}

/**
 * Debug message for a halt. The collector makes 401/403 readable to browsers (ADR-0012),
 * so a bad key or origin is named on the first request; "unreachable" means five network
 * errors before any success (collector down, blocked by CSP or an ad blocker).
 */
export function haltMessage(reason: FatalReason): string {
  if (!('status' in reason)) return 'collector unreachable (network errors before any success)';
  if (reason.status === 401) return 'invalid site key (401)';
  if (reason.status === 403) return 'origin not allowed for this site (403)';
  return `collector refused this page (${reason.status})`;
}
