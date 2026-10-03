package io.sessioninsights.api.sessions;

import com.clickhouse.client.api.Client;
import com.github.luben.zstd.Zstd;
import io.sessioninsights.events.ReplayObjects;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Data as the pipeline writes it: PostgreSQL rows, ClickHouse events and manifest, S3 objects. */
final class ApiFixtures {

    record Tenant(UUID id, UUID siteId, String email) {
    }

    private final JdbcTemplate owner;
    private final Client clickhouse;
    private final S3Client s3;
    private final String bucket;

    ApiFixtures(JdbcTemplate owner, Client clickhouse, S3Client s3, String bucket) {
        this.owner = owner;
        this.clickhouse = clickhouse;
        this.s3 = s3;
        this.bucket = bucket;
    }

    Tenant tenant(String name) {
        UUID tenant = UUID.randomUUID();
        UUID site = UUID.randomUUID();
        String email = name + "-" + tenant + "@example.com";
        owner.update("INSERT INTO tenant (id, name) VALUES (?, ?)", tenant, name);
        owner.update("INSERT INTO site (id, tenant_id, name, allowed_origins) VALUES (?, ?, 'site', ARRAY['http://localhost:*'])",
                site, tenant);
        owner.update("INSERT INTO app_user (tenant_id, email, display_name, role) VALUES (?, ?, 'Admin', 'ADMIN')", tenant, email);
        return new Tenant(tenant, site, email);
    }

    /** A session row (closed if {@code endedAt} is set) with a visitor carrying {@code traitsJson}. */
    UUID session(Tenant t, Instant startedAt, Instant endedAt, String entryUrl, int errors, long durationMs,
                 String traitsJson) {
        UUID session = UUID.randomUUID();
        String anonymous = "anon-" + session;
        UUID endUser = owner.queryForObject("INSERT INTO end_user (tenant_id, site_id, anonymous_id, traits) VALUES (?, ?, ?, ?::jsonb)"
                + " RETURNING id", UUID.class, t.id(), t.siteId(), anonymous, traitsJson);
        owner.update("""
                        INSERT INTO user_session (id, tenant_id, site_id, end_user_id, anonymous_id, started_at, last_active_at,
                                                  ended_at, duration_ms, page_count, error_count, entry_url, platform, browser)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 2, ?, ?, 'desktop', 'Chrome 140')""",
                session, t.id(), t.siteId(), endUser, anonymous, Timestamp.from(startedAt),
                Timestamp.from(endedAt == null ? startedAt : endedAt), endedAt == null ? null : Timestamp.from(endedAt),
                durationMs, errors, entryUrl);
        return session;
    }

    void event(Tenant t, UUID session, Instant ts, String type, String targetText) {
        exec("""
                INSERT INTO events (event_id, tenant_id, site_id, session_id, anonymous_id, ts, ingested_at, event_type,
                                    url, path, target_text, props)
                VALUES (generateUUIDv4(), {t:UUID}, {site:UUID}, {s:UUID}, 'anon', fromUnixTimestamp64Milli({ts:Int64}),
                        now64(3), {type:String}, 'http://localhost:5173/cart', '/cart', {text:String}, '{"k":1}')""",
                Map.of("t", t.id(), "site", t.siteId(), "s", session, "ts", ts.toEpochMilli(), "type", type,
                        "text", targetText));
    }

    /** A chunk: manifest row plus the zstd object, exactly as event-processor stores them. */
    void chunk(Tenant t, UUID session, int seq, String eventsJson, boolean fullSnapshot) {
        byte[] compressed = Zstd.compress(eventsJson.getBytes(StandardCharsets.UTF_8), 3);
        s3.putObject(b -> b.bucket(bucket).key(ReplayObjects.objectKey(t.id(), session, seq)).contentType("application/zstd"),
                RequestBody.fromBytes(compressed));
        exec("""
                INSERT INTO replay_chunks (tenant_id, site_id, session_id, chunk_seq, object_key, compressed_bytes,
                                           event_count, first_ts, last_ts, has_full_snapshot, ingested_at)
                VALUES ({t:UUID}, {site:UUID}, {s:UUID}, {seq:UInt32}, {key:String}, {bytes:UInt32}, 2, now64(3), now64(3),
                        {full:UInt8}, now64(3))""",
                Map.of("t", t.id(), "site", t.siteId(), "s", session, "seq", seq,
                        "key", ReplayObjects.objectKey(t.id(), session, seq), "bytes", compressed.length,
                        "full", fullSnapshot ? 1 : 0));
    }

    private void exec(String sql, Map<String, Object> params) {
        try (var ignored = clickhouse.execute(sql, params).get()) {
            // no result
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
