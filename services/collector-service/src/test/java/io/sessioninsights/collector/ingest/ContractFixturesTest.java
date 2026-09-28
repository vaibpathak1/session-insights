package io.sessioninsights.collector.ingest;

import io.sessioninsights.common.wire.EventBatch;
import io.sessioninsights.common.wire.EventType;
import io.sessioninsights.common.wire.ReplayBatch;
import io.sessioninsights.common.wire.TelemetryEvent;
import io.sessioninsights.common.wire.WireJson;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shared contract fixtures (Phase 3, task 3.9). {@code contracts/fixtures/*.json} are produced
 * by the browser SDK (sdk/test/contract.test.ts); here the real validator must accept them in
 * full, and every field the SDK sends must exist in the Java records, so that Java and
 * TypeScript cannot drift apart silently ({@link WireJson} ignores unknown properties).
 */
class ContractFixturesTest {

    /** Surefire runs with the module directory as working directory. */
    private static final Path FIXTURES = Path.of("..", "..", "contracts", "fixtures");

    private final BatchValidator validator = new BatchValidator(BatchValidatorTest.defaults());

    @Test
    void eventBatchFixtureIsAcceptedInFull() throws IOException {
        byte[] body = Files.readAllBytes(FIXTURES.resolve("event-batch.json"));

        var parsed = validator.parseEvents(body);
        EventBatch batch = parsed.batch();
        long latest = batch.events().stream().mapToLong(TelemetryEvent::ts).max().orElseThrow();
        var result = validator.validate(parsed, Instant.ofEpochMilli(latest).plusSeconds(1));

        assertThat(batch.siteKey()).as("the key is sent as ?k=, never in the body (ADR-0010)").isNull();
        assertThat(batch.sessionId()).isNotNull();
        assertThat(validator.anonymousId(batch)).isNotBlank();
        assertThat(validator.sdkVersion(batch)).isNotBlank();
        assertThat(result.dropped()).isZero();
        assertThat(result.accepted()).extracting(TelemetryEvent::type)
                .containsExactly(EventType.NAVIGATION, EventType.CLICK, EventType.CONSOLE_ERROR, EventType.EXCEPTION);
        assertThat(result.accepted()).allSatisfy(e -> {
            assertThat(e.clientEventId()).isNotNull();
            assertThat(e.url()).isNotBlank();
        });
    }

    @Test
    void replayBatchFixtureIsAccepted() throws IOException {
        byte[] body = Files.readAllBytes(FIXTURES.resolve("replay-batch.json"));

        ReplayBatch batch = validator.parseReplay(body);
        validator.validate(batch);   // throws IngestException if invalid

        assertThat(batch.siteKey()).isNull();
        assertThat(batch.chunkSeq()).isZero();
        assertThat(WireJson.mapper().readTree(body).has("payload"))
                .as("raw events only; the base64 payload form was removed (Phase 4b)").isFalse();
        assertThat(batch.events().isArray()).isTrue();
        // chunk 0 starts with rrweb Meta (4) + FullSnapshot (2)
        assertThat(batch.events().get(0).get("type").asInt()).isEqualTo(4);
        assertThat(batch.events().get(1).get("type").asInt()).isEqualTo(2);
    }

    @Test
    void sdkSendsOnlyFieldsTheRecordsDeclare() throws IOException {
        JsonNode events = WireJson.mapper().readTree(Files.readAllBytes(FIXTURES.resolve("event-batch.json")));
        assertThat(fieldNames(events)).isSubsetOf(components(EventBatch.class));
        for (JsonNode event : events.get("events")) {
            assertThat(fieldNames(event)).isSubsetOf(components(TelemetryEvent.class));
        }

        JsonNode replay = WireJson.mapper().readTree(Files.readAllBytes(FIXTURES.resolve("replay-batch.json")));
        assertThat(fieldNames(replay)).isSubsetOf(components(ReplayBatch.class));
    }

    private static Set<String> fieldNames(JsonNode node) {
        return new HashSet<>(node.propertyNames());
    }

    private static Set<String> components(Class<? extends Record> type) {
        return Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName).collect(Collectors.toSet());
    }
}
