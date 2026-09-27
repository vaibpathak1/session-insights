# Phase 2 — Collector API → Kafka

Branch: `phase-2-collector`. Milestone: M1 (first replay).
Implements FR-ING-1, FR-ING-2, FR-ING-3 and the ingestion side of F1.
Read `docs/product/features.md` (F1), `docs/requirements.md` (NFRs), ADR-0004, ADR-0006, ADR-0008.

## Context
The browser SDK (Phase 3) will POST batches to `collector-service`. The collector is the
public, stateless edge: it authenticates the site key, checks the origin, validates and
size-limits the batch, stamps server-side identity and time, and produces to Kafka.
It never uses JPA and never trusts tenant/site ids sent by the client.

Known constraint from Phase 1: the site key must be resolved **before** the tenant is
known, but RLS hides `site_key` rows without a tenant.

## Tasks

2.1 **ADR-0011 — tenant resolution at the edge.** Write `docs/adr/0011-edge-tenant-resolution.md`
    (and index it): a narrow `SECURITY DEFINER` function is the only pre-tenant read path;
    a dedicated least-privilege DB role for the collector; resolved keys cached in-process.
    Alternatives considered: collector connecting as owner / BYPASSRLS role (rejected:
    too broad), key → tenant map in config (rejected: no revocation).

2.2 **PostgreSQL V4 migration** (in `platform-db`):
    - Function `resolve_site_key(p_key_hash text)` returning `tenant_id`, `site_id`,
      `allowed_origins text[]`, `sampling_rate` — only for a non-revoked key of an active,
      non-deleted site and tenant. `SECURITY DEFINER`, owned by the schema owner,
      `SET search_path = pg_catalog, public`, `STABLE`.
    - Role `insights_collector` (Flyway placeholders, like the app role): `LOGIN`,
      no table privileges at all, only `EXECUTE` on `resolve_site_key`.
      `REVOKE ALL ON FUNCTION … FROM PUBLIC` first.
    - Tests: the collector role gets a row for a valid key; nothing for a revoked key or a
      soft-deleted site; `SELECT` on `site_key` / `tenant` as the collector role is denied.

2.3 **Wire contracts in `platform-common`** (Java records, Jackson 3, no Spring):
    - Inbound: `EventBatch` (siteKey?, sessionId, anonymousId, sdkVersion, events[]),
      `TelemetryEvent` (clientEventId, type, ts, url, path, title, targetSelector,
      targetText, errorMessage, errorStack, eventName, props), `ReplayBatch`
      (siteKey?, sessionId, chunkSeq, compressed rrweb payload as base64 or raw events).
      Unknown fields ignored; unknown event `type` rejected.
    - Outbound Kafka envelopes: `TelemetryEnvelope` / `ReplayEnvelope` carrying
      server-assigned `tenantId`, `siteId`, `receivedAt`, plus the client data.
      Event `type` enum matches the ClickHouse `event_type` values from Phase 1.
    - A `schemaVersion` field (1) and a Kafka header `si-schema-version`.

2.4 **Endpoints** in `collector-service`:
    - `POST /v1/events` → `telemetry.events.v1`; `POST /v1/replay` → `replay.chunks.v1`.
    - Site key accepted from header `X-SI-Key` **or** query param `k` — `navigator.sendBeacon`
      cannot set headers.
    - Accept `application/json` **and** `text/plain` bodies (beacon sends text/plain to avoid a
      CORS preflight); honour `Content-Encoding: gzip`.
    - CORS: preflight `OPTIONS` handled; `Access-Control-Allow-Origin` echoes the origin only
      when it matches the site's allowed origins (support `http://localhost:*` style port
      wildcards); never `*`.
    - Responses: `202` accepted (after Kafka ack), `400` invalid, `401` unknown/revoked key,
      `403` origin not allowed, `413` too large, `429` rate limited with `Retry-After`,
      `503` Kafka unavailable with `Retry-After`. Error body is a small JSON code, no details
      that help probing.

2.5 **Tenant resolution service**: hash the key (SHA-256, same as Phase 1 `SiteKeys`), call
    `resolve_site_key` via `JdbcTemplate` as `insights_collector`. Caffeine cache:
    positive entries ~60 s, **negative entries ~10 s** (so random keys can't hammer the DB),
    bounded size. Revocation takes effect within the positive TTL — document it.

2.6 **Validation and limits** (configurable, defaults):
    - events batch ≤ 500 events and ≤ 1 MB **decompressed**; replay batch ≤ 4 MB decompressed
      (matches the topic's `max.message.bytes`); abort decompression past the limit
      (gzip-bomb protection) → `413`.
    - `sessionId` and `clientEventId` must be UUIDs; client `ts` within 24 h in the past and
      5 min in the future, else rejected per event (count it, don't fail the batch).
    - String fields truncated to sane maxima (e.g. URL 2 KB, selector 1 KB, stack 8 KB).
    - Server-side redaction pass on `targetText`, `errorMessage`, `url` query strings:
      emails, phone numbers, card-like digit runs → masked (ADR-0006, defence in depth).

2.7 **Kafka producer**: key = `sessionId`; `acks=all`, idempotence on,
    `compression.type=zstd`, `linger.ms=20`, `batch.size=65536`, `delivery.timeout.ms`
    bounded. Send all records of a request and wait for their acks (virtual threads make the
    blocking fine) before replying `202` — acknowledged events must never be lost (NFR).
    Topic names from `Topics`. Headers: `si-tenant-id`, `si-schema-version`.

2.8 **Rate limiting**: per site key token bucket (Bucket4j, in-memory; note in the ADR that
    multi-instance limits come later). Defaults: 50 req/s burst 100 per key; configurable.

2.9 **Observability**: Micrometer counters `si.collector.events.accepted`,
    `si.collector.requests.rejected{reason}`, timer `si.collector.kafka.send`.
    **Never log request bodies, site keys, or event contents.**

2.10 **Config + compose wiring**: `application.yml` for DB (collector role), Kafka
    bootstrap, limits; `.env.example` gets `POSTGRES_COLLECTOR_USER/PASSWORD`. Collector
    runs from the IDE on port 8081 against the compose stack.

## Tests (Testcontainers: Kafka image and pgvector image from `.env.example`)
- valid events batch → records on `telemetry.events.v1`, key = sessionId, tenant/site from
  the key (a client-sent tenantId is ignored), `202`
- replay batch → `replay.chunks.v1`
- `text/plain` body with key in `?k=` works (beacon path); gzip body works
- unknown key → `401`; revoked key → `401` (after cache TTL, with a short test TTL)
- origin not allowed → `403`; allowed wildcard localhost port → CORS headers correct
- oversize and gzip bomb → `413`; invalid UUID → `400`; out-of-window timestamps dropped and counted
- redaction masks an email and a card-like number
- rate limit exceeded → `429` with `Retry-After`
- Kafka unreachable → `503` (not a hang, not a `202`)
- cache: repeated requests with the same key hit the DB once; unknown keys are negatively cached
- log capture: no site key or body content in logs

## Manual check (paste output in the PR)
With compose up and api-service run once (seed), start collector-service and send one batch
with `curl` using the dev site key; show the record with `kafka-console-consumer` (or Kafka UI).

## Acceptance criteria
`mvn verify` green on JDK 21 · all tests above pass · manual curl → Kafka works ·
ADR-0011 added · PR opened with the template, then stop.

## Do not
Build the SDK, any Kafka consumer, or ClickHouse writes. Use JPA/Hibernate in
collector-service. Give the collector role any table privilege. Log payloads.
