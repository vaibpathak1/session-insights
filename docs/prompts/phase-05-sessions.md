# Phase 5 — Session lifecycle and the session/replay read API

Branch: `phase-5-sessions` (split into `phase-5a-lifecycle` and `phase-5b-read-api` if the
reviewable logic exceeds the ~1,500-line guideline; each PR carries its own tests).
Milestone: M1 (first replay). Implements FR-SES-1, FR-SES-2 (API side), FR-TEN-1 enforcement
in the API, and the data needed by F3/F4.
Read ADR-0002, ADR-0008, ADR-0010, ADR-0011, the Phase 1 schema (`user_session`, `end_user`),
and the processor's store code.

## Context
Events and replay chunks are now persisted, but nothing creates `user_session` rows, closes
sessions, or lets anyone read a session. After this phase, the dashboard (Phase 6) has a
secured API to list sessions, read their events and stream their replay.

## Design rules for this phase
- **Idempotent under redelivery.** Min/max timestamps are safe to upsert repeatedly; counters
  are not. Counters (`page_count`, `error_count`, `duration_ms`) are **computed from
  ClickHouse (`FINAL`) at close time**, never incremented per Kafka delivery.
- **RLS stays on.** Writes set `app.tenant_id` per transaction (group a batch by tenant).
  The only cross-tenant read is a narrow `SECURITY DEFINER` function (same pattern as
  ADR-0010).
- **Safe with several processor instances.** No in-memory-only state that would break with
  two instances.

## Tasks

5.1 **PostgreSQL V5 migration**
    - Role `insights_processor` (placeholders like the other roles): `INSERT/UPDATE` on
      `user_session` and `end_user` only; no `DELETE`; RLS applies.
    - Partial index on `user_session (last_active_at) WHERE ended_at IS NULL`.
    - Function `claim_sessions_to_close(p_idle interval, p_limit int)` (`SECURITY DEFINER`,
      hardened `search_path`): returns `(tenant_id, session_id)` of open sessions idle longer
      than `p_idle`, using `FOR UPDATE SKIP LOCKED` so two closers never claim the same
      session. `EXECUTE` granted to `insights_processor` only.

5.2 **User agent**: collector adds the raw `User-Agent` to envelopes (additive field;
    `schemaVersion` stays 1, unknown-field tolerance already exists). Parsing happens in the
    processor with a maintained Apache-2.0 UA parser (verify and pin), producing `platform`
    (desktop/mobile/tablet) and `browser` + major version.

5.3 **Session tracker** (event-processor, own consumer group on `telemetry.events.v1`,
    platform-thread consumer per ADR-0011): per batch, aggregate by session and upsert
    `end_user` (by site + anonymous_id) and `user_session` (`started_at = least(...)`,
    `last_active_at = greatest(...)`, `entry_url` from the earliest NAVIGATION, platform,
    browser). JDBC only, connecting as `insights_processor`. Outage/poison rules exactly as
    ADR-0011.

5.4 **Session closer** (event-processor, scheduled): every ~30 s, call
    `claim_sessions_to_close('30 minutes', 500)`; for each claimed session compute
    `ended_at`, `duration_ms`, `page_count`, `error_count` from ClickHouse, update the row,
    then publish `CLOSED` to `session.lifecycle.v1` (key = sessionId, compacted topic).
    Idle timeout configurable (tests use seconds).
    **Late events** for a closed session extend `last_active_at`/`ended_at`, recompute on
    the next closer pass, and publish `UPDATED`. Document this in the PR.

5.5 **Shared read module**: move the ClickHouse/S3 read side into a small module (e.g.
    `platform-events`) used by both event-processor and api-service; writes stay in the
    processor. Every read takes `tenantId`.

5.6 **API security (dev-grade, replaced by OIDC in Phase 11)**
    - Spring Security in api-service. `dev` profile: HTTP Basic for the seeded ADMIN
      `app_user`, password from env (`DEV_ADMIN_PASSWORD`), stateless.
    - Any non-dev profile **refuses to start** without real auth configured (fail closed).
    - Authenticated user → tenant → `app.tenant_id` set per request transaction; ClickHouse
      and S3 reads use the same tenant.
    - Another tenant's session id returns `404`, not `403` (no existence leak).

5.7 **Read API** (`/api/v1`, JSON, documented in `docs/api/openapi.yaml` — use springdoc only
    if it supports Boot 4.1; otherwise hand-write the spec and add a test that the
    controllers match it):
    - `GET /sessions` — keyset pagination on `(started_at desc, id)`; filters: time range,
      URL contains (entry URL for now), has error, min/max duration, status (open/closed).
      Returns user (external id or anonymous), times, duration, pages, entry URL, platform,
      browser, error count, friction score (null until Phase 8), analysis status.
    - `GET /sessions/{id}` — same fields + end_user traits (redacted).
    - `GET /sessions/{id}/events?after=&limit=` — ordered by `ts`, from ClickHouse.
    - `GET /sessions/{id}/replay` — manifest (chunk seq, first/last ts, has full snapshot).
    - `GET /sessions/{id}/replay/{seq}` — streams the object **decompressed** as JSON
      (HTTP gzip allowed), after checking the manifest row belongs to the tenant.
      No presigned URLs (keeps S3 private and tenant checks in one place).
    - Live sessions: manifest and events work while the session is open.

5.8 **E2E**: extend `scripts/e2e-pipeline.sh` to require api-service; after the demo run,
    assert through the API (dev auth) that the session is listed, events are returned in
    order, the manifest starts at chunk 0 with a full snapshot, chunk 0 downloads and
    decompresses, and after the (shortened) idle timeout the session is closed with
    correct `duration_ms`/`page_count`/`error_count`. Privacy assertions extend to API
    responses.

## Tests
- tracker: redelivered batch leaves the row identical; out-of-order events keep min/max right
- closer: closes after the idle timeout; two closers in parallel never double-close
  (`SKIP LOCKED`); counters match ClickHouse; `CLOSED` published once per close
- late event after close → extended + `UPDATED`
- processor role cannot `DELETE` or read another tenant's rows
- API: unauthenticated → `401`; tenant A cannot see tenant B (`404`); pagination stable
  under inserts; filters; replay chunk streams decompressed content; non-dev profile without
  auth config fails to start
- outage: Postgres down → tracker retries, no DLT (ADR-0011)
- no payloads, passwords or tokens in logs

## Acceptance criteria
All checks green · e2e passes locally · OpenAPI spec present and matching · PR(s) opened
with the template, then stop.

## Do not
Build the dashboard, signals, AI, identify, or OIDC. Increment counters per delivery.
Expose S3 presigned URLs. Disable RLS for any role.
