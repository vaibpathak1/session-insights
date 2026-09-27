# ADR-0001: Java 21, Spring Boot 4.1, Spring AI 2.0, virtual threads

**Status:** Accepted · 2026-09

## Context
The original brief specified Spring Boot 3.4. Boot 3.5 (the last 3.x line) reached end of
OSS support on 30 June 2026, and Spring AI 2.0 (GA June 2026) requires Boot 4.x. A new
project must not start on an end-of-life line. Java 21 LTS is the maintainer's and most
target enterprises' standard runtime; Boot 4 and Spring AI 2.0 both support Java 17+.

## Decision
- **Java 21 LTS.**
- **Spring Boot 4.1.x / Spring Framework 7 / Jakarta EE 11 / Hibernate 7.**
  `jakarta.*` imports only.
- **Spring AI 2.0.x** for chat, embeddings, tool calling and the provider abstraction.
- **Jackson 3** (Boot 4 default). Package is `tools.jackson.*`; custom telemetry
  deserializers are written against Jackson 3.
- `spring.threads.virtual.enabled=true` in every service.

## Consequences
- Virtual threads are used for blocking I/O (HTTP handling, ClickHouse/PostgreSQL writes,
  S3 uploads, LLM calls). They do **not** raise Kafka consumer parallelism, which is
  bounded by partition count (ADR-0004).
- **Pinning on Java 21:** a virtual thread blocking inside a `synchronized` block pins its
  carrier thread (fixed only in JDK 24, JEP 491). Mitigations:
  - no blocking I/O inside `synchronized` in our code; use `ReentrantLock`
  - `-Djdk.tracePinnedThreads=short` in local runs and tests (parent pom) and in load tests
  - HikariCP pool sized deliberately; it, not the thread count, bounds DB concurrency
  - the flag is removed in JDK 24+; drop it when moving to the next LTS
- Some tutorials online still show Boot 3 / Jackson 2 APIs; verify against real artifacts.

## Alternatives considered
- Java 25 LTS: removes pinning, but not yet the standard runtime for target users.
- Java 21 + Boot 3.5: rejected, end of life.
- Quarkus / Micronaut: good, but Spring AI is the most complete JVM LLM integration and
  matches the team's skills.
