# ADR-0004: Kafka as a buffer — keys, partitions, delivery semantics

**Status:** Accepted · 2026-09

## Decision
- Kafka 4.x in KRaft mode. Topics declared explicitly; auto-create disabled.
- Naming: `<domain>.<entity>.v<schema-major>`; each has a `.dlt` dead-letter topic.
- **Key = `sessionId`** on telemetry, replay and lifecycle topics, guaranteeing per-session order.
- **Partitions:** 6 locally, **24–48 in production, decided before first release.**
  Adding partitions later re-maps keys and breaks per-session ordering in flight.
- Producer: `acks=all`, idempotence on, `compression.type=zstd`, `linger.ms=20`,
  `batch.size=65536`.
- Consumer: batch listener, manual commit after successful write,
  `DefaultErrorHandler` with bounded retries → DLT.
- Delivery is **at-least-once**; consumers are idempotent (dedup on client event id).
- Kafka retention is 3 days. It is a buffer for replay/recovery, not the system of record.
- Payloads: JSON with a versioned JSON Schema in `platform-common` for v1;
  move to Protobuf + schema registry if schema evolution becomes painful.

## Consequences
Consumer parallelism = partition count. Virtual threads speed up work inside a consumer,
not the number of consumers.
