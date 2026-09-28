package io.sessioninsights.processor.sessions;

import io.sessioninsights.common.Topics;
import io.sessioninsights.common.wire.SessionLifecycleEvent;
import io.sessioninsights.common.wire.WireHeaders;
import io.sessioninsights.common.wire.WireJson;
import io.sessioninsights.processor.ProcessorMetrics;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Closes idle sessions and recomputes closed ones that received late events (task 5.4).
 * <p>
 * One pass is one PostgreSQL transaction: claim up to {@code batchSize} sessions with
 * {@code claim_sessions_to_close()} (row locks, {@code SKIP LOCKED}, so closers on several
 * instances never claim the same session), compute counters from ClickHouse, update the rows,
 * publish {@code CLOSED}/{@code UPDATED} to {@code session.lifecycle.v1} and wait for the acks,
 * then commit. Each statement is bounded by {@code statement_timeout}, each {@code send()} by the
 * producer's {@code max.block.ms} and the acks by {@code publishTimeout}; any failure (a store
 * down, a timeout) rolls the whole pass
 * back; the sessions are claimed again next time. A commit that fails after the acks re-publishes
 * on the next pass: at least once.
 * <p>
 * A claimed session with no ClickHouse rows yet (the events writer lagging) is left open until
 * it has been idle for twice the timeout, then closed with zero counters, so no session is
 * reclaimed forever.
 */
public class SessionCloser {

    /** What one pass did. */
    public record Pass(int claimed, int closed, int recomputed, int deferred, int closedWithoutEvents) {
        static final Pass EMPTY = new Pass(0, 0, 0, 0, 0);
    }

    private record Claim(UUID tenantId, UUID sessionId, String kind) {
    }

    private record Row(UUID id, UUID siteId, Instant startedAt, Instant lastActiveAt) {
    }

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final SessionStats stats;
    private final KafkaOperations<String, byte[]> kafka;
    private final ProcessorMetrics metrics;
    private final Duration idleTimeout;
    private final int batchSize;
    private final Duration statementTimeout;
    private final Duration publishTimeout;
    private final Clock clock;

