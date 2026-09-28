package io.sessioninsights.processor;

import io.sessioninsights.common.Topics;
import io.sessioninsights.common.wire.TelemetryEnvelope;
import io.sessioninsights.common.wire.WireHeaders;
import io.sessioninsights.common.wire.WireJson;
import io.sessioninsights.processor.Fixtures.Session;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.kafka.support.KafkaHeaders;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static io.sessioninsights.processor.Fixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Record-level failures go to the DLT with a reason; the rest of the batch and the partition keep flowing. */
@ExtendWith(OutputCaptureExtension.class)
class PoisonRecordsTest extends ProcessorIntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(30);

    @Test
    void onlyThePoisonRecordOfABatchIsDeadLetteredWithItsOriginalBytesAndHeaders(CapturedOutput output) {
        Session s = Session.random();
        String secret = "poison-secret-" + s.sessionId();
        TelemetryEnvelope noTenant = new TelemetryEnvelope(1, null, s.siteId(), s.sessionId(), "anon", "0.1.0", NOW,
                Fixtures.click(NOW.toEpochMilli(), secret, null));
        List<ProducerRecord<String, byte[]>> batch = List.of(
                Fixtures.eventRecord(s, Fixtures.click(NOW.toEpochMilli(), "ok-1", null)),
                Fixtures.eventRecord(s, noTenant),
                Fixtures.eventRecord(s, Fixtures.click(NOW.toEpochMilli() + 1, "ok-2", null)));
        List<RecordMetadata> sent = ProcessorTestInfra.send(batch);

        await().atMost(WAIT).until(() -> finalEventRows(s.tenantId(), s.sessionId()) == 2);
        ConsumerRecord<String, byte[]> dead = singleDeadLetter(Topics.TELEMETRY_EVENTS_DLT, s);

        assertThat(reason(dead)).isEqualTo("missing_tenant");
        assertThat(dead.key()).isEqualTo(s.key());
        assertThat(dead.value()).isEqualTo(batch.get(1).value());
        assertThat(header(dead, WireHeaders.TENANT_ID)).isEqualTo(s.tenantId().toString());
        assertThat(header(dead, KafkaHeaders.DLT_ORIGINAL_TOPIC)).isEqualTo(Topics.TELEMETRY_EVENTS);
        assertThat(ByteBuffer.wrap(dead.headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_PARTITION).value()).getInt())
                .isEqualTo(sent.get(1).partition());
        assertThat(ByteBuffer.wrap(dead.headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_OFFSET).value()).getLong())
                .isEqualTo(sent.get(1).offset());
        assertThat(dead.headers().lastHeader(KafkaHeaders.DLT_EXCEPTION_MESSAGE)).isNull();
        assertThat(dead.headers().lastHeader(KafkaHeaders.DLT_EXCEPTION_STACKTRACE)).isNull();
        assertThat(output.getAll()).contains("reason=missing_tenant").doesNotContain(secret);
    }

    @Test
    void undeserializableBytesAreDeadLetteredAndTheConsumerKeepsGoing() {
        Session s = Session.random();
        ProcessorTestInfra.send(List.of(
                Fixtures.record(Topics.TELEMETRY_EVENTS, s, "{not json".getBytes(StandardCharsets.UTF_8)),
                Fixtures.eventRecord(s, Fixtures.click(NOW.toEpochMilli(), "after", null))));

        await().atMost(WAIT).until(() -> finalEventRows(s.tenantId(), s.sessionId()) == 1);
        assertThat(reason(singleDeadLetter(Topics.TELEMETRY_EVENTS_DLT, s))).isEqualTo("deserialization");
    }

    @Test
    void unknownEventTypeIsDeadLetteredAsBadType() {
        Session s = Session.random();
        String json = new String(WireJson.mapper().writeValueAsBytes(
                Fixtures.envelope(s, Fixtures.click(NOW.toEpochMilli(), "t", null))), StandardCharsets.UTF_8)
                .replace("\"CLICK\"", "\"TELEPORT\"");
        ProcessorTestInfra.send(List.of(Fixtures.record(Topics.TELEMETRY_EVENTS, s, json.getBytes(StandardCharsets.UTF_8))));

        assertThat(reason(singleDeadLetter(Topics.TELEMETRY_EVENTS_DLT, s))).isEqualTo("bad_type");
    }

    @Test
    void recordThatPassesValidationButClickHouseRejectsIsIsolatedAndDoesNotBlockThePartition(CapturedOutput output) {
        Session s = Session.random();
        String secret = "rejected-secret-" + s.sessionId();
        // valid JSON, valid envelope; ClickHouse's JSON type rejects the duplicate path a.b (code 117)
        String duplicatePath = "{\"a\":{\"b\":\"" + secret + "\"},\"a.b\":2}";
        ProcessorTestInfra.send(List.of(
                Fixtures.eventRecord(s, Fixtures.click(NOW.toEpochMilli(), "ok-1", null)),
                Fixtures.eventRecord(s, Fixtures.click(NOW.toEpochMilli() + 1, "ok-2", "{\"k\":1}")),
                Fixtures.eventRecord(s, Fixtures.click(NOW.toEpochMilli() + 2, "bad", duplicatePath)),
                Fixtures.eventRecord(s, Fixtures.click(NOW.toEpochMilli() + 3, "ok-3", null))));

        await().atMost(WAIT).until(() -> finalEventRows(s.tenantId(), s.sessionId()) == 3);
        assertThat(reason(singleDeadLetter(Topics.TELEMETRY_EVENTS_DLT, s))).isEqualTo("store_rejected");

        // same key, same partition: later records still arrive
        ProcessorTestInfra.send(List.of(Fixtures.eventRecord(s, Fixtures.click(NOW.toEpochMilli() + 4, "later", null))));
        await().atMost(WAIT).until(() -> finalEventRows(s.tenantId(), s.sessionId()) == 4);
        assertThat(eventStore.findEvents(s.tenantId(), s.sessionId()))
                .extracting(r -> r.targetText()).containsExactly("ok-1", "ok-2", "ok-3", "later");
        assertThat(output.getAll()).contains("reason=store_rejected").doesNotContain(secret);
    }

    @Test
    void base64ReplayPayloadIsDeadLetteredAsUnsupported() {
        Session s = Session.random();
        var envelope = new io.sessioninsights.common.wire.ReplayEnvelope(1, s.tenantId(), s.siteId(), s.sessionId(), 0,
                NOW, "H4sIAAAAAAAA", null);
        ProcessorTestInfra.send(List.of(Fixtures.chunkRecord(s, envelope)));

        assertThat(reason(singleDeadLetter(Topics.REPLAY_CHUNKS_DLT, s))).isEqualTo("unsupported_payload");
        assertThat(manifestStore.findChunks(s.tenantId(), s.sessionId())).isEmpty();
    }

    private static ConsumerRecord<String, byte[]> singleDeadLetter(String dlt, Session s) {
        List<ConsumerRecord<String, byte[]>> dead = ProcessorTestInfra.records(dlt, s.key(), 1, WAIT);
        assertThat(dead).hasSize(1);
        return dead.getFirst();
    }

    private static String reason(ConsumerRecord<?, ?> dead) {
        return header(dead, WireHeaders.DLT_REASON);
    }

    private static String header(ConsumerRecord<?, ?> record, String name) {
        var header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
