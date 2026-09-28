# Phase 4 — Event processor: Kafka → ClickHouse and object storage

Branch: `phase-4-processor`. Milestone: M1 (first replay).
Implements FR-PRC-1, FR-PRC-2. Read ADR-0002, ADR-0003, ADR-0004, ADR-0008, ADR-0010,
the envelopes in `platform-common`, the ClickHouse V1 schema and the migrator in `platform-db`.

## Context
The collector writes `TelemetryEnvelope`s to `telemetry.events.v1` and `ReplayEnvelope`s to
`replay.chunks.v1`, keyed by session id, delivered at least once (the SDK can also send a
duplicate at page close). `event-processor` persists them: events to ClickHouse, replay
chunks to S3-compatible storage. No JPA, no PostgreSQL writes in this phase (session
lifecycle is Phase 5).

The central rule of this phase: **a poison record goes to the DLT; an infrastructure
outage never does.** If ClickHouse or S3 is down, the consumer must stop, retry and resume
without losing or dead-lettering good data.

## Tasks

4.1 **ClickHouse V2 migration** — `replay_chunks` manifest (so the player never needs S3 LIST):
    `ReplacingMergeTree(ingested_at)`, `ORDER BY (tenant_id, session_id, chunk_seq)`,
    columns `tenant_id`, `site_id`, `session_id`, `chunk_seq UInt32`, `object_key`,
    `compressed_bytes UInt32`, `event_count UInt32`, `first_ts`, `last_ts`,
    `has_full_snapshot UInt8`, `ingested_at`; same retention TTL as `events`.
    Don't modify V1 (it's merged).

4.2 **ClickHouse writer** (`platform-db` or processor, propose): ClickHouse Java **client-v2**
    (Apache 2.0; verify current version on Maven Central and pin). Insert whole Kafka batches
    as one insert (JSONEachRow or RowBinary — pick and justify). Set an
    `insert_deduplication_token` derived from topic-partition + first/last offset, so a
    retried identical batch is deduplicated at insert time (ReplacingMergeTree covers
    duplicates across batches). Every write and every read helper takes `tenantId` explicitly.

4.3 **Events consumer** (`telemetry.events.v1`): batch listener, manual ack after a
    successful insert (`AckMode.BATCH`), virtual-thread listener executor, concurrency =
    partition count. Tune for big inserts: `max.poll.records` ~2000, `fetch.min.bytes`,
    `fetch.max.wait.ms` ~500. Map envelope → row exactly to the V1 columns.

4.4 **Replay consumer** (`replay.chunks.v1`): for each chunk, compress with zstd (zstd-jni,
    BSD — verify and pin) and PUT to
    `tenants/{tenantId}/sessions/{sessionId}/{chunkSeq, 6-digit zero-padded}.json.zst`
    via AWS SDK v2 (path-style access for SeaweedFS). Redelivery overwrites the same key —
    idempotent by design. Then insert the manifest row. Object first, manifest second, so a
    manifest row never points to a missing object.

4.5 **Error handling** (the heart of the phase):
    - `ErrorHandlingDeserializer`: undeserialisable records → `.dlt` with a reason header.
    - Envelope validation failures (missing tenant/session, bad type) → `.dlt` with reason.
      Use `BatchListenerFailedException` so only the bad record is dead-lettered and the
      rest of the batch is written.
    - ClickHouse / S3 unavailable or timing out → **no DLT**: back off (exponential, capped,
      effectively unbounded retries), offsets not committed, consumption resumes when the
      store is back. Log a clear warning once per outage, not per record.
    - DLT records keep the original key, headers, topic/partition/offset.

4.6 **Observability**: Micrometer — rows inserted, insert latency, batch size, chunks stored,
    bytes stored, DLT count by reason, consumer lag (Kafka client metrics). Never log
    payloads.

4.7 **Config**: ClickHouse (app user), S3 endpoint/bucket/credentials from `.env.example`
    values, Kafka consumer settings; processor runs from the IDE on port 8082.

4.8 **Pipeline e2e**: extend `scripts/e2e-sdk.sh` (or add `scripts/e2e-pipeline.sh`) to also
    require event-processor running, then extend the Playwright privacy test to assert, for
    the test session: events are queryable in ClickHouse (with `FINAL`), replay objects
    exist in S3 and the manifest lists them in order starting at chunk 0 with a full
    snapshot, and **none of the sensitive values appear in ClickHouse rows or in the
    decompressed S3 objects**.

4.9 **Throughput smoke (manual, report honestly)**: a small generator (test scope or script)
    that produces ~200k synthetic envelopes across many sessions; measure sustained rows/s
    into ClickHouse and end-to-end lag on this Mac. Compare with the single-node NFR
    (5,000 events/s, queryable < 10 s) and say what limited it.

## Tests (Testcontainers: Kafka, ClickHouse, SeaweedFS as GenericContainer; images from `.env.example`)
- events batch → rows in ClickHouse with correct tenant/site/session/type columns
- same records delivered twice (redelivery) → one row each with `FINAL`
- a batch with one poison record → that record in DLT with reason; the rest inserted
- undeserialisable bytes → DLT, consumer keeps going
- **ClickHouse stopped mid-stream → nothing in DLT, offsets not committed; restart
  ClickHouse → all records arrive, none lost, none duplicated (with `FINAL`)**
- **S3 stopped → same guarantee for replay chunks**
- replay chunk → object at the expected key, decompresses to the original payload;
  manifest row correct; redelivered chunk → same object, one manifest row with `FINAL`
- tenant isolation: read helper for tenant A never returns tenant B rows
- no payload content in logs

## Acceptance criteria
`mvn verify` and SDK checks green · all tests above pass · pipeline e2e privacy test passes
locally (summary in PR) · throughput numbers reported · PR opened with the template, stop.

## Do not
Write to PostgreSQL, build session lifecycle, signals, AI or APIs. Use JPA in the processor.
Dead-letter records because a store is down. Change the V1 migration.
