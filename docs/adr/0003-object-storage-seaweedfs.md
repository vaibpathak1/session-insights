# ADR-0003: SeaweedFS instead of MinIO

**Status:** Accepted · 2026-09

## Context
MinIO was the default choice for local S3. Its community edition stopped publishing
binaries and images in October 2025, entered maintenance mode in December 2025, the
repository was archived in 2026, and in September 2026 the images disappeared from
Docker Hub. It is also AGPLv3. An Apache-licensed project cannot depend on it.

## Decision
Use **SeaweedFS** (Apache 2.0, S3 API) for local and self-hosted deployments.
Application code talks only to the **S3 API via the AWS SDK v2**, so any S3-compatible
store works in production (AWS S3, GCS interop, Ceph RGW, Cloudflare R2, SeaweedFS).

## Consequences
- Pin a verified SeaweedFS image tag before the first release.
- Test with SeaweedFS in Testcontainers (generic container) to keep CI free.

## Alternatives considered
Garage (AGPLv3), RustFS (Apache 2.0 but young), community MinIO forks (uncertain
long-term maintenance), Ceph (too heavy for a laptop).
