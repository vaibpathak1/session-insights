# ADR-0013: Dev-grade API authentication and tenant binding

**Status:** Accepted · 2026-09 · Interim until OIDC (Phase 11), which will replace section 1 only.

## Context
Phase 5 exposes sessions, events and replay through api-service. Every read must be bound to
the caller's tenant across three stores: PostgreSQL under RLS (ADR-0008), ClickHouse and
object storage, which have no RLS. OIDC is planned for Phase 11, but the dashboard (Phase 6)
needs an authenticated API now. Two constraints shape this:
- A user can't be looked up under RLS before their tenant is known.
- A shortcut like "no auth in dev" must not be able to reach any other environment.

## Decision
1. **Dev-grade authentication, `dev` profile only.**
   - HTTP Basic, stateless, no session cookie, CSRF off (there's no cookie to forge).
   - The username is the email of an active app user; the password is `DEV_ADMIN_PASSWORD`.
     It's required, with at least 12 characters, or the application doesn't start.
   - The password is compared in constant time over SHA-256 digests: no bcrypt on every
     request, and nothing leaks through timing or length.
   - Failures say only `401`: not which part was wrong.
2. **Fail closed.** Any profile other than `dev` refuses to start, with a clear message, until
   real authentication (OIDC) exists. The API never runs unauthenticated.
3. **A pre-tenant user lookup: `resolve_app_user(email)`** (V6), the same pattern as
   `resolve_site_key` in ADR-0010.
   - `SECURITY DEFINER` with a pinned `search_path`, and `EXECUTE` for the app role only.
   - It returns only the user id, tenant and role of an active user of an active tenant.
   - An email that exists in several tenants is refused, never guessed.
   - With OIDC, the lookup will be by `external_subject_id` instead.
4. **Tenant binding.**
   - A filter runs the rest of every authenticated request as the principal's tenant
     (`TenantContext`), so every PostgreSQL transaction sets `app.tenant_id` and RLS applies.
   - The list query also filters on `tenant_id` (defence in depth, and it uses the index).
   - ClickHouse and object-storage reads take the principal's `tenantId` explicitly; object
     keys are built from it.
   - A replay chunk is streamed only after its manifest row is found for that tenant's
     session. No presigned URLs.
5. **404, not 403, for another tenant's data.** Sub-resources (events, manifest, chunks) first
   confirm the session exists in PostgreSQL for the caller's tenant, so another tenant's
   session id answers exactly like an unknown one. Existence doesn't leak.

## Consequences
- Local development and the e2e run need `DEV_ADMIN_PASSWORD`; `.env.example` has a
  placeholder.
- The dev password is shared by every app user in the dev profile. That's acceptable only
  because the profile is local-only and every other profile fails closed.
- Each request costs one call to `resolve_app_user`, a single indexed row. It will move to
  token validation with OIDC.
- Phase 11 replaces section 1 (Basic auth) with OIDC. Sections 2–5 stay.

## Alternatives considered
- **No authentication in dev.** Rejected: one misconfigured profile away from an open API.
- **Pin the dev user to the seeded tenant in configuration.** Rejected: it can't test tenant
  isolation, and OIDC needs the lookup anyway.
- **403 for another tenant's session.** Rejected: it confirms the session exists.
- **Presigned S3 URLs for replay chunks.** Rejected: the bucket would be reachable without the
  API, and the tenant check would be split across two places.
