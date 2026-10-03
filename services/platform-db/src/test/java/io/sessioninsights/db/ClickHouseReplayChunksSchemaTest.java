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
class ClickHouseReplayChunksSchemaTest {

    @Container
    static final GenericContainer<?> clickhouse = TestContainers.clickhouse();

    static Client client;

    @BeforeAll
    static void migrate() {
        client = TestContainers.clickhouseClient(clickhouse);
        assertThat(new ClickHouseMigrator(client).migrate()).isEqualTo(2);
    }

    @AfterAll
    static void close() {
        client.close();
    }

    @Test
    void tableUsesExpectedColumnsEngineKeysTtlAndDedupWindow() {
        assertThat(client.queryAll("SELECT name, type FROM system.columns"
                        + " WHERE database = currentDatabase() AND table = 'replay_chunks' ORDER BY position"))
                .extracting(r -> r.getString("name") + " " + r.getString("type"))
                .containsExactly(
                        "tenant_id UUID", "site_id UUID", "session_id UUID", "chunk_seq UInt32",
                        "object_key String", "compressed_bytes UInt32", "event_count UInt32",
                        "first_ts DateTime64(3)", "last_ts DateTime64(3)", "has_full_snapshot UInt8",
                        "ingested_at DateTime64(3)");

        GenericRecord table = client.queryAll(
                "SELECT engine_full, sorting_key, partition_key FROM system.tables"
                        + " WHERE database = currentDatabase() AND name = 'replay_chunks'").getFirst();
        assertThat(table.getString("engine_full")).startsWith("ReplacingMergeTree(ingested_at)")
                .contains("TTL first_ts + toIntervalDay(30)")
                .contains("non_replicated_deduplication_window = 1000");
        assertThat(table.getString("sorting_key")).isEqualTo("tenant_id, session_id, chunk_seq");
        assertThat(table.getString("partition_key")).isEqualTo("toYYYYMMDD(first_ts)");
    }

    @Test
    void eventsTableGetsDedupWindowWithoutChangingV1() {
        assertThat(client.queryAll("SELECT engine_full FROM system.tables"
                        + " WHERE database = currentDatabase() AND name = 'events'").getFirst().getString("engine_full"))
                .contains("non_replicated_deduplication_window = 1000");
        assertThat(client.queryAll("SELECT version, name FROM schema_migrations ORDER BY version"))
                .extracting(r -> r.getLong("version") + " " + r.getString("name"))
                .containsExactly("1 events", "2 replay_chunks");
    }

    @Test
    void redeliveredChunkIsOneRowWithFinal() {
        UUID tenantId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        String insert = """
                INSERT INTO replay_chunks (tenant_id, site_id, session_id, chunk_seq, object_key, compressed_bytes,
                                           event_count, first_ts, last_ts, has_full_snapshot, ingested_at)
                VALUES ({t:UUID}, generateUUIDv4(), {s:UUID}, 0, 'tenants/t/sessions/s/000000.json.zst', 123,
                        4, now64(3) - INTERVAL 1 DAY, now64(3) - INTERVAL 1 DAY + INTERVAL 5 SECOND,
                        1, now64(3) + {delay:UInt32})""";
        for (int delay : List.of(0, 5)) {
            exec(insert, Map.of("t", tenantId, "s", sessionId, "delay", delay));
        }

        String count = "SELECT count() AS c FROM replay_chunks %s WHERE tenant_id = {t:UUID} AND session_id = {s:UUID}";
        Map<String, Object> params = Map.of("t", tenantId, "s", sessionId);
        assertThat(client.queryAll(count.formatted(""), params).getFirst().getLong("c")).isEqualTo(2);
        assertThat(client.queryAll(count.formatted("FINAL"), params).getFirst().getLong("c")).isEqualTo(1);
    }

    private static void exec(String sql, Map<String, Object> params) {
        try (var ignored = client.execute(sql, params).get()) {
            // no result
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
