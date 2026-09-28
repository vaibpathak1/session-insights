package io.sessioninsights.processor;

import io.sessioninsights.common.Topics;
import io.sessioninsights.processor.Fixtures.Session;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static io.sessioninsights.processor.Fixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The central guarantee of Phase 4 (ADR-0011): while a store is down nothing is dead-lettered
 * and nothing is committed; when it is back, every record arrives exactly once (with FINAL).
 */
@ExtendWith(OutputCaptureExtension.class)
class StoreOutageTest extends ProcessorIntegrationTest {

    /** Long enough for several back-off rounds (retry.max-interval is 2 s in tests). */
    private static final long OUTAGE_MILLIS = 8_000;

    @Value("${processor.kafka.events.group-id}")
    String eventsGroup;

    @Value("${processor.kafka.replay.group-id}")
    String replayGroup;

    @Test
    void clickHouseDownThenBack_nothingDeadLetteredOrCommittedThenEverythingOnce(CapturedOutput output) {
        Session s = Session.random();
        String secret = "outage-secret-" + s.sessionId();
        List<ProducerRecord<String, byte[]>> records = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            records.add(Fixtures.eventRecord(s, Fixtures.click(NOW.toEpochMilli() + i, secret, null)));
        }

        ProcessorTestInfra.stop(ProcessorTestInfra.CLICKHOUSE);
        List<RecordMetadata> sent;
        try {
            sent = ProcessorTestInfra.send(records);
            ProcessorTestInfra.sleep(OUTAGE_MILLIS);

            assertThat(ProcessorTestInfra.records(Topics.TELEMETRY_EVENTS_DLT, s.key(), 0, Duration.ofSeconds(1))).isEmpty();
            TopicPartition partition = new TopicPartition(Topics.TELEMETRY_EVENTS, sent.getFirst().partition());
            assertThat(ProcessorTestInfra.committedOffset(eventsGroup, partition)).isLessThanOrEqualTo(sent.getFirst().offset());
        } finally {
            ProcessorTestInfra.start(ProcessorTestInfra.CLICKHOUSE);
        }

        await().atMost(Duration.ofSeconds(60)).until(() -> finalEventRows(s.tenantId(), s.sessionId()) == 200);
        assertThat(CH.queryAll("SELECT uniqExact(event_id) AS u FROM events FINAL WHERE tenant_id = {t:UUID} AND session_id = {s:UUID}",
                Map.of("t", s.tenantId(), "s", s.sessionId())).getFirst().getLong("u")).isEqualTo(200);
        assertThat(ProcessorTestInfra.records(Topics.TELEMETRY_EVENTS_DLT, s.key(), 0, Duration.ofSeconds(1))).isEmpty();
        await().atMost(Duration.ofSeconds(30)).until(() -> ProcessorTestInfra.committedOffset(eventsGroup,
                new TopicPartition(Topics.TELEMETRY_EVENTS, sent.getFirst().partition())) > sent.getLast().offset());

        String log = output.getAll();
        assertThat(count(log, "clickhouse unavailable")).as("one warning per outage").isEqualTo(1);
        assertThat(log).contains("clickhouse available again").doesNotContain(secret);
    }

    @Test
    void objectStorageDownThenBack_nothingDeadLetteredOrCommittedThenEveryChunkOnce(CapturedOutput output) {
        Session s = Session.random();
        List<ProducerRecord<String, byte[]>> records = new ArrayList<>();
        for (int seq = 0; seq < 5; seq++) {
            records.add(Fixtures.chunkRecord(s, Fixtures.chunk(s, seq,
                    Fixtures.rrwebEvents(NOW.toEpochMilli() + seq * 100L, 3, seq == 0, "s3-outage"))));
        }

        ProcessorTestInfra.stop(ProcessorTestInfra.SEAWEEDFS);
        List<RecordMetadata> sent;
        try {
            sent = ProcessorTestInfra.send(records);
            ProcessorTestInfra.sleep(OUTAGE_MILLIS);

            assertThat(ProcessorTestInfra.records(Topics.REPLAY_CHUNKS_DLT, s.key(), 0, Duration.ofSeconds(1))).isEmpty();
            assertThat(manifestStore.findChunks(s.tenantId(), s.sessionId())).as("no manifest row without its object").isEmpty();
            TopicPartition partition = new TopicPartition(Topics.REPLAY_CHUNKS, sent.getFirst().partition());
            assertThat(ProcessorTestInfra.committedOffset(replayGroup, partition)).isLessThanOrEqualTo(sent.getFirst().offset());
        } finally {
            ProcessorTestInfra.start(ProcessorTestInfra.SEAWEEDFS);
        }

        await().atMost(Duration.ofSeconds(60)).until(() -> manifestStore.findChunks(s.tenantId(), s.sessionId()).size() == 5);
        assertThat(manifestStore.findChunks(s.tenantId(), s.sessionId()))
                .extracting(m -> m.chunkSeq()).containsExactly(0, 1, 2, 3, 4);
        for (int seq = 0; seq < 5; seq++) {
            assertThat(objectStore.get(s.tenantId(), s.sessionId(), seq)).isNotNull();
        }
        assertThat(ProcessorTestInfra.records(Topics.REPLAY_CHUNKS_DLT, s.key(), 0, Duration.ofSeconds(1))).isEmpty();
        assertThat(count(output.getAll(), "s3 unavailable")).as("one warning per outage").isEqualTo(1);
        assertThat(output.getAll()).contains("s3 available again");
    }

    private static int count(String text, String needle) {
        return (int) Pattern.compile(Pattern.quote(needle)).matcher(text).results().count();
    }
}

