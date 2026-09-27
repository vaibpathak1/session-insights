# Architecture Decision Records

Short, immutable records of significant decisions. To change a decision, add a new ADR
that supersedes the old one; do not edit history.

| # | Decision | Status |
|---|---|---|
| [0001](0001-runtime-and-framework.md) | Java 21, Spring Boot 4.1, Spring AI 2.0, virtual threads | Accepted |
| [0002](0002-storage-split.md) | PostgreSQL + ClickHouse + S3-compatible object storage | Accepted |
| [0003](0003-object-storage-seaweedfs.md) | SeaweedFS instead of MinIO | Accepted |
| [0004](0004-kafka-topology.md) | Kafka as a buffer: keys, partitions, delivery semantics | Accepted |
| [0005](0005-llm-provider-strategy.md) | Ollama by default, signal-gated LLM analysis, guardrails over tools | Accepted |
| [0006](0006-recording-and-privacy.md) | rrweb recording with privacy-by-default masking | Accepted |
| [0007](0007-frontend-stack.md) | React dashboard, framework-free TypeScript SDK | Accepted |
| [0008](0008-multi-tenancy.md) | Shared-schema multi-tenancy with RLS | Accepted |
| [0009](0009-license.md) | Apache License 2.0 | Accepted |
| [0011](0011-edge-tenant-resolution.md) | Tenant resolution at the ingestion edge (SECURITY DEFINER lookup, collector role, key cache) | Accepted |

Template: Context → Decision → Consequences → Alternatives considered.
