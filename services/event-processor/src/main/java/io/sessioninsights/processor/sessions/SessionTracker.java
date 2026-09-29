package io.sessioninsights.processor.sessions;

import io.sessioninsights.common.Topics;
import io.sessioninsights.common.wire.EventType;
import io.sessioninsights.common.wire.TelemetryEnvelope;
import io.sessioninsights.processor.ProcessorMetrics;
import io.sessioninsights.processor.kafka.DeadLetters;
import io.sessioninsights.processor.ingest.BisectingWriter;
import io.sessioninsights.processor.ingest.DltReason;
import io.sessioninsights.processor.ingest.EnvelopeReader;
import io.sessioninsights.processor.ingest.PoisonRecordException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Keeps {@code user_session} and {@code end_user} up to date from {@code telemetry.events.v1}
 * (task 5.3), in its own consumer group, on the same platform-thread containers and ADR-0011
 * error handling as the other listeners.
 * <ul>
 *   <li>Per batch, events are aggregated per session and applied in one transaction; no
 *       counters (they are computed from ClickHouse when the session closes).</li>
 *   <li>Records the events writer already dead-letters (invalid envelopes) are skipped and
 *       counted here, not dead-lettered a second time.</li>
 *   <li>A session PostgreSQL rejects (e.g. a NUL byte, or a session id owned by another
 *       tenant) is isolated by bisection; its records go to the DLT as {@code store_rejected}
 *       with {@code si-dlt-consumer} naming this group.</li>
 * </ul>
 */
@Component
public class SessionTracker {

    public static final String ID = "sessions";

    private final SessionStore store;
    private final UserAgents userAgents;
    private final DeadLetters deadLetters;
    private final ProcessorMetrics metrics;

    public SessionTracker(SessionStore store, UserAgents userAgents, DeadLetters deadLetters, ProcessorMetrics metrics) {
        this.store = store;
        this.userAgents = userAgents;
        this.deadLetters = deadLetters;
        this.metrics = metrics;
    }

    @KafkaListener(id = ID, topics = Topics.TELEMETRY_EVENTS, groupId = "${processor.kafka.sessions.group-id}",
            concurrency = "${processor.kafka.concurrency}", batch = "true",
            properties = {
                    "max.poll.records=${processor.kafka.events.max-poll-records}",
                    "fetch.min.bytes=${processor.kafka.events.fetch-min-bytes}",
                    "fetch.max.wait.ms=${processor.kafka.events.fetch-max-wait-ms}"})
    public void onBatch(List<ConsumerRecord<String, byte[]>> records) {
        metrics.batch(ID, Topics.TELEMETRY_EVENTS, records.size());
        Map<Key, Aggregate> sessions = new LinkedHashMap<>();
        for (ConsumerRecord<String, byte[]> record : records) {
            TelemetryEnvelope envelope;
            try {
                envelope = EnvelopeReader.telemetry(record.value());
            } catch (PoisonRecordException e) {
                metrics.sessionRecordSkipped(e.reason().code());
                continue;
            }
            sessions.computeIfAbsent(new Key(envelope.tenantId(), envelope.sessionId()), k -> new Aggregate())
                    .add(envelope, record);
        }

        List<BisectingWriter.Item<SessionUpdate>> items = new ArrayList<>(sessions.size());
        Map<SessionUpdate, List<ConsumerRecord<String, byte[]>>> recordsByUpdate = new LinkedHashMap<>();
        for (Aggregate aggregate : sessions.values()) {
            SessionUpdate update = aggregate.toUpdate(userAgents);
            items.add(new BisectingWriter.Item<>(aggregate.records.getFirst(), update));
            recordsByUpdate.put(update, aggregate.records);
        }
        List<BisectingWriter.Item<SessionUpdate>> rejected =
                BisectingWriter.write(items, (updates, ignoredToken) -> store.apply(updates));
        metrics.sessionsUpserted(items.size() - rejected.size());

        Map<ConsumerRecord<String, byte[]>, DltReason> poison = new LinkedHashMap<>();
        for (BisectingWriter.Item<SessionUpdate> item : rejected) {
            recordsByUpdate.get(item.row()).forEach(r -> poison.put(r, DltReason.STORE_REJECTED));
        }
        deadLetters.deadLetter(poison);   // ADR-0014: all at once, then the batch is acknowledged
    }

    private record Key(UUID tenantId, UUID sessionId) {
    }

    /** One session's events within one batch. */
    private static final class Aggregate {
        private final List<ConsumerRecord<String, byte[]>> records = new ArrayList<>();
        private TelemetryEnvelope first;
        private long firstTs = Long.MAX_VALUE;
        private long lastTs = Long.MIN_VALUE;
        private Instant lastReceived;
        private long entryTs = Long.MAX_VALUE;
        private String entryUrl;
        private String userAgent;

        void add(TelemetryEnvelope envelope, ConsumerRecord<String, byte[]> record) {
            records.add(record);
            if (first == null) {
                first = envelope;
            }
            long ts = envelope.event().ts();
            firstTs = Math.min(firstTs, ts);
            lastTs = Math.max(lastTs, ts);
            if (lastReceived == null || envelope.receivedAt().isAfter(lastReceived)) {
                lastReceived = envelope.receivedAt();
            }
            if (envelope.event().type() == EventType.NAVIGATION && ts < entryTs && envelope.event().url() != null) {
                entryTs = ts;
                entryUrl = envelope.event().url();
            }
            if (userAgent == null && envelope.userAgent() != null && !envelope.userAgent().isBlank()) {
                userAgent = envelope.userAgent();
            }
        }

        SessionUpdate toUpdate(UserAgents userAgents) {
            UserAgents.Parsed ua = userAgents.parse(userAgent);
            return new SessionUpdate(first.tenantId(), first.siteId(), first.sessionId(), first.anonymousId(),
                    Instant.ofEpochMilli(firstTs), Instant.ofEpochMilli(lastTs), lastReceived,
                    entryUrl == null ? null : Instant.ofEpochMilli(entryTs), entryUrl,
                    ua.platform(), ua.browser());
        }
    }
}
