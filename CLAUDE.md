# CLAUDE.md — Session Insights

Project memory for Claude Code. Read this fully before any task.

## What this is
Open-source (Apache 2.0), self-hostable session replay + product insights platform
(FullStory-like). Browser SDK records sessions → collector → Kafka → ClickHouse / S3 /
PostgreSQL → signal gating → LLM analysis (Ollama by default) → human review (HITL).

Source of truth, in order:
1. `docs/product/features.md` — user-facing features F1–F15 with acceptance criteria
2. `docs/roadmap.md` — milestones and phases (what is in scope *now*)
3. `docs/requirements.md` — system requirements (FR-*), NFR targets, definition of done
4. `docs/adr/*.md` — accepted decisions. **Never contradict an ADR silently.** If a task
   seems to need a different decision, stop and ask; propose a new ADR.
5. `docs/prompts/phase-XX-*.md` — the current phase's task list and acceptance criteria

## Stack (ADR-0001 … 0009)
- Java 21 LTS, Spring Boot 4.1.x, Spring Framework 7, Spring AI 2.0.x, Hibernate 7
- `jakarta.*` only, never `javax.*`. Jackson 3 (`tools.jackson.*`), not Jackson 2
- Kafka 4 (KRaft) · PostgreSQL 17 + pgvector · ClickHouse · SeaweedFS (S3 API via AWS SDK v2) ·
  Ollama (native on macOS at `OLLAMA_BASE_URL`, Docker profile `ollama-docker` on Linux/CI)
- Dashboard: React + TypeScript + Vite · SDK: framework-free TypeScript
- Maven multi-module under `services/`; group id `io.sessioninsights`

## Non-negotiable rules
- Hot path (event ingestion/persistence) never uses JPA. JPA only for PostgreSQL domain entities.
- No cross-database foreign keys. Link by `tenant_id` + `session_id`.
- Every table, event and object key carries `tenant_id` (ADR-0008).
- Config entities use soft delete (`is_active`, `deleted_at`) via `@SQLDelete` + `@SQLRestriction`.
  Telemetry is never soft-deleted; it expires by TTL.
- Kafka: key = `sessionId`; consumers are idempotent; failures go to `.dlt` topics.
  Topic names only from `platform-common` `Topics` class.
- AI: Ollama is default; external providers are opt-in. Embeddings are 768-dim
  (`nomic-embed-text`). The LLM never sees raw DOM, only redacted summaries.
  Escalation to `REVIEW_REQUIRED` is decided by code guardrails; the LLM tool is advisory.
- Privacy: mask by default; never log request bodies containing telemetry.
- Only Apache-2.0-compatible dependencies (no AGPL / SSPL / BSL).
- Never do blocking I/O inside `synchronized` blocks (virtual-thread pinning on Java 21);
  use `ReentrantLock`. Watch test/run logs for pinned-thread traces.
- Build only what the current phase lists. v2 features (F9–F15) get schema room, not code,
  until their phase.
- Records for DTOs and events; constructor injection; no field injection; no Lombok.
- Never modify a Flyway/ClickHouse migration after it is merged to `main`; add a new version instead.

## Commands
```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)   # macOS; Maven must run on JDK 21
cp .env.example .env              # once
docker compose up -d              # local dependencies
./scripts/verify-stack.sh         # dependency health (must be 0 failures)
mvn -q verify                     # build + all tests
mvn -q -pl services/<module> -am verify   # one module
docker compose down -v            # full reset
```

## How to work in this repo
0. **Git workflow (always):**
   - Start from an up-to-date `main`; work on branch `phase-<N>-<short-name>`
     (docs-only work: `docs-<topic>`). Never commit directly to `main`.
   - One commit per task: `phase-<N>(task-<N.M>): <what>`.
   - When the phase's acceptance criteria pass locally: push the branch and open a PR with
     `gh pr create`, filling `.github/pull_request_template.md` (tasks done, how verified
     with real command output, deviations, follow-ups). Do not merge; the maintainer does.
   - Never push secrets; `.env` is git-ignored — check `git status` before every commit.
1. **Plan first.** For any phase or multi-file change, present a plan (files to create/
   change, tests to add) and wait for approval before writing code.
   Never start implementing a phase until the plan is explicitly approved by the maintainer.
2. **One task at a time.** Finish a task, run its tests, then commit with a message like
   `phase-1(task-1.3): add session_insight table with HNSW index`.
3. **Tests are part of the task.** Integration tests use Testcontainers with the same
   images as `docker-compose.yml`. No mocks for databases or Kafka in integration tests.
4. **Never mark something done without running it.** Paste the relevant test/command output.
5. If a library API differs from what you expect (Boot 4 / Spring AI 2 / Jackson 3 changed
   many APIs), check the actual jar/docs instead of guessing, and note it in the PR summary.
6. Keep `README.md` roadmap status and `docs/requirements.md` in sync when a phase completes.
7. Don't edit ADRs. Don't add dependencies not justified by the current phase.
