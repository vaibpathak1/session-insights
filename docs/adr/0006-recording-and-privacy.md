# ADR-0006: rrweb recording with privacy-by-default masking

**Status:** Accepted · 2026-09

## Decision
- Record with **rrweb** (MIT): full DOM snapshot + incremental mutations, and use its
  player for replay. We do not build our own DOM serialiser.
- SDK derives lightweight structured events (click, rage click, error, navigation)
  and sends them separately from replay chunks (`telemetry.events.v1` vs `replay.chunks.v1`).
- `maskAllInputs: true` by default. Password, card and `data-si-block` elements are
  never recorded. Unmasking is an explicit, per-selector opt-in.
- Server-side redaction runs again before storage and before any LLM call.

## Consequences
Replays show masked text by default, which analysts may find limiting; that is intended.
