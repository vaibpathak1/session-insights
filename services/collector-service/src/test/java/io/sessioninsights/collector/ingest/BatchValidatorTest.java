package io.sessioninsights.collector.ingest;

import io.sessioninsights.collector.config.CollectorProperties;
import io.sessioninsights.common.wire.ReplayBatch;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static io.sessioninsights.collector.ingest.RequestBodyReaderTest.assertRejected;
import static org.assertj.core.api.Assertions.assertThat;

class BatchValidatorTest {

    static final Instant NOW = Instant.parse("2026-09-27T12:00:00Z");

    final CollectorProperties properties = defaults();
    final BatchValidator validator = new BatchValidator(properties);

    @Test
    void outOfWindowTimestampsAreDroppedNotRejected() {
        long now = NOW.toEpochMilli();
        var batch = validator.parseEvents(batch(
                event(now),
                event(now - Duration.ofHours(24).toMillis() - 1),
                event(now + Duration.ofMinutes(5).toMillis() + 1),
                event(now - Duration.ofHours(23).toMillis())));

        var result = validator.validate(batch, NOW);

        assertThat(result.accepted()).hasSize(2);
        assertThat(result.droppedOutOfWindow()).isEqualTo(2);
        assertThat(result.droppedUnknownType()).isZero();
    }

    @Test
    void unknownEventTypesAreDroppedNotRejected() {
        long now = NOW.toEpochMilli();
        var parsed = validator.parseEvents(batch(
                event(now),
                event(now).replace("CLICK", "TELEPORT"),
                event(now).replace("CLICK", "click"),   // enum names are case-sensitive
                event(now - Duration.ofHours(25).toMillis())));

        var result = validator.validate(parsed, NOW);

        assertThat(parsed.totalEvents()).isEqualTo(4);
        assertThat(result.accepted()).hasSize(1);
        assertThat(result.droppedUnknownType()).isEqualTo(2);
        assertThat(result.droppedOutOfWindow()).isEqualTo(1);
        assertThat(result.dropped()).isEqualTo(3);
    }

    @Test
    void batchOfOnlyUnknownTypesIsAcceptedWithNothingToSend() {
        var parsed = validator.parseEvents(batch(event(NOW.toEpochMilli()).replace("CLICK", "TELEPORT")));

        var result = validator.validate(parsed, NOW);

        assertThat(result.accepted()).isEmpty();
        assertThat(result.droppedUnknownType()).isEqualTo(1);
    }

    @Test
    void structuralProblemsRejectTheBatch() {
        long now = NOW.toEpochMilli();
        String sid = UUID.randomUUID().toString();
        assertRejected(() -> validator.parseEvents(bytes("{\"sessionId\":\"not-a-uuid\",\"events\":[]}")), Rejection.INVALID);
        assertRejected(() -> validator.parseEvents(bytes("{\"sessionId\":\"" + sid
                + "\",\"events\":[{\"clientEventId\":\"x\",\"type\":\"CLICK\",\"ts\":1}]}")), Rejection.INVALID);
        // a missing or non-string type is structural, unlike an unknown one
        assertRejected(() -> validator.parseEvents(bytes("{\"sessionId\":\"" + sid
                + "\",\"events\":[{\"clientEventId\":\"" + UUID.randomUUID() + "\",\"type\":7,\"ts\":1}]}")),
                Rejection.INVALID);
        assertRejected(() -> validator.validate(validator.parseEvents(bytes("{\"sessionId\":\"" + sid
                + "\",\"events\":[{\"clientEventId\":\"" + UUID.randomUUID() + "\",\"ts\":" + now + "}]}")), NOW),
                Rejection.INVALID);
        assertRejected(() -> validator.parseEvents(bytes("{not json")), Rejection.INVALID);
        assertRejected(() -> validator.validate(validator.parseEvents(bytes("{\"sessionId\":\"" + sid + "\",\"events\":[]}")), NOW),
                Rejection.INVALID);
        assertRejected(() -> validator.validate(validator.parseEvents(bytes("{\"events\":[" + event(now) + "]}")), NOW),
                Rejection.INVALID);
        assertRejected(() -> validator.validate(validator.parseEvents(bytes("{\"sessionId\":\"" + sid + "\",\"events\":[{\"clientEventId\":\""
                + UUID.randomUUID() + "\",\"type\":\"CLICK\"}]}")), NOW), Rejection.INVALID);
        assertRejected(() -> validator.validate(validator.parseEvents(bytes("{\"sessionId\":\"" + sid + "\",\"events\":[{\"clientEventId\":\""
                + UUID.randomUUID() + "\",\"type\":\"CLICK\",\"ts\":" + now + ",\"props\":[1]}]}")), NOW), Rejection.INVALID);
    }

