# ADR-0008: Shared-schema multi-tenancy with row-level security

**Status:** Accepted · 2026-09

## Decision
- Every table and event carries `tenant_id`.
- PostgreSQL: Row-Level Security policies keyed on a session variable
  (`SET app.tenant_id`) set per request/transaction.
- ClickHouse: `tenant_id` is the first ordering key column; all queries go through a
  repository layer that always applies it (plus row policies in multi-tenant SaaS mode).
- Object storage keys: `tenants/{tenantId}/sessions/{sessionId}/{chunkSeq}.json.zst`.

## Consequences
One deployment serves many tenants cheaply; large customers can still get a dedicated
deployment with the same code.
