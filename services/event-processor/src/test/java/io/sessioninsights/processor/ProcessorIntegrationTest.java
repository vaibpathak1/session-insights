package io.sessioninsights.processor;

import com.clickhouse.client.api.Client;
import io.sessioninsights.events.EventReader;
import io.sessioninsights.events.ManifestReader;
import io.sessioninsights.events.ReplayObjectReader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.Map;
import java.util.UUID;

/**
 * The whole processor against real Kafka, ClickHouse and SeaweedFS ({@link ProcessorTestInfra}).
 * One Spring context for all subclasses; tests isolate themselves by random tenant/session ids.
 */
@SpringBootTest
public abstract class ProcessorIntegrationTest {

    @Autowired
    protected EventReader eventReader;

    @Autowired
    protected ManifestReader manifestReader;

    @Autowired
    protected ReplayObjectReader objectReader;

    protected static final Client CH = ProcessorTestInfra.CLICKHOUSE_CLIENT;

    @DynamicPropertySource
    static void infra(DynamicPropertyRegistry registry) {
        ProcessorTestInfra.register(registry);
    }

    /** Raw rows for a session (no FINAL), to see duplicates before merges. */
    protected static long rawEventRows(UUID tenantId, UUID sessionId) {
        return CH.queryAll("SELECT count() AS c FROM events WHERE tenant_id = {t:UUID} AND session_id = {s:UUID}",
                Map.of("t", tenantId, "s", sessionId)).getFirst().getLong("c");
    }

    protected static long finalEventRows(UUID tenantId, UUID sessionId) {
        return CH.queryAll("SELECT count() AS c FROM events FINAL WHERE tenant_id = {t:UUID} AND session_id = {s:UUID}",
                Map.of("t", tenantId, "s", sessionId)).getFirst().getLong("c");
    }
}
