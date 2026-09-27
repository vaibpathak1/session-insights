# ADR-0007: React dashboard, framework-free TypeScript SDK

**Status:** Accepted · 2026-09

## Decision
- **Dashboard:** React + TypeScript + Vite (SPA behind login; no SSR needed),
  TanStack Query for server state, rrweb player for replay.
- **Browser SDK:** plain TypeScript, no framework, because it runs inside customers'
  apps of any stack. Published as an npm package and a `<script>` snippet.

## Alternatives considered
Angular for the dashboard: strong structure and the maintainer's existing expertise.
React chosen for contributor pool, rrweb / analytics ecosystem, and market relevance.
