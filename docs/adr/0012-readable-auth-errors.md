# ADR-0012: Readable auth errors for browsers

**Status:** Accepted · 2026-09 · Supersedes ADR-0010 §7 (CORS is per site) only.
Everything else in ADR-0010 stands: key resolution, the collector role, the cache, rate
limits, and keys sent as `?k=` by browsers.

## Context
Under ADR-0010 §7 the collector echoed a CORS origin only after the key resolved and the
origin matched the site's allow-list. A bad key or origin got a bare `403` preflight or a
`401`/`403` without `Access-Control-Allow-Origin`. The browser hides both from JavaScript,
so the SDK saw a network error. It could not tell "wrong key" from "collector down" and
stopped only after five failed requests. An installer saw nothing but failed requests in
the network tab.

## Decision
1. **Preflight always succeeds.** `OPTIONS /v1/*` returns `204` and echoes the request
   `Origin`, with `Vary: Origin`, `Access-Control-Allow-Methods: POST`,
   `Access-Control-Allow-Headers: Content-Type, Content-Encoding, X-SI-Key` and
   `Access-Control-Max-Age: 600`. Never `*`, never credentials. The preflight does no key
   lookup, so it never touches the database. A missing or `null` origin is not echoed.
2. **Authorization happens on the actual request.** Key resolution and the origin
   allow-list are enforced on the `POST`, as before. Only authorized requests are produced
   to Kafka.
3. **`401` and `403` are readable.** They carry `Access-Control-Allow-Origin: <request
   origin>` (not for a missing or `null` origin) and `Vary: Origin`, with the same minimal
   body (`{"error":"unauthorized"}` / `{"error":"forbidden"}`). All other responses keep
   echoing the origin only once it matched the allow-list.
4. **The SDK stops on the first readable `401`/`403`.** With `debug: true` it logs
   "invalid site key" or "origin not allowed for this site". The five-consecutive-failures
   fallback remains for network errors (collector down, CSP, ad blockers, or a collector
   older than this ADR).

## Rationale
- The origin allow-list is enforced on the `POST`. It never was a boundary against
  non-browser clients, which can send any `Origin` header or none. It stops other sites'
  pages from spending a site's quota, and it still does.
- Site keys are 32 random bytes. A readable `401` versus `403` tells a caller whether a key
  exists, but guessing a key is infeasible, and keys are public anyway: they ship in the
  site's JavaScript (ADR-0010 §8).
- Install-time errors become visible: a wrong key or a missing allow-list entry shows up on
  the first request, in the SDK's debug log and in the network tab.
- Preflights stop costing a key lookup, and with `Max-Age: 600` a browser sends one per
  origin every 10 minutes.

## Consequences
- The SDK sends one request, not five, to a collector that refuses it, so a misconfigured
  install creates less load.
- The response still says nothing beyond `unauthorized` / `forbidden`, and nothing from the
  request is logged.
- Tests: preflights for unknown keys, disallowed origins and no key succeed; `401`/`403`
  carry the CORS headers; `202` is unchanged; the SDK stops on the first refusal, including
  end to end in a browser (`sdk/e2e/wrong-key.spec.ts`).

## Alternatives considered
- **Keep ADR-0010 §7.** Rejected: misconfigured installs are invisible, and the SDK needs
  five failures to give up.
- **Echo `*` on refusals.** Rejected: never `*` on this API; echoing the request origin with
  `Vary: Origin` is equivalent for a credential-less response and stays cache-correct.
- **A separate config/health endpoint the SDK calls first.** Rejected: an extra request on
  every page load, for information the first real request already carries.
