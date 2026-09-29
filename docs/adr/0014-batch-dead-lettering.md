# ADR-0014: Dead-letter all poison records of a batch in one pass

**Status:** Accepted · 2026-09 · Supersedes only the dead-lettering *mechanism* of ADR-0011
rule 1 ("reports the first poison record with `BatchListenerFailedException`"). What counts as
poison, `store_rejected` with the ping check and bisection, outages, and the outage tracker all
stand as ADR-0011 decided.

## Context
Under ADR-0011 a listener wrote the good records of a batch, then threw
`BatchListenerFailedException` for the **first** poison record. The error handler committed
the offsets before it, dead-lettered it and redelivered the rest. The next bad record was then
found on the next round. So a batch with *n* bad records took *n* rounds, and every round
re-read, re-parsed, re-aggregated and (for store rejections) re-bisected the remainder.

This showed up in Phase 5a. The session tracker's new consumer group read three days of
`telemetry.events.v1`, which held 550k events of throughput-smoke tenants that don't exist in
PostgreSQL. Every one is a legitimate `store_rejected` (foreign-key violation), and the tracker
cleared about 6 records per second across 6 partitions: roughly 25 hours. A producer bug that
sends a stream of bad envelopes would do the same to the events writer.

## Decision
1. **Every listener dead-letters all poison records of a batch itself, in one pass.**
   - It writes the good records, as before.
   - It publishes every poison record to `<topic>.dlt` through the same
     `DeadLetterPublishingRecoverer` configuration: original key, value bytes and headers;
     `kafka_dlt-original-*`; `si-dlt-reason`; `si-dlt-consumer`; no exception message or
     stack-trace headers.
   - It waits **once** for all the acks (30 s at most), then returns, and the batch is
     acknowledged. One round per batch, however many records are bad.
   - This applies to the events writer, the replay listener and the session tracker.
2. **A failed dead-letter publish is an outage** (`Store.KAFKA`), not poison. The batch is
   retried under ADR-0011's unbounded back-off and nothing is acknowledged, so nothing is lost.
   Writes are idempotent, so the retry rewrites nothing twice. The retry may publish some
   poison records to the DLT a second time: dead-lettering is at least once, like everything
   else on Kafka here.
3. **One log line per batch** ("Dead-lettered N record(s) from topic {reason: count}"), not per
   record. The per-reason counts stay in `si.processor.dlt{topic,reason}`.
4. The error handler keeps Spring's single-record path for a `BatchListenerFailedException`
   raised anywhere else. The listeners no longer throw it.

## Consequences
- A poison run costs about what a good run costs: parse, write the good records, publish the
  bad ones. The 550k-record backlog in the Phase 5a PR is the measured case.
- The listener now depends on Kafka acks for the DLT inside the batch. That was already true
  for the recoverer; it's simply explicit, and it's bounded.
- A DLT consumer may see duplicates after an outage during dead-lettering. The original
  topic, partition and offset headers identify them.

## Alternatives considered
- **Keep `BatchListenerFailedException` and cache rejected offsets** so redelivery rounds skip
  re-bisecting. Rejected: still one redelivery round (poll, seek, re-read) per bad record.
- **Dead-letter the whole batch when it has poison.** Rejected: good records would end up in
  the DLT, which ADR-0011 already ruled out.
