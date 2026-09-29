package io.sessioninsights.processor;

import io.micrometer.core.instrument.MeterRegistry;
import io.sessioninsights.common.Topics;
import io.sessioninsights.common.wire.WireHeaders;
import io.sessioninsights.processor.Fixtures.Session;
import io.sessioninsights.processor.ingest.EventsListener;
import io.sessioninsights.processor.sessions.SessionTracker;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.AlterConfigOp;
import org.apache.kafka.clients.admin.ConfigEntry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.ConfigResource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.sessioninsights.processor.Fixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * ADR-0014: a batch's poison records are dead-lettered in one pass, so a batch clears in one
 * round however many records are bad; a failed dead-letter publish is an outage.
 */
@ExtendWith(OutputCaptureExtension.class)
class DeadLetterBatchTest extends ProcessorIntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(60);

    @Autowired
    MeterRegistry registry;

    @Value("${processor.kafka.events.group-id}")
    String eventsGroup;

    @Test
    void aFullyPoisonBatchClearsInOneRound() {
        Session s = Session.random();
        List<ProducerRecord<String, byte[]>> poison = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            poison.add(Fixtures.record(Topics.TELEMETRY_EVENTS, s, ("{not json " + i).getBytes(StandardCharsets.UTF_8)));
        }
        double before = processed(EventsListener.ID);

        RecordMetadata last = ProcessorTestInfra.send(poison).getLast();
        awaitCommitted(eventsGroup, last);

        assertThat(ProcessorTestInfra.records(Topics.TELEMETRY_EVENTS_DLT, s.key(), 300, WAIT)).hasSize(300);
        assertThat(processed(EventsListener.ID) - before).as("each record processed once: no redelivery rounds")
                .isEqualTo(300);
    }

    @Test
    void aMixedBatchClearsInOneRoundForTheWriterAndTheTracker() {
        Session s = Session.random();
        List<ProducerRecord<String, byte[]>> mixed = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            mixed.add(Fixtures.eventRecord(s, Fixtures.envelope(s, Fixtures.click(NOW.toEpochMilli() + i, "ok", null),
                    NOW, null)));
            mixed.add(Fixtures.record(Topics.TELEMETRY_EVENTS, s, ("{bad " + i).getBytes(StandardCharsets.UTF_8)));
            // valid for ClickHouse, rejected by PostgreSQL (NUL byte): poison for the tracker only
            Session nul = s.next();
            mixed.add(Fixtures.eventRecord(nul, Fixtures.envelope(nul,
                    Fixtures.navigation(NOW.toEpochMilli() + i, "http://localhost/x\u0000" + i), NOW, null)));
        }
        double writerBefore = processed(EventsListener.ID);
        double trackerBefore = processed(SessionTracker.ID);

        RecordMetadata last = ProcessorTestInfra.send(mixed).getLast();
        awaitCommitted(eventsGroup, last);
        awaitCommitted("event-processor.sessions", last);

        assertThat(finalEventRows(s.tenantId(), s.sessionId())).isEqualTo(100);
        assertThat(ProcessorTestInfra.records(Topics.TELEMETRY_EVENTS_DLT, s.key(), 100, WAIT))
                .as("invalid envelopes: dead-lettered once, by the events writer").hasSize(100)
                .allSatisfy(r -> assertThat(header(r, WireHeaders.DLT_CONSUMER)).isEqualTo(eventsGroup));
        assertThat(processed(EventsListener.ID) - writerBefore).isEqualTo(300);
        assertThat(processed(SessionTracker.ID) - trackerBefore).isEqualTo(300);
    }

    @Test
    void aFailedDeadLetterPublishIsAnOutage_nothingAcknowledgedNothingLost(CapturedOutput output) throws Exception {
        Session s = Session.random();
        List<ProducerRecord<String, byte[]>> batch = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            batch.add(Fixtures.eventRecord(s, Fixtures.click(NOW.toEpochMilli() + i, "ok", null)));
            batch.add(Fixtures.record(Topics.TELEMETRY_EVENTS, s, ("{bad " + i).getBytes(StandardCharsets.UTF_8)));
        }
        setDltMaxMessageBytes("64");   // every dead-letter publish now fails (RecordTooLargeException)
        List<RecordMetadata> sent;
        try {
            sent = ProcessorTestInfra.send(batch);
            ProcessorTestInfra.sleep(8_000);

            TopicPartition partition = new TopicPartition(Topics.TELEMETRY_EVENTS, sent.getFirst().partition());
            assertThat(ProcessorTestInfra.committedOffset(eventsGroup, partition))
                    .as("nothing acknowledged").isLessThanOrEqualTo(sent.getFirst().offset());
            assertThat(ProcessorTestInfra.records(Topics.TELEMETRY_EVENTS_DLT, s.key(), 0, Duration.ofSeconds(1))).isEmpty();
        } finally {
            setDltMaxMessageBytes("4194304");
        }

        awaitCommitted(eventsGroup, sent.getLast());
        assertThat(finalEventRows(s.tenantId(), s.sessionId())).as("nothing lost, nothing duplicated").isEqualTo(10);
        assertThat(ProcessorTestInfra.records(Topics.TELEMETRY_EVENTS_DLT, s.key(), 10, WAIT)).hasSize(10);
        assertThat(output.getAll()).contains("kafka unavailable (dead-letter publish").contains("kafka available again");
    }

    // ------------------------------------------------------------------ helpers

    private double processed(String listener) {
        var summary = registry.find(ProcessorMetrics.BATCH_SIZE).tag("listener", listener).summary();
        return summary == null ? 0 : summary.totalAmount();
    }

    private static void awaitCommitted(String group, RecordMetadata sent) {
        TopicPartition partition = new TopicPartition(Topics.TELEMETRY_EVENTS, sent.partition());
        await().atMost(WAIT).until(() -> ProcessorTestInfra.committedOffset(group, partition) > sent.offset());
    }

    private static void setDltMaxMessageBytes(String value) throws Exception {
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                ProcessorTestInfra.KAFKA.getBootstrapServers()))) {
            ConfigResource dlt = new ConfigResource(ConfigResource.Type.TOPIC, Topics.TELEMETRY_EVENTS_DLT);
            admin.incrementalAlterConfigs(Map.of(dlt, List.of(new AlterConfigOp(
                    new ConfigEntry("max.message.bytes", value), AlterConfigOp.OpType.SET)))).all().get();
        }
    }

    private static String header(ConsumerRecord<?, ?> record, String name) {
        var header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
