import type { SealedChunk } from './replay';
import { MAX_EVENTS_PER_BATCH, type EventBatch, type TelemetryEvent } from './wire';

/** In-memory bound for everything waiting to be sent. */
export const MAX_QUEUE_BYTES = 2 * 1024 * 1024;
/** Upper bound for one events request (collector accepts 1 MB decompressed). */
const MAX_EVENT_BATCH_BYTES = 512 * 1024;

export interface QueuedEvent {
  sessionId: string;
  anonymousId: string;
  event: TelemetryEvent;
  /** Serialised event, computed once. */
  json: string;
}

/** One request's worth of queued items. */
export type Outgoing =
  | { path: 'events'; body: string; events: QueuedEvent[] }
  | { path: 'replay'; body: string; chunk: SealedChunk };

export interface DropCounts {
  events: number;
  replayChunks: number;
}

/**
 * Bounded queue (task 3.7). Items stay queued until the collector accepts them, so retries
 * resend the same items. When the bound is exceeded the oldest replay chunks are dropped
 * first, then the oldest events; `onReplayDropped` lets the client take a new full snapshot
 * so replay can recover.
 */
export class Outbox {
  private events: QueuedEvent[] = [];
  private chunks: SealedChunk[] = [];
  private bytes = 0;
  readonly dropped: DropCounts = { events: 0, replayChunks: 0 };

  constructor(
    private readonly sdkVersion: string,
    private readonly onReplayDropped: () => void = () => {},
    private readonly maxBytes = MAX_QUEUE_BYTES,
  ) {}

  get size(): { events: number; chunks: number; bytes: number } {
    return { events: this.events.length, chunks: this.chunks.length, bytes: this.bytes };
  }

  get isEmpty(): boolean {
    return this.events.length === 0 && this.chunks.length === 0;
  }

  /** Queued replay chunks, oldest first (read-only view). */
  get pendingChunks(): readonly SealedChunk[] {
    return this.chunks;
  }

  /** Queued events, oldest first (read-only view). */
  get pendingEvents(): readonly QueuedEvent[] {
    return this.events;
  }

  addEvent(sessionId: string, anonymousId: string, event: TelemetryEvent): void {
    const json = JSON.stringify(event);
    this.events.push({ sessionId, anonymousId, event, json });
    this.bytes += json.length;
    this.enforceBound();
  }

  addChunk(chunk: SealedChunk): void {
    this.chunks.push(chunk);
    this.bytes += chunk.bytes;
    this.enforceBound();
  }

  /**
   * The next request to send: events first (small, most valuable), as a batch of
   * consecutive events of one session; then the oldest replay chunk.
   */
  next(): Outgoing | null {
    const first = this.events[0];
    if (first) {
      const batch: QueuedEvent[] = [];
      let bytes = 0;
      for (const item of this.events) {
        if (item.sessionId !== first.sessionId || item.anonymousId !== first.anonymousId) break;
        if (batch.length >= MAX_EVENTS_PER_BATCH) break;
        if (batch.length > 0 && bytes + item.json.length > MAX_EVENT_BATCH_BYTES) break;
        batch.push(item);
        bytes += item.json.length + 1;
      }
      return { path: 'events', body: this.eventBody(batch), events: batch };
    }
    const chunk = this.chunks[0];
    return chunk ? { path: 'replay', body: chunk.body, chunk } : null;
  }

  /** Removes sent (or deliberately dropped) items; items already evicted are ignored. */
  remove(sent: Outgoing): void {
    if (sent.path === 'events') {
      const done = new Set(sent.events);
      this.events = this.events.filter((e) => {
        if (!done.has(e)) return true;
        this.bytes -= e.json.length;
        return false;
      });
    } else if (this.chunks.includes(sent.chunk)) {
      this.chunks = this.chunks.filter((c) => c !== sent.chunk);
      this.bytes -= sent.chunk.bytes;
    }
  }

  /** True while every item of `request` is still queued (nothing sent or evicted meanwhile). */
  contains(request: Outgoing): boolean {
    if (request.path === 'replay') return this.chunks.includes(request.chunk);
    const queued = new Set(this.events);
    return request.events.every((e) => queued.has(e));
  }

  /**
   * Requests for a page-hide beacon within `budgetBytes` (UTF-8) in total. Most recent
   * events first, split per session and by budget; then replay chunks oldest first, stopping
   * at the first that does not fit (the replay tail is dropped rather than leaving a gap).
   * Items of `exclude` (a request already in flight) are skipped. Nothing is removed here;
   * the caller removes what it actually sent.
   */
  forBeacon(
    budgetBytes: number,
    byteLength: (s: string) => number,
    exclude: Outgoing | null = null,
  ): Outgoing[] {
    const out: Outgoing[] = [];
    let remaining = budgetBytes;
    const skipEvents = new Set(exclude?.path === 'events' ? exclude.events : []);
    const skipChunk = exclude?.path === 'replay' ? exclude.chunk : null;
    const newestFirst = [...this.events].reverse().filter((e) => !skipEvents.has(e));
    while (newestFirst.length > 0) {
      const head = newestFirst[0]!;
      const batch: QueuedEvent[] = [];
      let body = this.eventBody(batch, head);
      for (const item of newestFirst) {
        if (item.sessionId !== head.sessionId || item.anonymousId !== head.anonymousId) continue;
        if (batch.length >= MAX_EVENTS_PER_BATCH) break;
        const candidate = this.eventBody([...batch, item], head);
        if (byteLength(candidate) > remaining) break;
        batch.push(item);
        body = candidate;
      }
      if (batch.length === 0) break; // budget exhausted
      out.push({ path: 'events', body, events: batch });
      remaining -= byteLength(body);
      const taken = new Set(batch);
      for (let i = newestFirst.length - 1; i >= 0; i--) {
        if (taken.has(newestFirst[i]!)) newestFirst.splice(i, 1);
      }
      // events of this session that did not fit: stop instead of skipping to older sessions
      if (newestFirst.some((e) => e.sessionId === head.sessionId)) break;
    }
    for (const chunk of this.chunks) {
      if (chunk === skipChunk) continue;
      const size = byteLength(chunk.body);
      if (size > remaining) break;
      out.push({ path: 'replay', body: chunk.body, chunk });
      remaining -= size;
    }
    return out;
  }

  clear(): void {
    this.events = [];
    this.chunks = [];
    this.bytes = 0;
  }

  private eventBody(batch: QueuedEvent[], head: QueuedEvent | undefined = batch[0]): string {
    const envelope: Omit<EventBatch, 'events'> = {
      sessionId: head?.sessionId ?? '',
      anonymousId: head?.anonymousId ?? '',
      sdkVersion: this.sdkVersion,
    };
    const json = JSON.stringify(envelope);
    return `${json.slice(0, -1)},"events":[${batch.map((e) => e.json).join(',')}]}`;
  }

  private enforceBound(): void {
    let droppedReplay = false;
    while (this.bytes > this.maxBytes) {
      const chunk = this.chunks.shift();
      if (chunk) {
        this.bytes -= chunk.bytes;
        this.dropped.replayChunks++;
        droppedReplay = true;
        continue;
      }
      const event = this.events.shift();
      if (!event) break;
      this.bytes -= event.json.length;
      this.dropped.events++;
    }
    if (droppedReplay) this.onReplayDropped();
  }
}
