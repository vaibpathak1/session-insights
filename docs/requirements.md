# Requirements & Non-Functional Targets

Status: **Draft v0.1** (Phase 0). Every later phase is accepted against this document.
Change it through a PR, and record any decision it forces as an ADR.

## 1. Product scope

An open-source, self-hostable platform that records user sessions on web applications,
lets teams replay them, surfaces friction automatically (rage clicks, errors, dead clicks,
abandoned flows), and uses an LLM to summarise and triage problem sessions, with humans
reviewing anything the system is not confident about.

### Personas

| Persona | Needs |
|---|---|
| **Site owner / developer** | Install a small SDK, trust that it won't slow the site or leak PII |
| **Product analyst** | Find problem sessions fast, replay them, see trends |
| **Support / QA reviewer** | Work a queue of flagged sessions, annotate, approve or reject AI findings |
| **Platform admin** | Manage tenants, sites, keys, retention, model providers |
| **End user (recorded)** | Their private data is masked, and deleted on request |

### Out of scope for v1

Mobile native SDKs, heatmap rendering over live pages, A/B testing, billing, SSO user
provisioning (SCIM), multi-region replication.

## 2. Functional requirements (by epic → build phase)

User-facing features (F1–F15) and their acceptance criteria live in
[`product/features.md`](product/features.md); phases and milestones in
[`roadmap.md`](roadmap.md). The FRs below are the system-level requirements behind them.

| ID | Requirement | Phase |
|---|---|---|
| FR-TEN-1 | Tenants, sites and site keys; every record carries `tenant_id` | 1 ✅ |
| FR-TEN-2 | Configuration entities use soft delete (`is_active`, `deleted_at`) | 1 ✅ |
| FR-ING-1 | Collector accepts batched events over HTTPS (JSON, gzip), authenticated by site key + origin allow-list | 2 ✅ |
| FR-ING-2 | Collector validates, stamps server time, and produces to Kafka keyed by `sessionId` | 2 ✅ |
| FR-ING-3 | Per-tenant rate limiting with `429` + `Retry-After` | 2 ✅ (per site key, see ADR-0010) |
| FR-SDK-1 | Browser SDK records via rrweb, batches, sends with `fetch keepalive` / `sendBeacon` on unload | 3 ✅ |
| FR-SDK-2 | Privacy by default: all inputs masked, opt-in unmasking per selector, block-list for elements | 3 ✅ |
| FR-PRC-1 | Events persisted to ClickHouse, replay chunks to object storage | 4 ✅ |
| FR-PRC-2 | At-least-once delivery with deduplication by client event id; poison messages to DLT | 4 ✅ (store outages never dead-letter, see ADR-0011) |
| FR-SES-1 | Session closes after inactivity timeout (default 30 min) or explicit end | 5 ✅ (inactivity; explicit end with the SDK `shutdown()` in a later phase) |
| FR-SES-2 | Replay player streams chunks for a closed or live session | 5–6 |
| FR-SIG-1 | Deterministic signals per session: rage clicks, dead clicks, error clusters, friction score | 8 |
| FR-AI-1 | Only sessions above a friction threshold are sent for AI analysis | 9 |
| FR-AI-2 | LLM receives a redacted, compact session summary (never raw DOM) | 9 |
| FR-AI-3 | Provider is configurable: Ollama (default), OpenAI, Anthropic; circuit breaker + fallback | 9 |
| FR-AI-4 | Deterministic guardrail rules can force `REVIEW_REQUIRED` regardless of LLM output | 9 |
| FR-HITL-1 | Reviewers list, open, annotate and transition flagged sessions | 10 |
| FR-HITL-2 | Only legal state transitions; concurrent edits rejected (optimistic locking); every change audited | 10 |
| FR-SRCH-1 | "Find similar sessions" via vector search over insight embeddings | v2 (F14) |
| FR-UI-1 | Dashboard: dev login, session list with filters, replay player with timeline | 6–7 |
| FR-PRV-1 | Erase all data for a given end-user id or session within the retention SLA | 11 |

### Analysis status lifecycle

```
PENDING → PROCESSING → AI_ANALYZED → (optional) HUMAN_APPROVED
                    ↘ REVIEW_REQUIRED → HUMAN_APPROVED | HUMAN_REJECTED
PROCESSING → FAILED (retryable)
```

## 3. Non-functional requirements

Targets are **hypotheses to be proven by load tests**, not guarantees. Two reference
environments are defined so numbers stay honest.

- **Single node:** 8 vCPU, 32 GB RAM, SSD, no GPU, running the full compose stack.
- **Reference cluster:** 3 Kafka brokers, 3+ collector pods, ClickHouse 2 shards × 2 replicas,
  PostgreSQL primary + replica, 1 GPU node for Ollama.

| Area | Single node | Reference cluster |
|---|---|---|
| Sustained ingest | 5,000 events/s | 50,000 events/s, scaling by adding nodes |
| Collector latency (p99, ack) | < 200 ms | < 150 ms |
| Event queryable after ingest | < 10 s | < 10 s |
| Replay available after session close | < 60 s | < 30 s |
| AI analysis latency for flagged session | < 15 min | < 5 min |
| Share of sessions sent to LLM | ≤ 5 % | ≤ 5 % (tunable) |
| Acknowledged-event loss | Best effort (RF=1) | Zero (acks=all, RF=3, min.insync=2) |
| Collector availability | n/a | 99.9 % |

### Retention defaults (configurable per tenant)

Raw events 30 days · replay chunks 30 days · AI insights 13 months · audit log 1 year ·
Kafka topics 3 days (buffer only).

### SDK budget

Target < 60 KB gzipped on the page, no long tasks > 50 ms attributable to the SDK,
never throws into the host page, and fails silent if the collector is down.

### Privacy & compliance (India DPDP Act 2023, GDPR)

1. Mask by default in the browser; password and payment fields can never be unmasked.
2. Server-side redaction pass (emails, phone numbers, card-like numbers) before storage
   and again before any LLM call.
3. External LLM providers are **off by default**; enabling one is an explicit tenant setting.
4. Erasure requests completed within 30 days across PostgreSQL, ClickHouse and object storage.
5. Data residency: self-hosted by design; no telemetry leaves the operator's infrastructure.

### Security

Tenant isolation enforced in the data layer (PostgreSQL RLS; `tenant_id` in every
ClickHouse query path), OIDC for the dashboard with roles `ADMIN`, `ANALYST`, `VIEWER`,
site keys are public, write-only and origin-restricted, secrets never committed,
LLM inputs treated as untrusted (prompt-injection defence: delimiting, read-only tools,
idempotent side effects).

### Observability

Micrometer metrics + OpenTelemetry traces on every service; key SLIs: ingest rate,
consumer lag per topic, ClickHouse insert latency, DLT depth, LLM latency / error rate,
review queue age.

## 4. Definition of done (every phase)

Code + tests (unit + Testcontainers integration) green in CI · runs in the compose stack ·
docs and ADRs updated · no secret in git · a short demo script in the PR description.
