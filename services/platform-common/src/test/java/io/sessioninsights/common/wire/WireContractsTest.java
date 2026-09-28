package io.sessioninsights.common.wire;

import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WireContractsTest {

    private static final JsonMapper JSON = WireJson.mapper();
    private static final UUID SESSION = UUID.fromString("0192f5a4-0000-7000-8000-000000000001");
    private static final UUID EVENT = UUID.fromString("0192f5a4-0000-7000-8000-000000000002");

    @Test
    void eventBatchIgnoresUnknownFieldsIncludingClientTenantId() {
        EventBatch batch = JSON.readValue("""
                {"sessionId":"%s","anonymousId":"anon","sdkVersion":"0.1.0","tenantId":"%s","future":{"x":1},
                 "events":[{"clientEventId":"%s","type":"RAGE_CLICK","ts":1790000000000,
                            "targetSelector":"#buy","props":{"n":3},"alsoNew":true}]}
                """.formatted(SESSION, UUID.randomUUID(), EVENT), EventBatch.class);

        assertThat(batch.sessionId()).isEqualTo(SESSION);
        assertThat(batch.siteKey()).isNull();
        assertThat(batch.events()).singleElement().satisfies(e -> {
            assertThat(e.type()).isEqualTo(EventType.RAGE_CLICK);
            assertThat(e.ts()).isEqualTo(1_790_000_000_000L);
            assertThat(e.props().get("n").asInt()).isEqualTo(3);
        });
    }

    @Test
    void unknownEventTypeIsRejected() {
        assertThatThrownBy(() -> JSON.readValue("""
                {"sessionId":"%s","events":[{"clientEventId":"%s","type":"TELEPORT","ts":1}]}
                """.formatted(SESSION, EVENT), EventBatch.class))
                .isInstanceOf(JacksonException.class);
    }

    @Test
    void invalidUuidIsRejectedWithoutEchoingInput() {
        assertThatThrownBy(() -> JSON.readValue("{\"sessionId\":\"secret-not-a-uuid\"}", EventBatch.class))
                .isInstanceOf(JacksonException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("{\"sessionId\""));
    }

    @Test
    void telemetryEnvelopeRoundTripsAndOmitsNulls() {
        TelemetryEvent event = new TelemetryEvent(EVENT, EventType.CLICK, 1L, "https://x.test/a", "/a", null,
                "#b", null, null, null, null, null);
        TelemetryEnvelope envelope = new TelemetryEnvelope(WireHeaders.CURRENT_SCHEMA_VERSION, UUID.randomUUID(),
                UUID.randomUUID(), SESSION, "anon", "0.1.0", Instant.parse("2026-09-27T10:00:00.123Z"), event);

        String json = JSON.writeValueAsString(envelope);

        assertThat(json).contains("\"schemaVersion\":1").contains("\"receivedAt\":\"2026-09-27T10:00:00.123Z\"")
                .doesNotContain("null");
        assertThat(JSON.readValue(json, TelemetryEnvelope.class)).isEqualTo(envelope);
    }

    @Test
    void replayEnvelopeRoundTrips() {
        ReplayEnvelope envelope = new ReplayEnvelope(1, UUID.randomUUID(), UUID.randomUUID(), SESSION, 7,
                Instant.parse("2026-09-27T10:00:00Z"), null, JSON.readTree("[{\"type\":2}]"));

        assertThat(JSON.readValue(JSON.writeValueAsString(envelope), ReplayEnvelope.class)).isEqualTo(envelope);
    }

    @Test
    void toStringNeverContainsKeyOrContent() {
        EventBatch batch = new EventBatch("sk_dev_secret", SESSION, "anon", "0.1.0", java.util.List.of(
                new TelemetryEvent(EVENT, EventType.CLICK, 1L, null, null, null, null, "private text", null, null, null, null)));

        assertThat(batch.toString()).doesNotContain("sk_dev_secret");
        assertThat(batch.events().getFirst().toString()).doesNotContain("private text");
        assertThat(new ReplayBatch("sk_dev_secret", SESSION, 0, null).toString())
                .doesNotContain("sk_dev_secret").doesNotContain("cGF5bG9hZA==");
    }

    /** EventType must list exactly the values documented on ClickHouse events.event_type. */
    @Test
    void eventTypesMatchClickHouseSchema() throws IOException {
        String ddl = Files.readString(repoRoot().resolve(
                "services/platform-db/src/main/resources/db/migration/clickhouse/V1__events.sql"));
        var m = Pattern.compile("-- type: ([A-Z_, \\n-]+?)\\n\\s*event_type").matcher(ddl);
        assertThat(m.find()).isTrue();
        var documented = Arrays.stream(m.group(1).replace("--", "").split(","))
                .map(String::trim).collect(Collectors.toSet());

        assertThat(documented).containsExactlyInAnyOrderElementsOf(
                Arrays.stream(EventType.values()).map(Enum::name).toList());
    }

    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve(".env.example"))) {
            dir = dir.getParent();
        }
        assertThat(dir).as(".env.example above working directory").isNotNull();
        return dir;
    }
}
