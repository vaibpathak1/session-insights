import { isUuid, uuid } from './ids';
import type { KeyValueStore } from './storage';

/** A session ends after this much inactivity (FR-SES-1). */
export const SESSION_TIMEOUT_MS = 30 * 60 * 1000;
/** `lastActivity` is written to sessionStorage at most this often (plus on page hide). */
export const ACTIVITY_WRITE_INTERVAL_MS = 5000;

export const ANONYMOUS_ID_KEY = 'si_anon_id';
export const SESSION_KEY = 'si_session';

interface SessionState {
  id: string;
  lastActivity: number;
  /** Next replay `chunkSeq`; persisted so a reload in the same tab never reuses one. */
  nextChunkSeq: number;
  /** Sampling decision, made once per session. */
  sampled: boolean;
}

export interface SessionDeps {
  local: KeyValueStore;
  session: KeyValueStore;
  sampleRate: number;
  now?: () => number;
  random?: () => number;
}

/**
 * `anonymousId` lives in localStorage (shared by the site's tabs); the session lives in
 * sessionStorage (one per tab). Both fall back to memory when storage is blocked.
 */
export class SessionManager {
  private anonId: string;
  private state: SessionState;
  private lastWrite = Number.NEGATIVE_INFINITY;
  private readonly now: () => number;
  private readonly random: () => number;

  constructor(private readonly deps: SessionDeps) {
    this.now = deps.now ?? Date.now;
    this.random = deps.random ?? Math.random;
    const storedAnon = deps.local.get(ANONYMOUS_ID_KEY);
    if (isUuid(storedAnon)) {
      this.anonId = storedAnon;
    } else {
      this.anonId = uuid();
      deps.local.set(ANONYMOUS_ID_KEY, this.anonId);
    }
    const t = this.now();
    const stored = parse(deps.session.get(SESSION_KEY));
    if (stored && t - stored.lastActivity <= SESSION_TIMEOUT_MS) {
      this.state = { ...stored, lastActivity: t };
    } else {
      this.state = this.freshState(t);
    }
    this.persist();
  }

  get sessionId(): string {
    return this.state.id;
  }

  get anonymousId(): string {
    return this.anonId;
  }

  get sampled(): boolean {
    return this.state.sampled;
  }

  /** True when the session has been inactive for longer than the timeout. */
  isIdle(): boolean {
    return this.now() - this.state.lastActivity > SESSION_TIMEOUT_MS;
  }

  /**
   * Records user activity. If the session was idle past the timeout, a new session starts
   * first and this returns true. Storage writes are throttled.
   */
  touch(): boolean {
    const t = this.now();
    const rotated = t - this.state.lastActivity > SESSION_TIMEOUT_MS;
    if (rotated) {
      this.state = this.freshState(t);
    } else {
      this.state.lastActivity = t;
    }
    if (rotated || t - this.lastWrite >= ACTIVITY_WRITE_INTERVAL_MS) this.persist();
    return rotated;
  }

  /** Reserves the next replay chunk sequence number for the current session. */
  takeChunkSeq(): number {
    const seq = this.state.nextChunkSeq++;
    this.persist();
    return seq;
  }

  /** New anonymous id and new session. */
  reset(): void {
    this.anonId = uuid();
    this.deps.local.set(ANONYMOUS_ID_KEY, this.anonId);
    this.state = this.freshState(this.now());
    this.persist();
  }

  persist(): void {
    this.lastWrite = this.now();
    this.deps.session.set(SESSION_KEY, JSON.stringify(this.state));
  }

  private freshState(t: number): SessionState {
    return {
      id: uuid(),
      lastActivity: t,
      nextChunkSeq: 0,
      sampled: this.random() < this.deps.sampleRate,
    };
  }
}

function parse(raw: string | null): SessionState | null {
  if (!raw) return null;
  try {
    const v = JSON.parse(raw) as Partial<SessionState>;
    if (
      isUuid(v.id) &&
      typeof v.lastActivity === 'number' &&
      Number.isFinite(v.lastActivity) &&
      Number.isInteger(v.nextChunkSeq) &&
      (v.nextChunkSeq as number) >= 0 &&
      typeof v.sampled === 'boolean'
    ) {
      return {
        id: v.id,
        lastActivity: v.lastActivity,
        nextChunkSeq: v.nextChunkSeq as number,
        sampled: v.sampled,
      };
    }
  } catch {
    // corrupt entry: start a new session
  }
  return null;
}
