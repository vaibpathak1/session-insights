# Phase 0 — Make the foundation actually run

Branch: `phase-0-verify`. The scaffold was written without being executed; image tags and
versions are candidates. Run everything on this machine, fix what breaks, leave it verified.

## Tasks
0.1 **Prerequisites.** `export JAVA_HOME=$(/usr/libexec/java_home -v 21)`; confirm `mvn -v`
    shows Java 21 (not a newer JDK). Report Docker/Compose, JDK, Maven versions and Docker
    memory. Confirm native Ollama answers at `http://localhost:11434` (the maintainer
    installs and runs it; do not install it yourself).
0.2 **Stack.** `cp .env.example .env` if missing; `docker compose up -d`. `kafka-init` and
    `storage-init` must exit 0. Fix failures (SeaweedFS flags/healthcheck, Kafka timing).
    Confirm each pinned image tag exists with `docker pull`; if one doesn't, pick the
    nearest existing release and note it.
0.3 **verify-stack.sh → 0 failures.** If the Ollama tool-calling check fails, try one
    alternative tool-capable model, report both results and ask which to keep.
    Do not change the embedding model (ADR-0005).
0.4 **Build.** Confirm Boot 4.1.1 and Spring AI 2.0.1 are the latest patches on Maven
    Central (bump if newer). `mvn verify` → BUILD SUCCESS; fix Boot 4 issues against real
    artifacts, not guesses.
0.5 **Clean re-run.** `docker compose down -v && docker compose up -d`, wait for init
    containers, `verify-stack.sh` → 0 failures; `docker compose config -q` passes.
0.6 README roadmap: M0 → ✅. Push branch, open PR (template), stop.

## Acceptance criteria
Clean-state `docker compose up -d` needs no manual steps (other than native Ollama running) ·
`verify-stack.sh` 0 failures · `mvn verify` green on JDK 21 · no `:latest` tags.

## Do not
Add application features, change ADR decisions, or add services.
