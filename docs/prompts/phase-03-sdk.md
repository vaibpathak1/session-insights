# Phase 3 — Browser SDK: recording, masking, batching

Branch: `phase-3-sdk`. Milestone: M1 (first replay).
Implements FR-SDK-1, FR-SDK-2 and F1 (except remote config). Identify (F2) is Phase 7:
reserve the API shape only.
Read `docs/product/features.md` (F1, F2), ADR-0006, ADR-0007, ADR-0010, and the collector
contracts in `platform-common` + `collector-service` (endpoints, limits, response codes).

## Context
The SDK runs inside customers' pages, so its first duty is to do no harm: never throw into
the host page, never block it, never send unmasked private data, stay small.
It talks only to the Phase 2 collector.

## Tasks

3.0 **Chores (small, own commits)**
    - CLAUDE.md rule: "Never modify a Flyway/ClickHouse migration after it is merged to
      `main`; add a new version instead."
    - `.github/workflows/ci.yml`: `timeout-minutes: 30` on every job (a hung test must not
      hold CI for hours).
    - `scripts/dev-site-key.sh`: rotates the **dev** site's key in the compose Postgres
      (revoke current, insert new), prints the new key once, and writes it to
      `examples/demo-site/.env.local` (git-ignored). Key format and hash must match
      Phase 1 `SiteKeys` exactly (read it; don't re-invent). Refuses to run unless the
      dev tenant exists.

3.1 **Package setup** in `sdk/` — `@session-insights/sdk`
    - Node 24 LTS (`.nvmrc`), npm, TypeScript strict, target ES2020.
    - Build: ESM + IIFE (`window.SessionInsights`, for a `<script>` tag) + `.d.ts`,
      with tsup or Vite library mode (pick one, justify in the PR).
    - Tests: Vitest (+ jsdom) for units; Playwright for e2e.
    - ESLint + Prettier. Size check with `size-limit`.
    - Only runtime dependency: rrweb recording (`@rrweb/record` or `rrweb`). **Verify the
      current stable version on npm and pin it exactly**; note in the PR if only
      pre-release versions exist.

3.2 **Public API** (stable from now on; document in `sdk/README.md`)
    ```ts
    SessionInsights.init({ siteKey, collectorUrl, sampleRate?, maskAllText?,
                           flushIntervalMs?, debug? })
    SessionInsights.identify(userId, traits?)   // Phase 7: no-op that logs in debug mode
    SessionInsights.track(name, props?)         // v2 (F15): no-op in debug mode
    SessionInsights.reset()                     // new anonymousId + new session
    SessionInsights.shutdown()                  // flush and stop
    SessionInsights.getSessionId()
    ```
    Calling `init` twice is a no-op with a debug warning. All methods are safe before `init`.

3.3 **Identity and session**
    - `anonymousId`: UUID in `localStorage`; in-memory fallback if storage is blocked.
    - `sessionId`: UUID per tab in `sessionStorage`, rotated after 30 min inactivity
      (matches FR-SES-1) or on `reset()`.
    - Sampling decided once per session from `sampleRate`; unsampled sessions send nothing.

3.4 **Recording and masking** (privacy is the acceptance bar, not a nice-to-have)
    - rrweb with `maskAllInputs: true`. Password fields, `autocomplete="cc-*"` fields, and
      elements matching `[data-si-block]` are **never** recorded, whatever the config.
    - `data-si-unmask` opts an element's inputs out of masking, **except** password/card
      fields, which stay masked.
    - `maskAllText` option masks all text nodes (off by default; on for strict tenants).
    - Canvas, video, cross-origin iframes: not recorded (v1).

3.5 **Derived events** → `POST /v1/events` (contract = `EventBatch` in platform-common)
    CLICK (masked target text, CSS selector), NAVIGATION (initial load, `pushState`,
    `replaceState`, `popstate`, `hashchange`), CONSOLE_ERROR (wrap `console.error`,
    always call the original), EXCEPTION (`error` + `unhandledrejection`, call previous
    handlers). The SDK must **not** capture its own internal errors.
    Rage/dead click detection is Phase 8 (server side); send raw clicks only.

3.6 **Replay chunks** → `POST /v1/replay` (contract = `ReplayBatch`)
    Buffer rrweb events; flush every `flushIntervalMs` (default 5 s) or at ~256 KB,
    `chunkSeq` increments per session, starting at 0 with a full snapshot.

3.7 **Transport**
    - Site key **always** as `?k=` (ADR-0010). Never as a header.
    - Normal flush: `fetch` with gzip via `CompressionStream` when available
      (`Content-Encoding: gzip`), plain JSON otherwise.
    - Page hide (`visibilitychange` → hidden, `pagehide`): `navigator.sendBeacon` with
      `text/plain`, uncompressed (beacon can't set headers). **Beacon and `keepalive`
      bodies are capped at ~64 KB by browsers**: send the most recent events first, split,
      and drop the replay tail if needed; count what was dropped.
    - Retry with exponential backoff + jitter on network errors, `429` (honour
      `Retry-After`) and `503`; never retry `400/401/403/413`. After `401`/`403`, stop the
      SDK for this page (bad key or origin) and log once in debug mode.
    - Bounded in-memory queue (e.g. 2 MB); drop oldest replay data first when full.

3.8 **Do no harm**
    - Every public method and every listener wrapped so nothing throws into the host.
    - No work on the main thread longer than ~50 ms attributable to the SDK (batch
      serialisation/compression off the hot path; use `requestIdleCallback` where possible).
    - Document the CSP requirement (`connect-src <collectorUrl>`) in `sdk/README.md`.

3.9 **Shared contract fixtures**
    `contracts/fixtures/*.json`: one valid `EventBatch` and `ReplayBatch` produced by the
    SDK. A Java test in collector-service deserialises and validates them; a Vitest test
    asserts the SDK output matches them. This is how Java and TS stay in sync.

3.10 **Demo site** `examples/demo-site/` (Vite, plain HTML/TS): a login form with password,
    a checkout form with a card-number field, buttons (one that throws, one that logs
    `console.error`), SPA-style navigation, and a `data-si-block` element. Uses the local
    SDK build; reads `VITE_SI_SITE_KEY` / `VITE_SI_COLLECTOR_URL` from `.env.local`.

3.11 **CI**: new `sdk` job (setup-node from `.nvmrc`, `npm ci`, lint, unit tests, build,
    size-limit). Playwright e2e stays local-only for now (needs the full stack).

## Tests
Unit (Vitest/jsdom):
- `init` twice, methods before `init`, and a throwing listener never throw into the page
- session rotates after 30 min inactivity; `reset()` rotates both ids; storage-blocked fallback
- sampling 0 sends nothing; sampling 1 sends
- transport: `?k=` always present and no key header; gzip path; beacon path is text/plain
  and ≤ 64 KB; retry on 503/429 (Retry-After honoured); no retry on 400/401/403/413;
  stop after 401/403
- queue bound drops oldest replay first
- console/exception wrappers call the originals; SDK internal errors are not captured

E2E (Playwright, local, full stack: compose + collector running, dev key from the script):
- open the demo site, type into password, card and a normal input, click, navigate, trigger
  the error buttons, close the page
- consume `telemetry.events.v1` and `replay.chunks.v1` for that session id and assert:
  events arrived (click, navigation, console error, exception); **the typed password and
  card number appear nowhere** in any record; the `data-si-block` content is absent;
  the normal input's value is masked
- paste the consumer output summary in the PR

## Acceptance criteria
`npm test`, `npm run build`, size check and `mvn verify` green · e2e privacy test passes
locally (output in PR) · bundle size reported against the 60 KB gz budget (report honestly;
if over, explain what dominates and propose options — don't game the measurement) ·
PR opened with the template, then stop.

## Do not
Build identify/track logic, server-side signal detection, the dashboard, or any consumer.
Add runtime dependencies beyond rrweb. Send the site key in a header. Record password or
card values under any configuration.
