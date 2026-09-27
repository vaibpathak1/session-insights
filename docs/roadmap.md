# Roadmap

Built **feature-first**: each milestone ends with something a person can use and demo.
Phases are the unit of work for Claude Code (one prompt per phase in `docs/prompts/`).

| Milestone | Demo at the end | Phases | Features |
|---|---|---|---|
| **M0 Foundation** | `docker compose up` + `verify-stack.sh` green, build green | 0 | — |
| **M1 First replay** | Add SDK to a demo page → session appears in dashboard → replay plays | 1–6 | F1, F4 (basic), F3 (basic), F8 (seeded site/key) |
| **M2 Find the pain** | Filter "rage clicks on /checkout for user X" and jump to the moment in the replay | 7–8 | F2, F3 (full), F4 (timeline), F5 |
| **M3 AI triage** | Flagged session gets a local-LLM summary; reviewer approves it in the queue | 9–10 | F6, F7 |
| **M4 v1.0 release** | Fresh clone → running in 10 min; erasure works; load test meets NFRs | 11–12 | F8 (full), hardening |
| **M5+ v2** | Ticket created from a session with replay attached | 13+ | F9 first, then F10–F15 |

## Phases

| Phase | Scope | Milestone |
|---|---|---|
| 0 | Verify local stack and build (Java 21) | M0 |
| 1 | Schemas: PostgreSQL (v1 + v2 hooks) and ClickHouse; JPA domain layer | M1 |
| 2 | Collector API → Kafka (site key auth, origin allow-list, rate limit) | M1 |
| 3 | Browser SDK: rrweb recording, masking, batching, sampling | M1 |
| 4 | Event processor: ClickHouse writer, replay chunks → S3, dedup, DLT | M1 |
| 5 | Session lifecycle (inactivity close) + replay/session read API | M1 |
| 6 | Dashboard shell: dev login, session list, replay player | M1 |
| 7 | Identify API end-to-end, full filters, timeline markers + event panel | M2 |
| 8 | Frustration signals + friction score + analysis gating | M2 |
| 9 | AI analysis worker: Ollama, router, guardrails, summaries, embeddings | M3 |
| 10 | Review queue API + UI, audit trail, optimistic locking | M3 |
| 11 | Admin UI: sites/keys, masking rules, retention, sampling, erasure; OIDC | M4 |
| 12 | Hardening: observability, load tests vs NFRs, Helm, docs, v1.0 release | M4 |
