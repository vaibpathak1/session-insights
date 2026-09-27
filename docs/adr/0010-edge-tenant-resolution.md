# ADR-0010: Tenant resolution at the ingestion edge

**Status:** Accepted · 2026-09

## Context
The collector receives SDK batches authenticated only by a site key. It must turn that key
into a `tenant_id` / `site_id` **before** any tenant is known, but row-level security
(ADR-0008) hides every `site_key` row until `app.tenant_id` is set. The collector is the
public, stateless edge and the most exposed service, so it should hold the least database
privilege of any component. It must never trust tenant or site ids sent by the client.

## Decision
1. **One narrow pre-tenant read path.** `resolve_site_key(p_key_hash text)` returns
   `tenant_id`, `site_id`, `allowed_origins`, `sampling_rate` for a non-revoked,
   non-deleted key of an active, non-deleted site and tenant, and nothing else. It is
   `SECURITY DEFINER`, owned by the schema owner, `STABLE`, with
   `SET search_path = pg_catalog, public`. `EXECUTE` is revoked from `PUBLIC`.
2. **Owner read policies.** The tables use `FORCE ROW LEVEL SECURITY`, which binds the
   owner too, so a non-superuser owner would see no rows inside the function. The migration
   adds `FOR SELECT TO <schema owner> USING (true)` policies on `tenant`, `site` and
   `site_key`. The owner can already disable RLS on its own tables, so this grants nothing
   it does not have. The application roles are unaffected.
3. **A dedicated least-privilege role.** The collector connects as `insights_collector`:
   `LOGIN`, no superuser, no `BYPASSRLS`, `USAGE` on the schema, `EXECUTE` on
   `resolve_site_key` and **no table privileges at all**. It never uses JPA.
4. **In-process cache.** The collector hashes the key (SHA-256, as in `SiteKeys`) and keeps
   the results in a bounded Caffeine cache: positive entries for ~60 s, negative (unknown
   or revoked) entries for ~10 s so random keys cannot hammer the database. Concurrent
   misses for one key share a single lookup. **Revocation and site/tenant deactivation take
   effect within the positive TTL (~60 s).**
5. **Rate limiting is per key and per instance** (Bucket4j, in memory). With N collector
   instances, the effective limit is up to N times the configured one. A shared limit
   (e.g. Bucket4j on PostgreSQL or Redis) is deferred until multi-instance deployments need it.
6. **Browser clients always send the site key as the `?k=` query parameter.** A CORS
   preflight cannot carry header values, so a browser request that put the key in
   `X-SI-Key` could never be authorized at preflight; `navigator.sendBeacon` cannot set
   headers at all. The SDK therefore sends `?k=` with a `text/plain` body and no custom
   headers, which is a CORS "simple request" and needs no preflight. **`X-SI-Key` is for
   non-browser clients only** (server-side senders, tools, tests). A key inside the JSON
   body is a last-resort fallback.
7. **CORS is per site.** The collector answers preflights for `/v1/*` itself: it resolves
   `?k=`, and only if the `Origin` is on that site's allow-list does it echo the origin
   (never `*`, never credentials), with `Access-Control-Allow-Methods: POST`,
   `Access-Control-Allow-Headers: Content-Type, Content-Encoding, X-SI-Key` and
   `Access-Control-Max-Age: 600`, so browsers cache a successful preflight for 10 minutes.
   Otherwise it returns a bare `403`. Every response carries `Vary: Origin`. Allow-list
   entries are exact origins or `scheme://host:*` (any port).
8. Query strings can end up in proxy access logs. A site key is public by design (it ships
   in page JavaScript) and only identifies a site. The origin allow-list, rate limits and
   server-side redaction are the controls, so the exposure is accepted. The collector
   itself never logs keys: it masks `k` in the query string before any request logging.

## Consequences
- The only data that the edge can read before tenant context is exactly what it needs.
  A compromised collector credential can resolve keys it already knows, and nothing more.
- Key changes are eventually consistent (≤ positive TTL), which is acceptable for revocation
  of a public identifier. Emergency revocation can restart the collectors.
- Every Flyway run needs the `collectorUser` / `collectorPassword` placeholders.

## Alternatives considered
- **Collector connects as the owner, or as a `BYPASSRLS` role.** Rejected: the most exposed
  service would get read (and often write) access to every tenant's data.
- **Key → tenant map in configuration.** Rejected: no revocation without redeploying, and
  keys would spread into config files and environment dumps.
- **App role with a permissive RLS policy on `site_key`.** Rejected: widens the application
  role for every service, not just the edge.
