-- Phase 4 / task 4.1: replay chunk manifest (ADR-0002, ADR-0003, ADR-0008).
-- One row per rrweb chunk stored in object storage, so the player lists a session's chunks
-- without an S3 LIST. The processor writes the object first and this row second, so a row
-- never points to a missing object. ReplacingMergeTree collapses redelivered chunks by
-- (tenant_id, session_id, chunk_seq), keeping the latest ingested_at; read with FINAL.
-- Same 30-day global TTL as events; per-tenant retention is enforced by the retention job.
CREATE TABLE IF NOT EXISTS replay_chunks
(
    tenant_id          UUID,
    site_id            UUID,
    session_id         UUID,
    chunk_seq          UInt32,

    -- tenants/{tenant_id}/sessions/{session_id}/{chunk_seq:06d}.json.zst
    object_key         String,
    compressed_bytes   UInt32,
    event_count        UInt32,

    -- rrweb event timestamps (client clock) of the first and last event in the chunk
    first_ts           DateTime64(3),
    last_ts            DateTime64(3),
    has_full_snapshot  UInt8,

    ingested_at        DateTime64(3)
)
ENGINE = ReplacingMergeTree(ingested_at)
PARTITION BY toYYYYMMDD(first_ts)
ORDER BY (tenant_id, session_id, chunk_seq)
TTL first_ts + INTERVAL 30 DAY
SETTINGS non_replicated_deduplication_window = 1000;

-- Insert-time deduplication (insert_deduplication_token) is off for non-replicated
-- MergeTree tables unless this window is set. The processor retries a failed batch with the
-- same token, so an insert that succeeded but whose ack was lost is not written twice.
ALTER TABLE events MODIFY SETTING non_replicated_deduplication_window = 1000;
