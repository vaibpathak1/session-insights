# Phase 1 — Data schemas and domain persistence

Branch: `phase-1-schemas`. Milestone: M1 (first replay).

Goal: create the PostgreSQL and ClickHouse schemas and the JPA domain layer for **all v1
features (F1–F8)**, leaving clean room for v2 (F9–F15) without building them.
Read `docs/product/features.md` and `docs/roadmap.md` first. Implements the storage rules
of ADR-0002, ADR-0005 and ADR-0008.

## Tasks

1.1 **Module `services/platform-db`** (plain jar):
    - Flyway SQL for PostgreSQL in `db/migration/postgres`
    - ClickHouse migration SQL in `db/migration/clickhouse`
    - a small `ClickHouseMigrator` (ordered `V<n>__name.sql`, applied once, tracked in a
      `schema_migrations` table in ClickHouse, checksum-verified)
    `api-service` runs both on startup (Flyway via Boot auto-config).

1.2 **PostgreSQL V1 — tenancy and configuration** (soft delete on all: `is_active`,
    `deleted_at`, plus `created_at`, `updated_at`, `version`):
    - `tenant` (name, retention days for events / replays / insights, external LLM allowed flag)
    - `site` (tenant, name, allowed origins `text[]`, sampling rate 0–1, signal thresholds `jsonb`)
    - `site_key` (site, key prefix for display, key hash — never plaintext, revoked_at)
    - `masking_rule` (site, css selector, action CHECK in MASK|UNMASK|BLOCK)  — F8
    - `model_provider_config` (tenant, provider CHECK in OLLAMA|OPENAI|ANTHROPIC, enabled,
      chat model, embedding model; no secrets — secrets come from env)
    - `app_user` (dashboard users: tenant, email, display name, role CHECK in
      ADMIN|ANALYST|VIEWER, external subject id for OIDC later)

1.3 **PostgreSQL V2 — end users, sessions, insights, review**
    - `end_user` — F2: `id uuid`, `tenant_id`, `site_id`, `external_user_id` (nullable
      until identify), `anonymous_id`, `traits jsonb` (redacted), `first_seen_at`,
      `last_seen_at`; unique (site_id, external_user_id) where not null
    - `user_session`: `id uuid`, `tenant_id`, `site_id`, `end_user_id` (FK end_user),
      `anonymous_id`, `started_at`, `last_active_at`, `ended_at`, `duration_ms bigint`,
      `page_count`, `platform`, `browser`, `entry_url`, `friction_score smallint`,
      signal counters (`rage_click_count`, `dead_click_count`, `error_count`),
      `analysis_status` (CHECK in PENDING, PROCESSING, AI_ANALYZED, REVIEW_REQUIRED,
      HUMAN_APPROVED, HUMAN_REJECTED, FAILED), `assigned_to` (FK app_user, nullable),
      `version`, timestamps. Indexes for F3 filters: (tenant_id, started_at desc),
      (tenant_id, friction_score desc), (tenant_id, analysis_status), (end_user_id).
      No FK to events (ADR-0002).
    - `session_insight` — F6: `id bigint` from a sequence with increment 50, `tenant_id`,
      `session_id` (FK), `summary`, `probable_cause`, `friction_score`,
      `auto_tags text[]`, `human_notes`, `evidence jsonb` (replay timestamps the summary
      refers to), `embedding vector(768)`, `model_provider`, `model_name`,
      `guardrail_rule` (which rule forced review, nullable), `version`, timestamps.
      HNSW index on `embedding` with `vector_cosine_ops`.
    - `review_event` — F7, append-only audit: session, actor (app_user), action,
      from_status, to_status, note, before/after `jsonb`, created_at.
    - `erasure_request` — F8: tenant, subject_type CHECK in END_USER|SESSION, subject_id,
      status CHECK in REQUESTED|IN_PROGRESS|COMPLETED|FAILED, per-store progress `jsonb`,
      requested_by, requested_at, completed_at.
    - `external_ticket_link` — **v2 hook for F9, table only, no code**: tenant, session_id,
      system CHECK in JIRA|SERVICENOW|WEBHOOK, external_id, url, created_by, created_at.

