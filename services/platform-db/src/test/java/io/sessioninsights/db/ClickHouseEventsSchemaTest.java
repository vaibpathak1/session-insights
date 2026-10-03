package io.sessioninsights.db;

import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.query.GenericRecord;
import io.sessioninsights.db.testing.TestContainers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class ClickHouseEventsSchemaTest {

    @Container
    static final GenericContainer<?> clickhouse = TestContainers.clickhouse();

    static Client client;

    @BeforeAll
    static void migrate() {
        client = TestContainers.clickhouseClient(clickhouse);
        assertThat(new ClickHouseMigrator(client).migrate()).isEqualTo(2);
        assertThat(new ClickHouseMigrator(client).migrate()).isZero();
    }

    @AfterAll
    static void close() {
        client.close();
    }

    @Test
    void tableUsesExpectedEngineKeysAndTtl() {
        GenericRecord table = client.queryAll(
                "SELECT engine_full, sorting_key, partition_key FROM system.tables"
                        + " WHERE database = currentDatabase() AND name = 'events'").getFirst();
        assertThat(table.getString("engine_full")).startsWith("ReplacingMergeTree(ingested_at)")
                .contains("TTL ts + toIntervalDay(30)");
        assertThat(table.getString("sorting_key")).isEqualTo("tenant_id, session_id, ts, event_id");
        assertThat(table.getString("partition_key")).isEqualTo("toYYYYMMDD(ts)");
        assertThat(client.queryAll("SELECT name FROM system.data_skipping_indices"
                        + " WHERE database = currentDatabase() AND table = 'events' ORDER BY name"))
                .extracting(r -> r.getString("name")).containsExactly("idx_event_type", "idx_path");
    }

    @Test
    void sameEventIdInsertedTwiceIsOneRowWithFinal() {
        UUID eventId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        // the same ts for both inserts (it is part of the sorting key), recent enough for the 30-day TTL
        long ts = System.currentTimeMillis() - 86_400_000L;
        String insert = """
                INSERT INTO events (event_id, tenant_id, site_id, session_id, anonymous_id, ts, ingested_at,
                                    event_type, url, path, props)
                VALUES ({e:UUID}, {t:UUID}, generateUUIDv4(), {s:UUID}, 'anon', fromUnixTimestamp64Milli({ts:Int64}),
                        now64(3) + {delay:UInt32}, 'CLICK', 'http://localhost/cart', '/cart', '{"button": "pay"}')""";
        for (int delay : List.of(0, 5)) {
            exec(insert, Map.of("e", eventId, "t", tenantId, "s", sessionId, "delay", delay, "ts", ts));
        }

        String count = "SELECT count() AS c FROM events %s WHERE tenant_id = {t:UUID} AND event_id = {e:UUID}";
        Map<String, Object> params = Map.of("t", tenantId, "e", eventId);
        assertThat(client.queryAll(count.formatted(""), params).getFirst().getLong("c")).isEqualTo(2);
        assertThat(client.queryAll(count.formatted("FINAL"), params).getFirst().getLong("c")).isEqualTo(1);
    }

    @Test
    void retriedInsertWithSameDeduplicationTokenIsWrittenOnce() {
        UUID tenantId = UUID.randomUUID();
        // settings cannot be query parameters; the token is a generated UUID, safe to inline
        String insert = """
                INSERT INTO events (event_id, tenant_id, site_id, session_id, anonymous_id, ts, ingested_at, event_type, props)
                SETTINGS insert_deduplication_token = 'test:%s'
                VALUES (generateUUIDv4(), {t:UUID}, generateUUIDv4(), generateUUIDv4(), 'anon',
                        now64(3) - INTERVAL 1 DAY, now64(3), 'CLICK', '{}')""".formatted(UUID.randomUUID());
        for (int i = 0; i < 2; i++) {
            exec(insert, Map.of("t", tenantId));
        }

        // no FINAL: the second insert was dropped at insert time, not merged away later
        assertThat(client.queryAll("SELECT count() AS c FROM events WHERE tenant_id = {t:UUID}",
                Map.of("t", tenantId)).getFirst().getLong("c")).isEqualTo(1);
    }

    private static void exec(String sql, Map<String, Object> params) {
        try (var ignored = client.execute(sql, params).get()) {
            // no result
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