    @Test
    void tooManyEventsIsTooLarge() {
        String[] events = IntStream.range(0, 501).mapToObj(i -> event(NOW.toEpochMilli())).toArray(String[]::new);
        var batch = validator.parseEvents(batch(events));

        assertRejected(() -> validator.validate(batch, NOW), Rejection.TOO_LARGE);
    }

    @Test
    void unknownTypesStillCountTowardsTheEventLimit() {
        String[] events = IntStream.range(0, 501).mapToObj(i -> event(NOW.toEpochMilli()).replace("CLICK", "TELEPORT"))
                .toArray(String[]::new);
        var batch = validator.parseEvents(batch(events));

        assertRejected(() -> validator.validate(batch, NOW), Rejection.TOO_LARGE);
    }

    @Test
    void redactsThenTruncates() {
        String longText = "x".repeat(1020) + " jane@example.com";
        var parsed = validator.parseEvents(bytes("""
                {"sessionId":"%s","anonymousId":"%s","events":[{"clientEventId":"%s","type":"ERROR_CLICK","ts":%d,
                 "url":"https://shop.test/pay?email=jane%%40example.com","targetText":"%s",
                 "errorMessage":"card 4111 1111 1111 1111 declined","errorStack":"%s"}]}
                """.formatted(UUID.randomUUID(), "a".repeat(300), UUID.randomUUID(), NOW.toEpochMilli(), longText,
                "s".repeat(10_000))));

        var event = validator.validate(parsed, NOW).accepted().getFirst();

        assertThat(event.url()).isEqualTo("https://shop.test/pay?email=%5Bemail%5D");
        assertThat(event.errorMessage()).isEqualTo("card [card] declined");
        assertThat(event.targetText()).hasSize(1024).doesNotContain("jane").doesNotContain("@");
        assertThat(event.errorStack()).hasSize(8192);
        assertThat(validator.anonymousId(parsed.batch())).hasSize(128);
    }

    @Test
    void truncateKeepsSurrogatePairsWhole() {
        assertThat(BatchValidator.truncate("ab😀", 3)).isEqualTo("ab");
        assertThat(BatchValidator.truncate("abc", 3)).isEqualTo("abc");
    }

    @Test
    void replayNeedsExactlyOneOfPayloadOrEvents() {
        UUID sid = UUID.randomUUID();
        validator.validate(new ReplayBatch(null, sid, 0, "aGVsbG8=", null));
        validator.validate(validator.parseReplay(bytes("{\"sessionId\":\"" + sid + "\",\"chunkSeq\":1,\"events\":[{\"type\":2}]}")));

        assertRejected(() -> validator.validate(new ReplayBatch(null, sid, 0, null, null)), Rejection.INVALID);
        assertRejected(() -> validator.validate(validator.parseReplay(bytes("{\"sessionId\":\"" + sid
                + "\",\"chunkSeq\":1,\"payload\":\"aGVsbG8=\",\"events\":[]}"))), Rejection.INVALID);
        assertRejected(() -> validator.validate(new ReplayBatch(null, sid, 0, "not base64!", null)), Rejection.INVALID);
        assertRejected(() -> validator.validate(new ReplayBatch(null, sid, -1, "aGVsbG8=", null)), Rejection.INVALID);
        assertRejected(() -> validator.validate(validator.parseReplay(bytes("{\"sessionId\":\"" + sid
                + "\",\"chunkSeq\":1,\"events\":{\"type\":2}}"))), Rejection.INVALID);
    }

    static CollectorProperties defaults() {
        return new Binder(new MapConfigurationPropertySource(Map.of())).bindOrCreate("collector", CollectorProperties.class);
    }

    static byte[] batch(String... events) {
        return bytes("{\"sessionId\":\"" + UUID.randomUUID() + "\",\"events\":["
                + java.util.Arrays.stream(events).collect(Collectors.joining(",")) + "]}");
    }

    static String event(long ts) {
        return "{\"clientEventId\":\"" + UUID.randomUUID() + "\",\"type\":\"CLICK\",\"ts\":" + ts + "}";
    }

    static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
