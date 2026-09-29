package io.sessioninsights.processor.store;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.sessioninsights.common.wire.WireJson;
import io.sessioninsights.events.EventReader;
import io.sessioninsights.events.EventRow;
import io.sessioninsights.events.ManifestReader;
import io.sessioninsights.events.ManifestRow;
import io.sessioninsights.events.ReplayObjects;
import io.sessioninsights.processor.ProcessorMetrics;
import io.sessioninsights.processor.ProcessorTestInfra;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The ClickHouse stores on their own (no Kafka, no Spring). */
class ClickHouseStoresTest {

    private static final Instant T = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    private final ClickHouseInserter inserter = new ClickHouseInserter(ProcessorTestInfra.CLICKHOUSE_CLIENT,
            WireJson.mapper(), Duration.ofSeconds(10), new ProcessorMetrics(new SimpleMeterRegistry()));
    private final EventStore events = new ClickHouseEventStore(inserter);
    private final ReplayManifestStore manifest = new ClickHouseReplayManifestStore(inserter);
    // read side lives in platform-events; tenant scoping is asserted through it
    private final EventReader eventReader = new EventReader(ProcessorTestInfra.CLICKHOUSE_CLIENT);
    private final ManifestReader manifestReader = new ManifestReader(ProcessorTestInfra.CLICKHOUSE_CLIENT);

    @Test
    void retriedInsertWithTheSameTokenIsWrittenOnce() {
        UUID tenant = UUID.randomUUID();
        UUID session = UUID.randomUUID();
        List<EventRow> rows = List.of(row(tenant, session, "{}"), row(tenant, session, "{}"));
        String token = "test:" + UUID.randomUUID();

        events.insert(rows, token);
        events.insert(rows, token);   // e.g. the ack of the first insert was lost

        assertThat(ProcessorTestInfra.CLICKHOUSE_CLIENT.queryAll(
                "SELECT count() AS c FROM events WHERE tenant_id = {t:UUID}", Map.of("t", tenant))
                .getFirst().getLong("c")).isEqualTo(2);
    }

    @Test
    void readHelpersAreScopedToTheTenant() {
        UUID session = UUID.randomUUID();
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        events.insert(List.of(row(tenantA, session, "{}"), row(tenantB, session, "{}"), row(tenantB, session, "{}")),
                "test:" + UUID.randomUUID());
        manifest.insert(List.of(chunk(tenantA, session, 0), chunk(tenantB, session, 0), chunk(tenantB, session, 1)),
                "test:" + UUID.randomUUID());

        assertThat(eventReader.findEvents(tenantA, session)).hasSize(1).allMatch(r -> r.tenantId().equals(tenantA));
        assertThat(eventReader.findEvents(tenantB, session)).hasSize(2).allMatch(r -> r.tenantId().equals(tenantB));
        assertThat(manifestReader.findChunks(tenantA, session)).hasSize(1).allMatch(r -> r.tenantId().equals(tenantA));
        assertThat(manifestReader.findChunks(tenantB, session)).extracting(ManifestRow::chunkSeq).containsExactly(0, 1);
        assertThat(eventReader.findEvents(UUID.randomUUID(), session)).isEmpty();
    }

    @Test
    void dataTheServerRejectsIsStoreRejectedWithoutTheMessage() {
        UUID tenant = UUID.randomUUID();
        String secret = "secret-" + tenant;
        EventRow bad = row(tenant, UUID.randomUUID(), "{\"a\":{\"b\":\"" + secret + "\"},\"a.b\":2}");

        assertThatThrownBy(() -> events.insert(List.of(row(tenant, bad.sessionId(), "{}"), bad), "test:" + tenant))
                .isInstanceOfSatisfying(StoreRejectedException.class, e -> assertThat(e.errorCode()).isEqualTo(117))
                .hasNoCause()
                .message().doesNotContain(secret);
        assertThat(eventReader.findEvents(tenant, bad.sessionId())).as("all or nothing").isEmpty();
    }

    private static EventRow row(UUID tenant, UUID session, String props) {
        return new EventRow(UUID.randomUUID(), tenant, UUID.randomUUID(), session, "anon", null, T, T, "CLICK",
                null, "http://x/", "/", "t", "a", "text", null, null, WireJson.mapper().readTree(props));
    }

    private static ManifestRow chunk(UUID tenant, UUID session, int seq) {
        return new ManifestRow(tenant, UUID.randomUUID(), session, seq, ReplayObjects.objectKey(tenant, session, seq),
                100, 3, T, T.plusSeconds(1), seq == 0, T);
    }
}