1.4 **PostgreSQL V3 — row-level security**
    - Non-owner application role; the app connects as that role, Flyway as owner.
    - Enable + FORCE RLS on every tenant-scoped table; policy uses
      `current_setting('app.tenant_id', true)::uuid`.
    - A Spring component sets `app.tenant_id` per transaction.

1.5 **ClickHouse V1 — events**
    `events`: `ReplacingMergeTree(ingested_at)`, `PARTITION BY toYYYYMMDD(ts)`,
    `ORDER BY (tenant_id, session_id, ts, event_id)`, `TTL ts + INTERVAL 30 DAY`
    (per-tenant retention is enforced later by the erasure/retention job).
    Columns:
    - identity: `event_id UUID`, `tenant_id UUID`, `site_id UUID`, `session_id UUID`,
      `anonymous_id String`, `end_user_id Nullable(UUID)`
    - time: `ts DateTime64(3)`, `ingested_at DateTime64(3)`
    - type: `event_type LowCardinality(String)` (CLICK, DEAD_CLICK, RAGE_CLICK,
      ERROR_CLICK, NAVIGATION, INPUT, SCROLL, CONSOLE_ERROR, EXCEPTION, IDENTIFY,
      CUSTOM, NETWORK), `event_name LowCardinality(String)` (for CUSTOM — v2 F15)
    - context: `url`, `path`, `page_title`, `target_selector`, `target_text` (masked),
      `error_message`, `error_stack`
    - network — **v2 hook for F13**, default empty: `http_method LowCardinality(String)`,
      `http_url`, `http_status UInt16`, `http_duration_ms UInt32`
    - `props JSON`
    Skipping indexes on `event_type` and `path`.

1.6 **JPA domain layer** (propose `platform-db` or a separate `platform-domain` module):
    entities for every PostgreSQL table except append-only audit reads, enums with
    `@Enumerated(STRING)`, repositories. Soft delete via `@SQLDelete` + `@SQLRestriction`.
    `session_insight` uses `@SequenceGenerator(allocationSize = 50)`. `embedding` via
    `hibernate-vector`, `auto_tags` as `String[]`, `jsonb` via `@JdbcTypeCode(SqlTypes.JSON)`.
    Settings: `hibernate.jdbc.batch_size=50`, `order_inserts=true`,
    JDBC URL `reWriteBatchedInserts=true`. No entity for `external_ticket_link` yet.

1.7 **Seed data (dev profile only)**: one tenant, one site (origin `http://localhost:*`),
    one site key printed to the log once, one ADMIN app_user. Needed for M1 demo.

1.8 **Tests** (Testcontainers: `pgvector/pgvector:pg17`, ClickHouse image from `.env.example`)
    - migrations apply on an empty DB and are no-ops on re-run
    - invalid enum values rejected by CHECK constraints
    - soft-deleted config rows invisible to repositories but still in the table
    - RLS: tenant A cannot read tenant B's sessions, even with a native query
    - vector: 3 embeddings, nearest-neighbour returns the expected row; HNSW index exists
    - ClickHouse: same `event_id` inserted twice → one row with `FINAL`
    - optimistic locking: concurrent update of one session → one succeeds, one fails
    - identify: two sessions with same anonymous_id linked to one end_user after identify

## Acceptance criteria
`mvn verify` green on JDK 21 · api-service starts against the compose stack and both
migration sets apply · all tests pass · PR opened with the template, then stop.

## Do not
Build REST endpoints, Kafka consumers, AI code, or any v2 feature logic. Store events in
PostgreSQL. Add a GIN index on any payload. Use 1536-dim vectors.
