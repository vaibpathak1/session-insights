package io.sessioninsights.processor.sessions;

import io.sessioninsights.common.Topics;
import io.sessioninsights.common.wire.TelemetryEvent;
import io.sessioninsights.common.wire.WireHeaders;
import io.sessioninsights.processor.Fixtures;
import io.sessioninsights.processor.Fixtures.Session;
import io.sessioninsights.processor.ProcessorIntegrationTest;
import io.sessioninsights.processor.ProcessorTestInfra;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static io.sessioninsights.processor.Fixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Task 5.3: user_session / end_user from telemetry, idempotent under redelivery and reordering. */
class SessionTrackerTest extends ProcessorIntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(30);
    private final JdbcTemplate db = ProcessorTestInfra.owner();

    @Value("${processor.kafka.sessions.group-id}")
    String sessionsGroup;

    @Test
    void batchBecomesASessionAndAVisitor() {
        Session s = Session.random();
        long t0 = NOW.toEpochMilli() - 60_000;
        sendAll(List.of(
                at(s, Fixtures.navigation(t0, "http://localhost:5173/landing?q=%5Bemail%5D"), 0),
                at(s, Fixtures.click(t0 + 1_000, "Buy", null), 1),
                at(s, Fixtures.navigation(t0 + 2_000, "http://localhost:5173/cart"), 2)));

        Map<String, Object> row = awaitSession(s);
        assertThat(ts(row, "started_at")).isEqualTo(Instant.ofEpochMilli(t0));
        assertThat(ts(row, "last_active_at")).isEqualTo(NOW.plusSeconds(2));   // latest server receive time
        assertThat(row.get("ended_at")).isNull();
        assertThat(row.get("entry_url")).isEqualTo("http://localhost:5173/landing?q=%5Bemail%5D");
        assertThat(ts(row, "entry_at")).isEqualTo(Instant.ofEpochMilli(t0));
        assertThat(row.get("platform")).isEqualTo("desktop");
        assertThat(row.get("browser")).isEqualTo("Chrome 140");
        assertThat(row.get("anonymous_id")).isEqualTo("anon-" + s.sessionId());
        assertThat(row.get("page_count")).as("counters come from ClickHouse at close").isEqualTo(0);

        Map<String, Object> visitor = db.queryForMap("SELECT * FROM end_user WHERE id = ?", row.get("end_user_id"));
        assertThat(visitor.get("tenant_id")).isEqualTo(s.tenantId());
        assertThat(visitor.get("site_id")).isEqualTo(s.siteId());
        assertThat(visitor.get("anonymous_id")).isEqualTo("anon-" + s.sessionId());
    }

    @Test
    void redeliveredBatchLeavesTheRowIdentical() {
        Session s = Session.random();
        List<ProducerRecord<String, byte[]>> batch = List.of(
                at(s, Fixtures.navigation(NOW.toEpochMilli(), "http://localhost:5173/"), 0),
                at(s, Fixtures.click(NOW.toEpochMilli() + 5, "x", null), 3));
        sendAll(batch);
        Map<String, Object> before = awaitSession(s);
        Map<String, Object> visitorBefore = db.queryForMap("SELECT * FROM end_user WHERE id = ?", before.get("end_user_id"));

        RecordMetadata again = ProcessorTestInfra.send(batch.stream()
                .map(r -> new ProducerRecord<>(r.topic(), r.key(), r.value())).toList()).getLast();
        awaitProcessed(again);

        assertThat(session(s)).isEqualTo(before);   // incl. version and updated_at
        assertThat(db.queryForMap("SELECT * FROM end_user WHERE id = ?", before.get("end_user_id"))).isEqualTo(visitorBefore);
    }

    @Test
    void outOfOrderBatchesKeepMinimaMaximaAndTheEarliestEntry() {
        Session s = Session.random();
        long t0 = NOW.toEpochMilli() - 600_000;
        sendAll(List.of(at(s, Fixtures.navigation(t0 + 60_000, "http://localhost:5173/second"), 10)));
        awaitSession(s);
        // an earlier part of the session arrives later (e.g. an offline queue), received earlier
        RecordMetadata late = sendAll(List.of(
                at(s, Fixtures.navigation(t0, "http://localhost:5173/first"), 0),
                at(s, Fixtures.click(t0 + 1, "x", null), 1)));
        awaitProcessed(late);

        Map<String, Object> row = session(s);
        assertThat(ts(row, "started_at")).isEqualTo(Instant.ofEpochMilli(t0));
        assertThat(ts(row, "last_active_at")).isEqualTo(NOW.plusSeconds(10));
        assertThat(row.get("entry_url")).isEqualTo("http://localhost:5173/first");
        assertThat(ts(row, "entry_at")).isEqualTo(Instant.ofEpochMilli(t0));
    }

    @Test
    void lateEventForAClosedSessionExtendsItAndFlagsARecompute() {
        Session s = Session.random();
        sendAll(List.of(at(s, Fixtures.click(NOW.toEpochMilli(), "x", null), 0)));
        awaitSession(s);
        db.update("UPDATE user_session SET ended_at = ? WHERE id = ?", Timestamp.from(NOW), s.sessionId());

        RecordMetadata late = sendAll(List.of(at(s, Fixtures.click(NOW.toEpochMilli() + 90_000, "late", null), 120)));
        awaitProcessed(late);

        Map<String, Object> row = session(s);
        assertThat(row.get("needs_recompute")).isEqualTo(true);
        assertThat(ts(row, "ended_at")).isEqualTo(NOW.plusSeconds(90));
        assertThat(ts(row, "last_active_at")).isEqualTo(NOW.plusSeconds(120));
    }

    @Test
    void aSessionPostgresRejectsIsDeadLetteredByTheTrackerOnlyAndTheRestIsWritten() {
        Session good = Session.random();
        Session bad = good.next();
        // valid JSON and a valid envelope; PostgreSQL text cannot hold a NUL byte (22021)
        ProcessorTestInfra.send(List.of(
                Fixtures.eventRecord(good, Fixtures.envelope(good, Fixtures.click(NOW.toEpochMilli(), "ok", null), NOW, null)),
                Fixtures.eventRecord(bad, Fixtures.envelope(bad, Fixtures.navigation(NOW.toEpochMilli(), "http://localhost/a\u0000b"), NOW, null))));

        awaitSession(good);
        List<ConsumerRecord<String, byte[]>> dead = ProcessorTestInfra.records(Topics.TELEMETRY_EVENTS_DLT, bad.key(), 1, WAIT);
        assertThat(dead).hasSize(1);
        assertThat(header(dead.getFirst(), WireHeaders.DLT_REASON)).isEqualTo("store_rejected");
        assertThat(header(dead.getFirst(), WireHeaders.DLT_CONSUMER)).isEqualTo(sessionsGroup);
        assertThat(db.queryForObject("SELECT count(*) FROM user_session WHERE id = ?", Long.class, bad.sessionId())).isZero();
        // ClickHouse took the event: only the tracker rejected it
        await().atMost(WAIT).until(() -> finalEventRows(bad.tenantId(), bad.sessionId()) == 1);
    }

    @Test
    void invalidEnvelopesAreDeadLetteredOnceByTheEventsWriterNotAgainByTheTracker() {
        Session s = Session.random();
        ProcessorTestInfra.send(List.of(Fixtures.record(Topics.TELEMETRY_EVENTS, s, "{not json".getBytes(StandardCharsets.UTF_8)),
                at(s, Fixtures.click(NOW.toEpochMilli(), "after", null), 0)));
        awaitSession(s);
        List<ConsumerRecord<String, byte[]>> dead = ProcessorTestInfra.records(Topics.TELEMETRY_EVENTS_DLT, s.key(), 1, WAIT);
        assertThat(dead).hasSize(1);
        assertThat(header(dead.getFirst(), WireHeaders.DLT_CONSUMER)).isNotEqualTo(sessionsGroup);
    }

    // ------------------------------------------------------------------ helpers

    /** An event of session {@code s}, received {@code seconds} after NOW, from Chrome on macOS. */
    private static ProducerRecord<String, byte[]> at(Session s, TelemetryEvent event, int seconds) {
        return Fixtures.eventRecord(s, Fixtures.envelope(s, event, NOW.plusSeconds(seconds), Fixtures.CHROME_MAC));
    }

    private static RecordMetadata sendAll(List<ProducerRecord<String, byte[]>> records) {
        return ProcessorTestInfra.send(records).getLast();
    }

    private Map<String, Object> session(Session s) {
        return db.queryForMap("SELECT * FROM user_session WHERE id = ?", s.sessionId());
    }

    private Map<String, Object> awaitSession(Session s) {
        await().atMost(WAIT).until(() ->
                db.queryForObject("SELECT count(*) FROM user_session WHERE id = ?", Long.class, s.sessionId()) == 1);
        return session(s);
    }

    private void awaitProcessed(RecordMetadata sent) {
        TopicPartition partition = new TopicPartition(Topics.TELEMETRY_EVENTS, sent.partition());
        await().atMost(WAIT).until(() -> ProcessorTestInfra.committedOffset(sessionsGroup, partition) > sent.offset());
    }

    private static Instant ts(Map<String, Object> row, String column) {
        return ((Timestamp) row.get(column)).toInstant();
    }

    private static String header(ConsumerRecord<?, ?> record, String name) {
        var header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
