# Phase 4b — Edge fixes (small PR)

Branch: `edge-fixes`. Clears follow-ups from Phases 2–4 before M1 continues.
Read ADR-0010, ADR-0011, and the follow-up lists in PRs #3, #4, #5.

## Tasks

4b.1 **ADR-0012 — readable auth errors for browsers** (supersedes only the CORS part of ADR-0010):
    - Preflight (`OPTIONS`) always succeeds: echo the request `Origin`, `Vary: Origin`,
      `Access-Control-Max-Age: 600`, allowed methods/headers. No credentials, never `*`.
      Authorization happens on the actual request, not the preflight.
    - `401` and `403` responses also carry `Access-Control-Allow-Origin: <request origin>`
      and `Vary: Origin`, with the same minimal JSON body as today, so the SDK can read
      the status.
    - Rationale to record: the origin allow-list is enforced on the POST; it was never a
      boundary against non-browser clients; site keys are 32 random bytes, so a readable
      `401` gives no useful enumeration signal; install-time errors become visible.
    - SDK: in browsers, stop immediately on a readable `401`/`403` (keep the
      5-consecutive-failures fallback for network errors). Debug mode logs
      "invalid site key" / "origin not allowed for this site".
    - Tests: collector (preflight for unknown key and disallowed origin succeeds; `401`/`403`
      carry the CORS headers; `202` path unchanged) and SDK (stops on first `401`/`403`).
      Extend the Playwright e2e with a wrong-key run: the SDK stops after one request.

4b.2 **Remove the base64 replay form** from `ReplayBatch`: the collector returns `400` for it;
    contract fixtures and both Java/TS fixture tests updated. The processor keeps its
    `unsupported_payload` DLT path for records already in Kafka.

4b.3 **SDK sensitive-name heuristic → token match**: split `name`/`id` on non-alphanumerics
    and camelCase boundaries; a field is sensitive if a token equals `otp`, `cvv`, `cvc`
    or `cvn`. Tests: `footprint`, `hotpot` not sensitive; `otpCode`, `card_cvv`,
    `user-otp`, `CVC` sensitive. Autocomplete/type rules unchanged.

4b.4 **Large full snapshots**: raise the collector's replay limit to 16 MB **decompressed**
    while guaranteeing the produced Kafka record (zstd-compressed) fits the topic's
    `max.message.bytes` (align producer `max.request.size`); if it can't fit, `413`.
    SDK: allow a single full-snapshot chunk up to the same limit on the normal `fetch`
    path (beacon limits unchanged). Test with a synthetic ~10 MB snapshot through
    collector → Kafka → processor → S3.

## Acceptance criteria
All Java/SDK checks green · e2e (privacy + wrong-key) passes locally · ADR-0012 indexed ·
PR opened with the template, then stop.
