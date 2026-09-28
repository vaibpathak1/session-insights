import { RRWEB_META, type RrwebEvent } from './recorder';

/** A chunk is sealed once its serialised events reach about this size. */
export const REPLAY_CHUNK_BYTES = 256 * 1024;

/** A sealed `ReplayBatch`, already serialised. */
export interface SealedChunk {
  sessionId: string;
  chunkSeq: number;
  /** `ReplayBatch` JSON: `{"sessionId","chunkSeq","events":[…]}`. */
  body: string;
  /** Approximate size (UTF-16 length of `body`). */
  bytes: number;
  /** Starts with Meta + FullSnapshot: may be large, and replay of what follows depends on it. */
  fullSnapshot?: boolean;
}

/**
 * Buffers rrweb events for one session at a time and cuts them into chunks (task 3.6).
 * Each event is serialised once, when it arrives, so sealing only joins strings. A Meta
 * event (start of a full snapshot) always starts a new chunk, so chunk 0 of a session
 * starts with its full snapshot.
 */
export class ReplayBuffer {
  private parts: string[] = [];
  private bytes = 0;
  private sessionId: string | null = null;
  private startsWithSnapshot = false;

  constructor(
    private readonly takeChunkSeq: () => number,
    private readonly onSealed: (chunk: SealedChunk) => void,
    private readonly maxChunkBytes = REPLAY_CHUNK_BYTES,
  ) {}

  get isEmpty(): boolean {
    return this.parts.length === 0;
  }

  /** The caller seals before the session changes, so a chunk never mixes sessions. */
  add(sessionId: string, event: RrwebEvent): void {
    if (event.type === RRWEB_META || (this.sessionId !== null && this.sessionId !== sessionId)) {
      this.seal();
    }
    const json = JSON.stringify(event);
    if (this.parts.length === 0) this.startsWithSnapshot = event.type === RRWEB_META;
    this.parts.push(json);
    this.bytes += json.length;
    this.sessionId = sessionId;
    if (this.bytes >= this.maxChunkBytes) this.seal();
  }

  seal(): void {
    if (this.sessionId === null || this.parts.length === 0) return;
    const chunkSeq = this.takeChunkSeq();
    const body = `{"sessionId":${JSON.stringify(this.sessionId)},"chunkSeq":${chunkSeq},"events":[${this.parts.join(',')}]}`;
    const chunk: SealedChunk = { sessionId: this.sessionId, chunkSeq, body, bytes: body.length };
    if (this.startsWithSnapshot) chunk.fullSnapshot = true;
    this.parts = [];
    this.bytes = 0;
    this.onSealed(chunk);
  }

  /** Drops buffered events without sealing (e.g. when recording stops for this page). */
  clear(): void {
    this.parts = [];
    this.bytes = 0;
  }
}
