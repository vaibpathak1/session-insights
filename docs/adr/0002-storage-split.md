# ADR-0002: PostgreSQL + ClickHouse + S3-compatible object storage

**Status:** Accepted · 2026-09

## Context
Session recording produces very high-volume, append-only data (10k concurrent users ×
~5 events/s ≈ 50k events/s). The same product also needs transactional state (tenants,
configs, review workflow) and vector search. No single store is good at all three.

## Decision
| Store | Owns |
|---|---|
| **PostgreSQL 17 + pgvector** | Tenants, sites, keys, configs (soft delete), session metadata, AI insights, embeddings (HNSW), review workflow, audit log |
| **ClickHouse** | All structured telemetry events and analytics aggregates |
| **S3-compatible storage** | Compressed rrweb replay chunks, one object per chunk |

Rules:
- Hot path never uses JPA. Events → ClickHouse via the Java client in large batches.
  JPA / Hibernate is used only for PostgreSQL domain entities.
- No cross-database foreign keys. `session_id` (UUID) and `tenant_id` link records.
- ClickHouse events: `ReplacingMergeTree` keyed on client event id (dedup), ordered by
  `(tenant_id, session_id, ts)`, partitioned by day, `TTL` for retention.
- Event persistence sits behind an `EventStore` interface for testability.

## Consequences
- Two databases to operate. Mitigated by single-node defaults in compose and Helm later.
- The original brief's GIN index on a JSONB event payload and the PostgreSQL event FK
  are dropped; they conflict with high write throughput.
- Erasure touches three stores; handled by one erasure workflow (FR-PRV-1).

## Alternatives considered
- **PostgreSQL only (partitioned):** simplest, but write and analytics ceiling well below target.
- **TimescaleDB:** key features under the Timescale License, not Apache 2.0.
- **Cassandra / ScyllaDB:** great writes, weak ad-hoc analytics.
- **Dedicated vector DB (Qdrant, Milvus):** unnecessary at expected volume.
