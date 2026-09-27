-- Phase 1 / task 1.5: structured telemetry events (ADR-0002, ADR-0008).
-- ReplacingMergeTree deduplicates redelivered events by the full sorting key (which ends in
-- the client event_id), keeping the row with the latest ingested_at; read with FINAL.
-- The 30-day TTL is a global ceiling; per-tenant retention is enforced by the retention job.
CREATE TABLE IF NOT EXISTS events
(
    -- identity
    event_id          UUID,
    tenant_id         UUID,
    site_id           UUID,
    session_id        UUID,
    anonymous_id      String,
    end_user_id       Nullable(UUID),

    -- time
    ts                DateTime64(3),
    ingested_at       DateTime64(3),

    -- type: CLICK, DEAD_CLICK, RAGE_CLICK, ERROR_CLICK, NAVIGATION, INPUT, SCROLL,
    --       CONSOLE_ERROR, EXCEPTION, IDENTIFY, CUSTOM, NETWORK
    event_type        LowCardinality(String),
    event_name        LowCardinality(String) DEFAULT '',   -- CUSTOM events (v2, F15)

    -- context (target_text is masked by the SDK and redacted server-side)
    url               String DEFAULT '',
    path              String DEFAULT '',
    page_title        String DEFAULT '',
    target_selector   String DEFAULT '',
    target_text       String DEFAULT '',
    error_message     String DEFAULT '',
    error_stack       String DEFAULT '',

    -- network (v2 hook for F13), empty until then
    http_method       LowCardinality(String) DEFAULT '',
    http_url          String DEFAULT '',
    http_status       UInt16 DEFAULT 0,
    http_duration_ms  UInt32 DEFAULT 0,

    props             JSON,

    INDEX idx_event_type event_type TYPE set(0)            GRANULARITY 4,
    INDEX idx_path       path       TYPE bloom_filter(0.01) GRANULARITY 4
)
ENGINE = ReplacingMergeTree(ingested_at)
PARTITION BY toYYYYMMDD(ts)
ORDER BY (tenant_id, session_id, ts, event_id)
TTL ts + INTERVAL 30 DAY;
