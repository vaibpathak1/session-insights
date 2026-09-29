# Architecture Decision Records

Short, immutable records of significant decisions. To change a decision, add a new ADR
that supersedes the old one; do not edit history.

| # | Decision | Status |
|---|---|---|
| [0001](0001-runtime-and-framework.md) | Java 21, Spring Boot 4.1, Spring AI 2.0, virtual threads | Accepted |
| [0002](0002-storage-split.md) | PostgreSQL + ClickHouse + S3-compatible object storage | Accepted |
| [0003](0003-object-storage-seaweedfs.md) | SeaweedFS instead of MinIO | Accepted |
| [0004](0004-kafka-topology.md) | Kafka as a buffer: keys, partitions, delivery semantics | Accepted (consumer retry/DLT rule superseded by 0011) |
| [0005](0005-llm-provider-strategy.md) | Ollama by default, signal-gated LLM analysis, guardrails over tools | Accepted |
| [0006](0006-recording-and-privacy.md) | rrweb recording with privacy-by-default masking | Accepted |
| [0007](0007-frontend-stack.md) | React dashboard, framework-free TypeScript SDK | Accepted |
| [0008](0008-multi-tenancy.md) | Shared-schema multi-tenancy with RLS | Accepted |
| [0009](0009-license.md) | Apache License 2.0 | Accepted |
| [0010](0010-edge-tenant-resolution.md) | Tenant resolution at the ingestion edge (SECURITY DEFINER lookup, collector role, key cache) | Accepted (CORS §7 superseded by 0012) |
| [0011](0011-consumer-failure-handling.md) | Failure handling in consumers: poison → DLT (incl. store-rejected), store outage → unbounded back-off | Accepted (dead-lettering mechanism superseded by 0014) |
| [0012](0012-readable-auth-errors.md) | Readable auth errors for browsers: preflight always succeeds, 401/403 echo the origin | Accepted |
| [0014](0014-batch-dead-lettering.md) | Dead-letter all poison records of a batch in one pass; a failed DLT publish is an outage | Accepted |

Template: Context → Decision → Consequences → Alternatives considered.
