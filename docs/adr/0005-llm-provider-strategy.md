# ADR-0005: Ollama by default, signal-gated analysis, guardrails over tools

**Status:** Accepted · 2026-09

## Context
The project must run free, fully local. LLM inference is the slowest, costliest step,
and session data is untrusted input.

## Decision
1. **Ollama is the default provider** (`qwen2.5:7b` chat with tool calling,
   `nomic-embed-text` 768-dim embeddings). OpenAI and Anthropic are optional, off by
   default, enabled per tenant.
2. Spring AI auto-configured `ChatClient.Builder` is disabled
   (`spring.ai.chat.client.enabled=false`); one `ChatClient` per provider is built
   explicitly, and a `ModelRouter` picks one per request. Each provider is wrapped in a
   Resilience4j circuit breaker with fallback order configurable.
3. **Gate before the LLM:** deterministic signals (Phase 6) decide which sessions are
   analysed. Target ≤ 5 % of sessions.
4. **Compact, redacted input:** the LLM receives a structured session summary, never raw
   DOM mutations.
5. **Guardrails decide escalation, the LLM advises.** Rules in code (e.g. payment error +
   ≥ 3 rage clicks) force `REVIEW_REQUIRED`. The `flagForHumanReview` tool is an extra
   signal, never the only path.
6. Tools are read-only or idempotent. Session content is delimited as untrusted data.
7. **One embedding model per vector column.** Dimension is fixed in the migration.
   Changing model = new column + backfill, never mixing vectors.

## Local development
On macOS, Ollama runs natively (Metal GPU) rather than in Docker, which has no GPU
access there. Linux and CI use the `ollama-docker` compose profile. Services read
`OLLAMA_BASE_URL`.

## Consequences
The original brief's 1536-dim OpenAI embeddings are replaced by 768 dims.
Small local models call tools less reliably; `scripts/verify-stack.sh` checks this on
day 1, and the agent loop must tolerate text-only replies.
