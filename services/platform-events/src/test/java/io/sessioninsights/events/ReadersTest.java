package io.sessioninsights.events;

import com.clickhouse.client.api.Client;
import io.sessioninsights.db.ClickHouseMigrator;
import io.sessioninsights.db.testing.TestContainers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The shared read side against real ClickHouse (task 5.5). */
@Testcontainers
class ReadersTest {

    @Container
    static final GenericContainer<?> clickhouse = TestContainers.clickhouse();

    static Client client;
    static EventReader events;
    static ManifestReader manifest;

    @BeforeAll
    static void migrate() {
        client = TestContainers.clickhouseClient(clickhouse);
        new ClickHouseMigrator(client).migrate();
        events = new EventReader(client);
        manifest = new ManifestReader(client);
    }

    @AfterAll
    static void close() {
        client.close();
    }

    /** Recent enough for the events TTL (30 days after ts); a fixed date would expire. */
    private static final long RECENT = System.currentTimeMillis() - 86_400_000L;

    @Test
    void pagesThroughASessionInOrderAndStaysStableWhileEventsArrive() {
        UUID tenant = UUID.randomUUID();
        UUID session = UUID.randomUUID();
        long t0 = RECENT;
        for (int i = 0; i < 25; i++) {
            insertEvent(tenant, session, t0 + (i / 2) * 1000L);   // pairs share a timestamp: event_id breaks ties
        }

        List<EventRow> seen = new ArrayList<>();
        EventCursor cursor = null;
        List<EventRow> page;
        int pages = 0;
        do {
            page = events.page(tenant, session, cursor, 10);
            seen.addAll(page);
            if (!page.isEmpty()) {
                cursor = EventCursor.decode(EventCursor.after(page.getLast()).encode());
            }
            if (pages++ == 0) {
                insertEvent(tenant, session, t0 - 60_000);   // earlier than the cursor: never returned later
                insertEvent(tenant, session, t0 + 999_000);  // later: returned in order
            }
        } while (page.size() == 10);

        assertThat(seen).hasSize(26);
        assertThat(seen).extracting(EventRow::eventId).doesNotHaveDuplicates();
        // ordered by ts; ties by event_id in ClickHouse's UUID order (not Java's UUID.compareTo),
        // which the keyset uses too: completeness and no duplicates above are the guarantee
        for (int i = 1; i < seen.size(); i++) {
            assertThat(seen.get(i - 1).ts()).as("ts order at %d", i).isBeforeOrEqualTo(seen.get(i).ts());
        }
        assertThat(seen.getLast().ts()).isEqualTo(Instant.ofEpochMilli(t0 + 999_000));
    }

    @Test
    void everyReadIsScopedToTheTenant() {
        UUID session = UUID.randomUUID();
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        insertEvent(tenantA, session, RECENT);
        insertEvent(tenantB, session, RECENT);
        insertChunk(tenantB, session, 0);

        assertThat(events.page(tenantA, session, null, 100)).singleElement()
                .satisfies(r -> assertThat(r.tenantId()).isEqualTo(tenantA));
        assertThat(manifest.findChunks(tenantA, session)).isEmpty();
        assertThat(manifest.findChunk(tenantA, session, 0)).isEmpty();
        assertThat(manifest.findChunk(tenantB, session, 0)).hasValueSatisfying(m -> {
            assertThat(m.objectKey()).isEqualTo(ReplayObjects.objectKey(tenantB, session, 0));
            assertThat(m.hasFullSnapshot()).isTrue();
        });
        assertThat(manifest.findChunk(tenantB, session, 1)).isEmpty();
    }

    @Test
    void cursorsRoundTripAndRejectGarbage() {
        EventCursor cursor = new EventCursor(Instant.ofEpochMilli(1_790_000_000_123L), UUID.randomUUID());
        assertThat(EventCursor.decode(cursor.encode())).isEqualTo(cursor);
        assertThat(cursor.encode()).matches("[A-Za-z0-9_-]+");
        for (String garbage : new String[] {"", "!!", "bm90LWEtY3Vyc29y", "MTIzOm5vdC1hLXV1aWQ"}) {
            assertThatThrownBy(() -> EventCursor.decode(garbage)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    private static void insertEvent(UUID tenant, UUID session, long tsMillis) {
        exec("""
                INSERT INTO events (event_id, tenant_id, site_id, session_id, anonymous_id, ts, ingested_at, event_type, props)
                VALUES (generateUUIDv4(), {t:UUID}, generateUUIDv4(), {s:UUID}, 'anon',
                        fromUnixTimestamp64Milli({ts:Int64}), now64(3), 'CLICK', '{}')""",
                Map.of("t", tenant, "s", session, "ts", tsMillis));
    }

    private static void insertChunk(UUID tenant, UUID session, int seq) {
        exec("""
                INSERT INTO replay_chunks (tenant_id, site_id, session_id, chunk_seq, object_key, compressed_bytes,
                                           event_count, first_ts, last_ts, has_full_snapshot, ingested_at)
                VALUES ({t:UUID}, generateUUIDv4(), {s:UUID}, {seq:UInt32}, {key:String}, 10, 2, now64(3), now64(3), 1, now64(3))""",
                Map.of("t", tenant, "s", session, "seq", seq, "key", ReplayObjects.objectKey(tenant, session, seq)));
    }

    private static void exec(String sql, Map<String, Object> params) {
        try (var ignored = client.execute(sql, params).get()) {
            // no result
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
