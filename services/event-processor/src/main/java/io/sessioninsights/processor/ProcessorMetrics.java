package io.sessioninsights.processor;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.sessioninsights.processor.store.Store;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Processor meters (task 4.6). Tags are fixed, low-cardinality codes: table, topic, store,
 * DLT reason. Never a tenant, session or anything from a payload. Consumer lag comes from
 * the Kafka client metrics that Boot binds to the registry
 * ({@code kafka.consumer.fetch.manager.records.lag.max}).
 * The bean name avoids Boot's own {@code processorMetrics} (CPU metrics).
 */
@Component("pipelineMetrics")
public class ProcessorMetrics {

    public static final String ROWS_INSERTED = "si.processor.rows.inserted";
    public static final String INSERT_LATENCY = "si.processor.insert.latency";
    public static final String BATCH_SIZE = "si.processor.batch.size";
    public static final String CHUNKS_STORED = "si.processor.replay.chunks.stored";
    public static final String BYTES_STORED = "si.processor.replay.bytes.stored";
    public static final String DEAD_LETTERED = "si.processor.dlt";
    public static final String STORE_RETRIES = "si.processor.store.retries";
    public static final String STORE_UNAVAILABLE = "si.processor.store.unavailable";
    public static final String SESSIONS_UPSERTED = "si.processor.sessions.upserted";
    public static final String SESSIONS_SKIPPED = "si.processor.sessions.skipped";
    public static final String SESSIONS_CLOSED = "si.processor.sessions.closed";
    public static final String SESSIONS_CLOSE_DEFERRED = "si.processor.sessions.close.deferred";
    public static final String SESSIONS_CLOSED_WITHOUT_EVENTS = "si.processor.sessions.closed.without_events";
    public static final String CLOSER_FAILURES = "si.processor.sessions.closer.failures";

    private final MeterRegistry registry;
    private final Counter chunksStored;
    private final Counter bytesStored;
    private final Map<Store, AtomicInteger> unavailable = new EnumMap<>(Store.class);

    public ProcessorMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.chunksStored = Counter.builder(CHUNKS_STORED)
                .description("Replay chunk objects written to object storage").register(registry);
        this.bytesStored = Counter.builder(BYTES_STORED).baseUnit("bytes")
                .description("Compressed replay bytes written to object storage").register(registry);
        for (Store store : Store.values()) {
            AtomicInteger state = new AtomicInteger();
            unavailable.put(store, state);
            Gauge.builder(STORE_UNAVAILABLE, state, AtomicInteger::get)
                    .description("1 while a store outage holds consumption back, else 0")
                    .tag("store", store.tag()).register(registry);
        }
    }

    public void inserted(String table, int rows, long nanos, boolean success) {
        Timer.builder(INSERT_LATENCY).description("ClickHouse insert latency, one insert per batch")
                .tag("table", table).tag("outcome", success ? "success" : "failure")
                .register(registry).record(nanos, TimeUnit.NANOSECONDS);
        if (rows > 0) {
            Counter.builder(ROWS_INSERTED).description("Rows inserted into ClickHouse (before dedup)")
                    .tag("table", table).register(registry).increment(rows);
        }
    }

    /** One listener invocation; the sum over time is how many records each listener processed. */
    public void batch(String listener, String topic, int records) {
        DistributionSummary.builder(BATCH_SIZE).description("Records per consumed batch")
                .tag("listener", listener).tag("topic", topic).register(registry).record(records);
    }

    public void chunkStored(long compressedBytes) {
        chunksStored.increment();
        bytesStored.increment(compressedBytes);
    }

    public void deadLettered(String topic, String reason) {
        Counter.builder(DEAD_LETTERED).description("Records sent to a dead-letter topic")
                .tag("topic", topic).tag("reason", reason).register(registry).increment();
    }

    /** Sessions written by the tracker (one per session per batch, before no-op detection). */
    public void sessionsUpserted(int sessions) {
        Counter.builder(SESSIONS_UPSERTED).description("Session upserts applied by the session tracker")
                .register(registry).increment(sessions);
    }

    /** Records the tracker ignored because the events writer dead-letters them already. */
    public void sessionRecordSkipped(String reason) {
        Counter.builder(SESSIONS_SKIPPED).description("Invalid records skipped by the session tracker")
                .tag("reason", reason).register(registry).increment();
    }

    /** One closer pass: {@code kind} close = first close, recompute = late events. */
    public void sessionsClosed(int closed, int recomputed, int deferred, int withoutEvents) {
        Counter.builder(SESSIONS_CLOSED).description("Sessions closed or recomputed by the closer")
                .tag("kind", "close").register(registry).increment(closed);
        Counter.builder(SESSIONS_CLOSED).description("Sessions closed or recomputed by the closer")
                .tag("kind", "recompute").register(registry).increment(recomputed);
        Counter.builder(SESSIONS_CLOSE_DEFERRED)
                .description("Claims skipped because ClickHouse had no events for the session yet")
                .register(registry).increment(deferred);
        Counter.builder(SESSIONS_CLOSED_WITHOUT_EVENTS)
                .description("Sessions closed with zero counters after 2 x idle timeout without ClickHouse events")
                .register(registry).increment(withoutEvents);
    }

    public void closerFailed() {
        Counter.builder(CLOSER_FAILURES).description("Closer passes rolled back").register(registry).increment();
    }

    public void storeRetry(Store store) {
        Counter.builder(STORE_RETRIES).description("Batch attempts that failed because a store was unavailable")
                .tag("store", store.tag()).register(registry).increment();
    }

    public void storeUnavailable(Store store, boolean down) {
        unavailable.get(store).set(down ? 1 : 0);
    }
}
