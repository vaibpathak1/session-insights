# Product Features

Status: **Approved v1 scope** · 2026-09

## Positioning

Session Insights is an open-source, **self-hosted, privacy-first** session replay platform
with **local AI triage** and **human review**. Data never leaves the operator's
infrastructure, which matters for regulated industries (banking, telecom, healthcare,
government) under India's DPDP Act and GDPR.

Differentiators, in priority order:
1. Self-hosted, masking by default, erasure built in
2. Local LLM (Ollama) summaries: no data sent to third parties
3. Human-in-the-loop review of AI findings
4. (v2) Session → ITSM ticket: a friction session becomes an incident with the replay attached

## Personas

| Persona | Primary features |
|---|---|
| Developer / site owner | F1, F2, F8 |
| Product analyst | F3, F4, F5, F6 |
| Support / QA reviewer | F4, F6, F7 |
| Platform admin | F8 |
| Recorded end user | F1 (masking), F8 (erasure) |

---

## v1 features (MVP)

### F1 — SDK install and masked recording
**Story:** As a developer, I add one script tag (or npm package) and my site starts
recording sessions, without exposing private data.

Acceptance criteria
- Install via `<script>` snippet or `npm i @session-insights/sdk`; init with site key + collector URL
- Records DOM snapshot + mutations (rrweb), clicks, navigation (incl. SPA route changes), console errors, unhandled exceptions
- All inputs masked by default; password/card fields can never be unmasked; `data-si-block` elements not recorded; `data-si-unmask` opt-in per element
- Batches sent every ~5 s and on page hide (`sendBeacon` / `fetch keepalive`)
- Fails silently if the collector is unreachable; never throws into the host page
- Sampling rate configurable (e.g. record 25 % of sessions)
- Size budget < 60 KB gzipped

Out of scope (v1): network request capture, canvas/video recording, mobile SDKs.

### F2 — User identify API
**Story:** As a developer, I link a recorded session to my app's logged-in user so
analysts can find all sessions of a customer.

Acceptance criteria
- `SessionInsights.identify(userId, { email?, name?, plan?, ...custom })`
- Sessions start anonymous (`anonymousId` persisted in first-party storage); `identify` links the current and later sessions to the user
- Traits are redacted server-side per masking rules (e.g. email can be hashed per site setting)
- `SessionInsights.reset()` on logout starts a new anonymous identity

### F3 — Session list and filters
**Story:** As an analyst, I find the sessions that matter quickly.

Acceptance criteria
- List shows: user (or anonymous), start time, duration, pages, entry URL, platform, friction score, badges (error, rage click, AI flagged)
- Filters: date range, URL contains/visited, user id / trait, duration range, has error, has rage click, has dead click, friction score ≥ X, analysis status
- Sort by time or friction score; paginated; results in < 2 s for 30 days of data on the single-node reference setup
- Filter state is shareable via URL

### F4 — Replay player with timeline
**Story:** As an analyst or reviewer, I watch exactly what the user experienced.

Acceptance criteria
- Play / pause / seek / speed (1×, 2×, 4×) / skip inactivity
- Timeline markers for page changes, clicks, errors, rage/dead clicks; clicking a marker seeks to it
- Side panel: event list and console errors, synced with playback
- Replay of a live (still active) session works with a short delay
- Masked content stays masked in playback

### F5 — Frustration signals
**Story:** As an analyst, the system tells me which sessions went badly, without AI.

Acceptance criteria
- Detected per session: **rage click** (≥ 3 clicks on same element within 1 s), **dead click** (click with no DOM change / navigation within 1 s), **error click** (click followed by JS error within 1 s), **error burst**, **long session on one page with repeated actions**
- Thresholds configurable per site
- Friction score 0–100 from weighted signals, formula documented
- Sessions above a configurable threshold are marked for AI analysis (≤ 5 % target)

### F6 — AI session summary (local LLM)
**Story:** As an analyst, I read a short explanation of what went wrong instead of
watching a 20-minute replay.

Acceptance criteria
- For flagged sessions only: 3–5 line summary, probable cause, affected page/flow, auto tags (e.g. `checkout`, `payment-failure`)
- Runs on Ollama by default; OpenAI/Anthropic only if enabled per tenant
- LLM input is a redacted, compact summary of events and signals, never raw DOM
- Each insight stores model provider + name; summary links to timestamps in the replay
- Guardrail rules can force `REVIEW_REQUIRED` regardless of the LLM output
- If the LLM is down, the session stays `PENDING`/`FAILED` and is retried; nothing is lost

### F7 — Review queue (human-in-the-loop)
**Story:** As a reviewer, I work through sessions the AI or rules couldn't settle.

Acceptance criteria
- Queue of `REVIEW_REQUIRED` sessions, oldest first, with summary, signals and replay link
- Actions: approve (optionally editing summary/tags), reject with reason, add notes, assign to me
- Only legal status transitions; concurrent edits rejected with a clear message
- Every action recorded in an audit trail (who, when, before/after)
- Queue age and throughput visible (basic counters)

### F8 — Admin and privacy
**Story:** As an admin, I set up sites and control what is recorded and kept.

Acceptance criteria
- Manage sites: name, allowed origins, site keys (create, revoke; key shown once)
- Masking rules per site: CSS selector → mask / unmask / block
- Retention per tenant: events, replays, insights (defaults: 30 d / 30 d / 13 months)
- Sampling rate per site
- **Erasure request** by end-user id or session id: removes data from PostgreSQL, ClickHouse and object storage; status tracked until completed
- Dashboard login: roles ADMIN, ANALYST, VIEWER (dev login in early milestones; OIDC before v1.0)

---

## v2 features (planned; v1 schema leaves room for them)

| ID | Feature | Summary |
|---|---|---|
| F9 | Session → ITSM ticket | Create Jira / ServiceNow / generic-webhook ticket from a session or review item, replay link and AI summary attached; link visible both ways |
| F10 | Alerts | Slack / email / webhook when a signal rate crosses a threshold (e.g. rage clicks on /checkout 3× baseline) |
| F11 | Funnels | Define steps (URL or event); conversion and drop-off per step; open replays of drop-offs |
| F12 | Click maps | Click density per page and element |
| F13 | Network capture | Failed/slow XHR/fetch in the replay timeline (URL, method, status, duration; bodies off by default) |
| F14 | Similar sessions | "Show sessions like this one" via insight embeddings (pgvector) |
| F15 | Custom events | `SessionInsights.track(name, props)`; filterable and usable in funnels |

## v3 and later
User journeys, saved segments, custom dashboards, SSO/SCIM provisioning, mobile SDKs,
data export (S3/warehouse), multi-region.

## Explicitly not planned
A/B testing, feature flags, product surveys, billing.