    public SessionCloser(JdbcClient jdbc, TransactionTemplate tx, SessionStats stats, KafkaOperations<String, byte[]> kafka,
                         ProcessorMetrics metrics, Duration idleTimeout, int batchSize, Duration statementTimeout,
                         Duration publishTimeout, Clock clock) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.stats = stats;
        this.kafka = kafka;
        this.metrics = metrics;
        this.idleTimeout = idleTimeout;
        this.batchSize = batchSize;
        this.statementTimeout = statementTimeout;
        this.publishTimeout = publishTimeout;
        this.clock = clock;
    }

    /** Runs passes until one claims less than a full batch. */
    public List<Pass> closeAll() {
        List<Pass> passes = new ArrayList<>();
        Pass pass;
        do {
            pass = pass();
            passes.add(pass);
        } while (pass.claimed() >= batchSize);
        return passes;
    }

    /** One transaction; throws (after rolling back) if anything fails. */
    public Pass pass() {
        Pass pass = tx.execute(status -> {
            // SET LOCAL: bounds every statement of this transaction only
            jdbc.sql("SELECT set_config('statement_timeout', :ms, true)")
                    .param("ms", Long.toString(statementTimeout.toMillis())).query().singleValue();
            List<Claim> claims = jdbc.sql("SELECT tenant_id, session_id, kind FROM claim_sessions_to_close(CAST(:idle AS interval), :limit)")
                    .param("idle", idleTimeout.toMillis() + " milliseconds").param("limit", batchSize)
                    .query((rs, n) -> new Claim(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3)))
                    .list();
            if (claims.isEmpty()) {
                return Pass.EMPTY;
            }
            Map<UUID, List<Claim>> byTenant = new LinkedHashMap<>();
            claims.forEach(c -> byTenant.computeIfAbsent(c.tenantId(), t -> new ArrayList<>()).add(c));

            Instant now = clock.instant();
            int closed = 0;
            int recomputed = 0;
            int deferred = 0;
            int empty = 0;
            List<CompletableFuture<?>> acks = new ArrayList<>();
            for (Map.Entry<UUID, List<Claim>> tenant : byTenant.entrySet()) {
                UUID tenantId = tenant.getKey();
                jdbc.sql("SELECT set_config('app.tenant_id', :tenant, true)").param("tenant", tenantId.toString())
                        .query().singleValue();
                List<UUID> ids = tenant.getValue().stream().map(Claim::sessionId).toList();
                Map<UUID, Row> rows = new LinkedHashMap<>();
                jdbc.sql("SELECT id, site_id, started_at, last_active_at FROM user_session WHERE id = ANY(:ids)")
                        .param("ids", ids.toArray(UUID[]::new))
                        .query((rs, n) -> new Row(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                                rs.getTimestamp(3).toInstant(), rs.getTimestamp(4).toInstant()))
                        .list().forEach(r -> rows.put(r.id(), r));
                Map<UUID, SessionStats.Stats> counters = stats.of(tenantId, ids);

                for (Claim claim : tenant.getValue()) {
                    Row row = rows.get(claim.sessionId());
                    if (row == null) {
                        continue;   // invisible under RLS: cannot happen for a row the function returned
                    }
                    boolean recompute = "RECOMPUTE".equals(claim.kind());
                    SessionStats.Stats s = counters.get(claim.sessionId());
                    if (s == null) {
                        if (!recompute && row.lastActiveAt().isAfter(now.minus(idleTimeout.multipliedBy(2)))) {
                            deferred++;   // ClickHouse may still be catching up
                            continue;
                        }
                        s = new SessionStats.Stats(row.startedAt(), row.startedAt(), 0, 0);
                        empty++;
                    }
                    update(claim.sessionId(), s);
                    acks.add(publish(tenantId, row, s, recompute ? SessionLifecycleEvent.Type.UPDATED
                            : SessionLifecycleEvent.Type.CLOSED, now));
                    if (recompute) {
                        recomputed++;
                    } else {
                        closed++;
                    }
                }
            }
            awaitAcks(acks);
            return new Pass(claims.size(), closed, recomputed, deferred, empty);
        });
        metrics.sessionsClosed(pass.closed(), pass.recomputed(), pass.deferred(), pass.closedWithoutEvents());
        return pass;
    }

    private void update(UUID sessionId, SessionStats.Stats s) {
        jdbc.sql("""
                        UPDATE user_session SET
                            started_at      = least(started_at, :firstTs),
                            ended_at        = :lastTs,
                            duration_ms     = :durationMs,
                            page_count      = :pages,
                            error_count     = :errors,
                            needs_recompute = false,
                            updated_at      = now(),
                            version         = version + 1
                        WHERE id = :id""")
                .param("firstTs", Timestamp.from(s.firstTs())).param("lastTs", Timestamp.from(s.lastTs()))
                .param("durationMs", s.durationMs()).param("pages", s.pageCount()).param("errors", s.errorCount())
                .param("id", sessionId)
                .update();
    }

    private CompletableFuture<?> publish(UUID tenantId, Row row, SessionStats.Stats s, SessionLifecycleEvent.Type type,
                                         Instant now) {
        Instant startedAt = row.startedAt().isBefore(s.firstTs()) ? row.startedAt() : s.firstTs();
        SessionLifecycleEvent event = new SessionLifecycleEvent(WireHeaders.CURRENT_SCHEMA_VERSION, type, tenantId,
                row.siteId(), row.id(), startedAt, s.lastTs(), s.durationMs(), s.pageCount(), s.errorCount(), now);
        ProducerRecord<String, byte[]> record = new ProducerRecord<>(Topics.SESSION_LIFECYCLE, row.id().toString(),
                WireJson.mapper().writeValueAsBytes(event));
        record.headers().add(new RecordHeader(WireHeaders.TENANT_ID, tenantId.toString().getBytes(StandardCharsets.UTF_8)));
        return kafka.send(record);
    }

    /** Throws if any ack is missing after the publish timeout: the transaction rolls back. */
    private void awaitAcks(List<CompletableFuture<?>> acks) {
        try {
            CompletableFuture.allOf(acks.toArray(CompletableFuture[]::new))
                    .get(publishTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while publishing session lifecycle events", e);
        } catch (Exception e) {
            throw new IllegalStateException("session lifecycle events not acknowledged in " + publishTimeout, e);
        }
    }
}
