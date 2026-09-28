package io.sessioninsights.processor.sessions;

import io.sessioninsights.common.Topics;
import io.sessioninsights.common.wire.EventType;
import io.sessioninsights.common.wire.SessionLifecycleEvent;
import io.sessioninsights.common.wire.WireJson;
import io.sessioninsights.processor.Fixtures;
import io.sessioninsights.processor.Fixtures.Session;
import io.sessioninsights.processor.ProcessorIntegrationTest;
import io.sessioninsights.processor.ProcessorMetrics;
import io.sessioninsights.processor.ProcessorTestInfra;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

import static io.sessioninsights.processor.Fixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * Task 5.4. Closers are built by hand with short idle timeouts and run explicitly (the
 * scheduled one is off in tests). A pass claims idle sessions of every tenant, so assertions
 * are about this test's own sessions.
 */
class SessionCloserTest extends ProcessorIntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(30);
    private final JdbcTemplate db = ProcessorTestInfra.owner();

    @Autowired
    JdbcClient jdbc;
    @Autowired
    TransactionTemplate tx;
    @Autowired
    SessionStats stats;
    @Autowired
    KafkaTemplate<String, byte[]> kafka;
    @Autowired
    ProcessorMetrics metrics;

    @Test
    void closesAfterTheIdleTimeoutWithCountersFromClickHouseAndPublishesClosedOnce() {
        Session s = Session.random();
        long t0 = NOW.toEpochMilli() - 120_000;
        track(s, List.of(
                Fixtures.navigation(t0, "http://localhost:5173/a"),
                Fixtures.navigation(t0 + 1_000, "http://localhost:5173/a#faq"),         // fragment only
                Fixtures.navigation(t0 + 2_000, "http://localhost:5173/b"),
                Fixtures.ofType(EventType.EXCEPTION, t0 + 3_000),
                Fixtures.ofType(EventType.CONSOLE_ERROR, t0 + 4_000),
                Fixtures.click(t0 + 5_000, "x", null)));
        idleFor(s, Duration.ofMinutes(10));

        closer(Duration.ofHours(1)).closeAll();
        assertThat(row(s).get("ended_at")).as("idle for 10 min, timeout 1 h: still open").isNull();

        closer(Duration.ofMinutes(5)).closeAll();

        Map<String, Object> row = row(s);
        assertThat(((Timestamp) row.get("ended_at")).toInstant()).isEqualTo(Instant.ofEpochMilli(t0 + 5_000));
        assertThat(row.get("duration_ms")).isEqualTo(5_000L);
        assertThat(row.get("page_count")).isEqualTo(2);
        assertThat(row.get("error_count")).isEqualTo(2);
        assertThat(row.get("needs_recompute")).isEqualTo(false);

        closer(Duration.ofMinutes(5)).closeAll();   // nothing left to do for it
        List<SessionLifecycleEvent> published = lifecycle(s, 1);
        assertThat(published).singleElement().satisfies(e -> {
            assertThat(e.type()).isEqualTo(SessionLifecycleEvent.Type.CLOSED);
            assertThat(e.tenantId()).isEqualTo(s.tenantId());
            assertThat(e.siteId()).isEqualTo(s.siteId());
            assertThat(e.pageCount()).isEqualTo(2);
            assertThat(e.errorCount()).isEqualTo(2);
            assertThat(e.durationMs()).isEqualTo(5_000);
        });
    }

    @Test
    void twoClosersInParallelNeverDoubleClose() throws Exception {
        List<Session> sessions = new ArrayList<>();
        Session first = Session.random();
        for (int i = 0; i < 20; i++) {
            Session s = i == 0 ? first : first.next();
            sessions.add(s);
            track(s, List.of(Fixtures.click(NOW.toEpochMilli() - 60_000, "x", null)));
        }
        sessions.forEach(s -> idleFor(s, Duration.ofMinutes(10)));

        try (var pool = Executors.newFixedThreadPool(2)) {
            List<CompletableFuture<Void>> runs = new ArrayList<>();
            for (int closerNo = 0; closerNo < 2; closerNo++) {
                SessionCloser closer = closer(Duration.ofMinutes(5), 3);   // small batches: many interleaved passes
                runs.add(CompletableFuture.runAsync(closer::closeAll, pool));
            }
            CompletableFuture.allOf(runs.toArray(CompletableFuture[]::new)).get();
        }

        for (Session s : sessions) {
            assertThat(row(s).get("ended_at")).isNotNull();
            assertThat(lifecycle(s, 1)).as("closed once: " + s.sessionId()).hasSize(1);
        }
    }

    @Test
    void lateEventAfterCloseRecomputesAndPublishesUpdated() {
        Session s = Session.random();
        long t0 = NOW.toEpochMilli() - 300_000;
        track(s, List.of(Fixtures.navigation(t0, "http://localhost:5173/a")));
        idleFor(s, Duration.ofMinutes(10));
        closer(Duration.ofMinutes(5)).closeAll();
        assertThat(row(s).get("error_count")).isEqualTo(0);

        // a late event (e.g. an offline queue flushing): received now, happened later in the page
        ProcessorTestInfra.send(List.of(Fixtures.eventRecord(s,
                Fixtures.envelope(s, Fixtures.ofType(EventType.EXCEPTION, t0 + 30_000), Instant.now(), null))));
        await().atMost(WAIT).until(() -> Boolean.TRUE.equals(row(s).get("needs_recompute")));
        await().atMost(WAIT).until(() -> finalEventRows(s.tenantId(), s.sessionId()) == 2);

        closer(Duration.ofHours(1)).closeAll();   // recomputes do not wait for the idle timeout

        Map<String, Object> row = row(s);
        assertThat(row.get("needs_recompute")).isEqualTo(false);
        assertThat(row.get("error_count")).isEqualTo(1);
        assertThat(row.get("duration_ms")).isEqualTo(30_000L);
        assertThat(lifecycle(s, 2)).extracting(SessionLifecycleEvent::type)
                .containsExactly(SessionLifecycleEvent.Type.CLOSED, SessionLifecycleEvent.Type.UPDATED);
    }

    @Test
    void aSessionWithoutClickHouseRowsIsDeferredUntilTwiceTheIdleTimeoutThenClosedWithZeroCounters() {
        Session s = Session.random();
        Duration idle = Duration.ofMinutes(10);
        db.update("INSERT INTO user_session (id, tenant_id, site_id, anonymous_id, started_at, last_active_at)"
                        + " VALUES (?, ?, ?, 'anon', now() - interval '15 minutes', now() - interval '15 minutes')",
                s.sessionId(), s.tenantId(), s.siteId());
        double deferredBefore = counter(ProcessorMetrics.SESSIONS_CLOSE_DEFERRED);

        closer(idle).closeAll();
        assertThat(row(s).get("ended_at")).as("1.5 x idle: deferred, ClickHouse may lag").isNull();
        assertThat(counter(ProcessorMetrics.SESSIONS_CLOSE_DEFERRED)).isGreaterThan(deferredBefore);

        db.update("UPDATE user_session SET last_active_at = now() - interval '25 minutes' WHERE id = ?", s.sessionId());
        double emptyBefore = counter(ProcessorMetrics.SESSIONS_CLOSED_WITHOUT_EVENTS);
        closer(idle).closeAll();

        Map<String, Object> row = row(s);
        assertThat(row.get("ended_at")).as("2.5 x idle: closed, never reclaimed forever").isNotNull();
        assertThat(row.get("page_count")).isEqualTo(0);
        assertThat(row.get("error_count")).isEqualTo(0);
        assertThat(row.get("duration_ms")).isEqualTo(0L);
        assertThat(counter(ProcessorMetrics.SESSIONS_CLOSED_WITHOUT_EVENTS)).isGreaterThan(emptyBefore);
        assertThat(lifecycle(s, 1)).singleElement().extracting(SessionLifecycleEvent::type)
                .isEqualTo(SessionLifecycleEvent.Type.CLOSED);
    }

    @Test
    void anUnacknowledgedPublishRollsThePassBack() {
        Session s = Session.random();
        track(s, List.of(Fixtures.click(NOW.toEpochMilli() - 60_000, "x", null)));
        idleFor(s, Duration.ofMinutes(10));
        KafkaTemplate<String, byte[]> unreachable = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(Map.<String, Object>of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:1",
                // as in application.yml: send() waits at most 5 s for metadata
                ProducerConfig.MAX_BLOCK_MS_CONFIG, 5_000), new StringSerializer(), new ByteArraySerializer()));
        SessionCloser closer = new SessionCloser(jdbc, tx, stats, unreachable, metrics, Duration.ofMinutes(5), 500,
                Duration.ofSeconds(10), Duration.ofSeconds(2), Clock.systemUTC());

        long start = System.nanoTime();
        assertThatThrownBy(closer::pass);   // the first send cannot get metadata: the pass fails
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(15));
        assertThat(row(s).get("ended_at")).as("rolled back").isNull();
        unreachable.destroy();

        closer(Duration.ofMinutes(5)).closeAll();   // claimed again next time
        assertThat(row(s).get("ended_at")).isNotNull();
    }

    @Test
    void theStatementTimeoutBoundsThePass() throws Exception {
        SessionCloser closer = new SessionCloser(jdbc, tx, stats, kafka, metrics, Duration.ofMinutes(5), 500,
                Duration.ofMillis(500), Duration.ofSeconds(10), Clock.systemUTC());
        // another session holds the table: without a statement timeout the claim would wait forever
        try (Connection blocker = DriverManager.getConnection(ProcessorTestInfra.POSTGRES.getJdbcUrl(),
                ProcessorTestInfra.POSTGRES.getUsername(), ProcessorTestInfra.POSTGRES.getPassword())) {
            blocker.setAutoCommit(false);
            try (Statement lock = blocker.createStatement()) {
                lock.execute("LOCK TABLE user_session IN ACCESS EXCLUSIVE MODE");
            }
            long start = System.nanoTime();
            assertThatThrownBy(closer::pass).hasStackTraceContaining("statement timeout");
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
            blocker.rollback();
        }
    }

    // ------------------------------------------------------------------ helpers

    private SessionCloser closer(Duration idle) {
        return closer(idle, 500);
    }

    private SessionCloser closer(Duration idle, int batchSize) {
        return new SessionCloser(jdbc, tx, stats, kafka, metrics, idle, batchSize, Duration.ofSeconds(10),
                Duration.ofSeconds(10), Clock.systemUTC());
    }

    /** Sends the events and waits until both the tracker and the ClickHouse writer have them. */
    private void track(Session s, List<io.sessioninsights.common.wire.TelemetryEvent> events) {
        List<ProducerRecord<String, byte[]>> records = events.stream()
                .map(e -> Fixtures.eventRecord(s, Fixtures.envelope(s, e, NOW, Fixtures.CHROME_MAC))).toList();
        ProcessorTestInfra.send(records);
        await().atMost(WAIT).until(() ->
                db.queryForObject("SELECT count(*) FROM user_session WHERE id = ?", Long.class, s.sessionId()) == 1
                        && finalEventRows(s.tenantId(), s.sessionId()) == events.size());
    }

    private void idleFor(Session s, Duration idle) {
        db.update("UPDATE user_session SET last_active_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minus(idle)), s.sessionId());
    }

    private Map<String, Object> row(Session s) {
        return db.queryForMap("SELECT * FROM user_session WHERE id = ?", s.sessionId());
    }

    private List<SessionLifecycleEvent> lifecycle(Session s, int expected) {
        List<ConsumerRecord<String, byte[]>> records =
                ProcessorTestInfra.records(Topics.SESSION_LIFECYCLE, s.key(), expected, WAIT);
        return records.stream().map(r -> WireJson.mapper().readValue(r.value(), SessionLifecycleEvent.class)).toList();
    }

    private double counter(String name) {
        var counter = registry().find(name).counter();
        return counter == null ? 0 : counter.count();
    }

    @Autowired
    io.micrometer.core.instrument.MeterRegistry meterRegistry;

    private io.micrometer.core.instrument.MeterRegistry registry() {
        return meterRegistry;
    }
}
