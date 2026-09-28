# ADR-0011: Failure handling in consumers

**Status:** Accepted · 2026-09 · Supersedes the consumer retry/DLT rule of ADR-0004
("`DefaultErrorHandler` with bounded retries → DLT"). Everything else in ADR-0004 stands.

## Context
ADR-0004 gave every consumer bounded retries followed by the dead-letter topic. That treats
two very different failures the same way:

- a **poison record**: a value that can never be written, however often it is retried;
- a **store outage**: ClickHouse or object storage down, unreachable, timing out or overloaded.
  Every record in flight fails, and all of them are fine.

With bounded retries, a ClickHouse restart longer than the retry budget moves good telemetry
into the DLT. The system then needs manual replay, and records are lost if nobody notices
within the DLT retention. Consumers are idempotent (ADR-0004), so holding back and retrying
is always safe.

A store error can also look like an outage while really being about one record. ClickHouse
rejects a whole insert when a single row cannot be parsed (for example duplicate paths in a
`JSON` column), so one bad record would block its partition forever.

## Decision
**A poison record goes to the DLT. A store outage never does.**

1. **Poison → DLT, immediately, without retries.** A record is poison when:
   - it cannot be deserialised, or fails envelope validation (missing tenant/site/session,
     unsupported schema version, unknown event type, invalid replay chunk, the unsupported
     base64 replay `payload`); or
   - **`store_rejected`**: it passed validation, but the store rejected the data itself.
   The listener writes every good record of the batch, then reports the first poison record
   with `BatchListenerFailedException`. The error handler commits the offsets before it,
   dead-letters it and redelivers the rest. Rewriting the rest is harmless (deduplicated).
2. **Telling data errors from outages.** When an insert fails, the processor pings ClickHouse.
   The failure counts as a data error only if **the ping succeeds and** the server error code
   is in the parse/type/value class (for example 6, 27, 53, 70, 72, 117, 321). In that case the
   batch is **bisected** until the offending records are isolated; they are dead-lettered as
   `store_rejected` and everything else is written. Each sub-insert has its own
   deduplication token, so an outage in the middle of a bisection repeats safely. Every
   other failure is an outage: connection, timeout, overload, a failed ping, missing tables,
   authentication, unknown codes, any S3 error, and unexpected exceptions.
3. **Outage → unbounded back-off, consumer paused, no DLT, no commit.** The error handler
   pauses the consumer and keeps polling, so the consumer stays in its group. It re-invokes
   the listener with the same batch under exponential back-off (0.5 s × 2, capped at 30 s)
   with **no attempt or time limit**. Offsets are committed only after the batch is written.
   If the container stops meanwhile, the partitions are rewound to the start of the batch.
   A rebalance during the outage keeps the new assignment paused. If the store comes back
   and the batch turns out to contain a poison record, rule 1 applies.
4. **The outage tracker.** One WARN when a store outage starts and one INFO when it ends
   (with duration and attempt count), not one line per record or retry. Every failed attempt
   is counted in `si.processor.store.retries{store}`, and
   `si.processor.store.unavailable{store}` is 1 while an outage lasts. That gauge is the
   alert signal, together with consumer lag.
5. **Dead-letter records** keep the original key, value bytes and headers. They add
   `kafka_dlt-original-topic/partition/offset/timestamp` and `si-dlt-reason` (a
   low-cardinality code, also the `reason` tag of `si.processor.dlt`). Exception message and
   stack-trace headers are **not** written: ClickHouse error messages quote the rejected
   row, and nothing from a payload may leave through headers or logs.

6. **Threads: platform threads for third-party libraries that block inside `synchronized`.**
   On Java 21, a virtual thread that blocks inside `synchronized` pins its carrier.
   kafka-clients' classic consumer does exactly that: the group coordinator and metadata
   code sleeps and waits in `synchronized` (`AbstractCoordinator.ensureCoordinatorReady`,
   `requestRejoin`, join/sync handlers, `Metadata.update`). With 12 consumers on 8 carriers
   the processor deadlocked at startup. So **the Kafka consumer loop threads are platform
   threads**: one per partition consumer, a small fixed number. Virtual threads are for our
   own blocking work, such as the parallel object PUTs inside a batch, and for HTTP requests.
   `VirtualThreadPinningTest` records JFR `jdk.VirtualThreadPinned` events (no threshold)
   across consumer startup, ClickHouse inserts and parallel S3 PUTs, and requires none. With
   virtual consumer threads it fails on the kafka-clients frames above.

## Consequences
- A long outage turns into consumer lag, not data loss or manual DLT replay. Kafka retention
  (3 days) bounds how long an outage can last before data is lost, so alert on the gauge and
  on lag well before that.
- A partition never stalls on one bad record, including one only the store can recognise.
  The cost is about 2·log2(n) extra inserts per bad record, only when one is present.
- A misconfiguration (wrong credentials, missing bucket or table) stalls consumption instead
  of dead-lettering everything. That is intentional: fixing the configuration resumes with
  nothing lost.
- A code bug that throws for every batch also stalls instead of dead-lettering. It shows up
  as one ERROR line, the gauge (`store=unknown`) and lag.
- ADR-0001's "virtual threads for all blocking I/O" gets one scoped exception: threads
  owned by a library that blocks inside `synchronized` on Java 21. Revisit on a JDK with
  JEP 491 (24+), or with the KIP-848 consumer (`group.protocol=consumer`), which does its
  network I/O on its own background thread.
- Insert deduplication by token relies on `non_replicated_deduplication_window` (ClickHouse
  V2 migration); across redeliveries, ReplacingMergeTree deduplicates (read with `FINAL`).

## Alternatives considered
- **Bounded retries → DLT (ADR-0004 as written).** Rejected: an outage longer than the budget
  dead-letters good data.
- **Spring Kafka's built-in batch retry (`FallbackBatchErrorHandler`) with an infinite
  back-off.** Rejected: if the store comes back and the retried batch then throws
  `BatchListenerFailedException`, it hands the *whole batch* to the recoverer.
- **Dead-letter the whole batch on a store data error.** Rejected: one bad row would move up
  to 2,000 good events to the DLT.
- **Treat every server error as poison.** Rejected: overload and "table missing" errors would
  dead-letter good data.
