package io.sessioninsights.processor.smoke;

import com.clickhouse.client.api.Client;
import io.sessioninsights.common.Topics;
import io.sessioninsights.common.wire.EventType;
import io.sessioninsights.common.wire.ReplayEnvelope;
import io.sessioninsights.common.wire.TelemetryEnvelope;
import io.sessioninsights.common.wire.TelemetryEvent;
import io.sessioninsights.common.wire.WireJson;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 4.9 throughput smoke, manual: against the compose stack and a running event-processor
 * (not Testcontainers). Skipped unless {@code -Dsmoke.throughput=true}:
 * <pre>
 * mvn -q -pl services/event-processor test -Dtest=ThroughputSmokeTest -Dsmoke.throughput=true -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 * Phase 1 (burst): 200k envelopes across 2,000 sessions, produced as fast as possible, plus
 * 4,000 replay chunks, for the sustained insert rate. Phase 2 (paced): 5,000 events/s for 30 s,
 * the single-node NFR rate, for the end-to-end lag (produce → queryable in ClickHouse).
 * {@code -Dsmoke.burstOnly=true} runs phase 1 only: start it with the processor stopped and
 * start the processor at "SMOKE: burst produced" to measure the drain rate of a backlog.
 * Each phase writes under its own random tenant; nothing else is touched.
 */
@EnabledIfSystemProperty(named = "smoke.throughput", matches = "true")
class ThroughputSmokeTest {

    private static final int EVENTS = Integer.getInteger("smoke.events", 200_000);
    private static final int SESSIONS = 2_000;
    private static final int CHUNKS_PER_SESSION = 2;
    private static final int PACED_RATE = 5_000;
    private static final int PACED_SECONDS = 30;

    private final Client ch = new Client.Builder()
            .addEndpoint(env("CLICKHOUSE_URL", "http://localhost:8123"))
            .setUsername(env("CLICKHOUSE_USER", "insights"))
            .setPassword(env("CLICKHOUSE_PASSWORD", "insights_dev_pw"))
            .setDefaultDatabase(env("CLICKHOUSE_DB", "insights"))
            .build();

    @Test
    void burstThenPaced() throws Exception {
        try (KafkaProducer<String, byte[]> producer = producer()) {
            burst(producer);
            if (!Boolean.getBoolean("smoke.burstOnly")) {
                paced(producer);
            }
        } finally {
            ch.close();
        }
    }

    // ------------------------------------------------------------ phase 1

    private void burst(KafkaProducer<String, byte[]> producer) throws Exception {
        UUID tenant = UUID.randomUUID();
        UUID site = UUID.randomUUID();
        createTenant(tenant, site);
        List<UUID> sessions = new ArrayList<>();
        for (int i = 0; i < SESSIONS; i++) {
            sessions.add(UUID.randomUUID());
        }
        int chunks = SESSIONS * CHUNKS_PER_SESSION;
        long block = EVENTS / 20;
        long[] blockSent = new long[20];

        Poller poller = new Poller(tenant, EVENTS, chunks);
        poller.start();
        long start = System.nanoTime();
        for (int i = 0; i < EVENTS; i++) {
            UUID session = sessions.get(i % SESSIONS);
            producer.send(new ProducerRecord<>(Topics.TELEMETRY_EVENTS, session.toString(),
                    WireJson.mapper().writeValueAsBytes(envelope(tenant, site, session, i))));
            if ((i + 1) % block == 0) {
                blockSent[(int) ((i + 1) / block) - 1] = System.nanoTime();
            }
            if (i % (EVENTS / chunks) == 0 && i / (EVENTS / chunks) < chunks) {
                int c = i / (EVENTS / chunks);
                UUID chunkSession = sessions.get(c % SESSIONS);
                producer.send(new ProducerRecord<>(Topics.REPLAY_CHUNKS, chunkSession.toString(),
                        WireJson.mapper().writeValueAsBytes(chunk(tenant, site, chunkSession, c / SESSIONS))));
            }
        }
        producer.flush();
        long produced = System.nanoTime();
        System.out.println("SMOKE: burst produced");   // drain runs start the processor now
        poller.join(Duration.ofMinutes(10).toMillis());

        List<long[]> samples = poller.samples;   // {nanos, events, chunks}
        long firstRow = samples.stream().filter(s -> s[1] > 0).findFirst().orElseThrow()[0];
        long at10 = firstAt(samples, EVENTS / 10);
        long at90 = firstAt(samples, EVENTS * 9 / 10);
        long all = firstAt(samples, EVENTS);
        long chunksDone = samples.stream().filter(s -> s[2] >= chunks).findFirst().map(s -> s[0]).orElse(-1L);
        long[] lags = new long[20];
        for (int b = 0; b < 20; b++) {
            lags[b] = firstAt(samples, (b + 1) * block) - blockSent[b];
        }
        java.util.Arrays.sort(lags);

        long finalRows = count("SELECT count() AS c FROM events FINAL WHERE tenant_id = {t:UUID}", tenant);
        long finalChunks = count("SELECT count() AS c FROM replay_chunks FINAL WHERE tenant_id = {t:UUID}", tenant);
        System.out.printf("""
                        === Throughput smoke, phase 1 (burst) — tenant %s
                        produced            %,d events + %,d replay chunks in %.1f s (%,.0f events/s into Kafka)
                        first row visible   %.1f s after start
                        sustained insert    %,.0f rows/s (10%%→90%% of rows)
                        all rows visible    %.1f s after start → %,.0f rows/s overall
                        all chunks stored   %s
                        block lag (10k)     p50 %.1f s, max %.1f s  (produce → queryable, backlog included)
                        FINAL check         %,d rows, %,d manifest rows (expected %,d / %,d)
                        """,
                tenant, EVENTS, chunks, secs(produced - start), EVENTS / secs(produced - start),
                secs(firstRow - start),
                (EVENTS * 0.8) / secs(at90 - at10),
                secs(all - start), EVENTS / secs(all - start),
                chunksDone < 0 ? "not within timeout" : "%.1f s after start".formatted(secs(chunksDone - start)),
                secs(lags[9]), secs(lags[19]),
                finalRows, finalChunks, EVENTS, chunks);
        assertThat(finalRows).isEqualTo(EVENTS);
        assertThat(finalChunks).isEqualTo(chunks);
    }

    // ------------------------------------------------------------ phase 2

    private void paced(KafkaProducer<String, byte[]> producer) throws Exception {
        UUID tenant = UUID.randomUUID();
        UUID site = UUID.randomUUID();
        createTenant(tenant, site);
        List<UUID> sessions = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            sessions.add(UUID.randomUUID());
        }
        int total = PACED_RATE * PACED_SECONDS;
        int perTick = PACED_RATE / 10;   // every 100 ms
        List<long[]> sent = new ArrayList<>();   // {nanos, cumulative}
        Poller poller = new Poller(tenant, total, 0);
        poller.start();
        long start = System.nanoTime();
        int n = 0;
        for (int tick = 0; tick < PACED_SECONDS * 10; tick++) {
            for (int i = 0; i < perTick; i++, n++) {
                UUID session = sessions.get(n % sessions.size());
                producer.send(new ProducerRecord<>(Topics.TELEMETRY_EVENTS, session.toString(),
                        WireJson.mapper().writeValueAsBytes(envelope(tenant, site, session, n))));
            }
            sent.add(new long[] {System.nanoTime(), n});
            long next = start + (tick + 1) * 100_000_000L;
            long sleep = next - System.nanoTime();
            if (sleep > 0) {
                Thread.sleep(sleep / 1_000_000, (int) (sleep % 1_000_000));
            }
        }
        producer.flush();
        long produced = System.nanoTime();
        poller.join(Duration.ofMinutes(5).toMillis());

        long[] lags = sent.stream().mapToLong(s -> firstAt(poller.samples, s[1]) - s[0]).sorted().toArray();
        System.out.printf("""
                        === Throughput smoke, phase 2 (paced %,d events/s for %d s) — tenant %s
                        produced            %,d events in %.1f s (%,.0f events/s)
                        lag produce→queryable  p50 %.2f s, p95 %.2f s, max %.2f s  (%d samples, 250 ms polling)
                        NFR                 5,000 events/s queryable < 10 s: %s
                        """,
                PACED_RATE, PACED_SECONDS, tenant, total, secs(produced - start), total / secs(produced - start),
                secs(lags[lags.length / 2]), secs(lags[(int) (lags.length * 0.95)]), secs(lags[lags.length - 1]),
                lags.length, secs(lags[lags.length - 1]) < 10 ? "met" : "NOT met");
        assertThat(count("SELECT count() AS c FROM events FINAL WHERE tenant_id = {t:UUID}", tenant)).isEqualTo(total);
    }

    // ------------------------------------------------------------ helpers

    /** Polls row and manifest counts for one tenant every 250 ms until both targets are reached. */
    private final class Poller extends Thread {
        private final UUID tenant;
        private final long events;
        private final long chunks;
        final List<long[]> samples = new java.util.concurrent.CopyOnWriteArrayList<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();

        Poller(UUID tenant, long events, long chunks) {
            super("smoke-poller");
            this.tenant = tenant;
            this.events = events;
            this.chunks = chunks;
            setDaemon(true);
        }

        @Override
        public void run() {
            try {
                while (true) {
                    long e = count("SELECT count() AS c FROM events WHERE tenant_id = {t:UUID}", tenant);
                    long c = chunks == 0 ? 0
                            : count("SELECT count() AS c FROM replay_chunks WHERE tenant_id = {t:UUID}", tenant);
                    samples.add(new long[] {System.nanoTime(), e, c});
                    if (e >= events && c >= chunks) {
                        return;
                    }
                    Thread.sleep(250);
                }
            } catch (Throwable t) {
                failure.set(t);
            }
        }
    }

    private static long firstAt(List<long[]> samples, long rows) {
        return samples.stream().filter(s -> s[1] >= rows).findFirst()
                .orElseThrow(() -> new AssertionError("never reached " + rows + " rows"))[0];
    }

    private long count(String sql, UUID tenant) {
        return ch.queryAll(sql, Map.of("t", tenant)).getFirst().getLong("c");
    }

    private static TelemetryEnvelope envelope(UUID tenant, UUID site, UUID session, int i) {
        long ts = System.currentTimeMillis();
        TelemetryEvent event = new TelemetryEvent(UUID.randomUUID(), i % 7 == 0 ? EventType.NAVIGATION : EventType.CLICK,
                ts, "http://localhost:5173/products/" + (i % 50) + "?ref=smoke", "/products/" + (i % 50), "Products",
                "main > div.card:nth-child(" + (i % 12) + ") > button.add", "Add to cart", null, null, null,
                WireJson.mapper().readTree("{\"i\":" + i + ",\"variant\":\"v" + (i % 3) + "\"}"));
        return new TelemetryEnvelope(1, tenant, site, session, "anon-" + session, "0.1.0", Instant.ofEpochMilli(ts), event);
    }

    private static ReplayEnvelope chunk(UUID tenant, UUID site, UUID session, int seq) {
        StringBuilder json = new StringBuilder("[");
        long ts = System.currentTimeMillis();
        for (int e = 0; e < 30; e++) {
            json.append(e == 0 && seq == 0 ? "{\"type\":2" : "{\"type\":3").append(",\"timestamp\":").append(ts + e)
                    .append(",\"data\":{\"source\":1,\"positions\":[{\"x\":").append(e * 7).append(",\"y\":")
                    .append(e * 3).append(",\"id\":42,\"timeOffset\":0}]}},");
        }
        json.setLength(json.length() - 1);
        JsonNode events = WireJson.mapper().readTree(json.append(']').toString());
        return new ReplayEnvelope(1, tenant, site, session, seq, Instant.now(), null, events);
    }

    private static KafkaProducer<String, byte[]> producer() {
        return new KafkaProducer<>(Map.<String, Object>of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, env("KAFKA_BOOTSTRAP", "localhost:9092"),
                ProducerConfig.ACKS_CONFIG, "all",
                ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true,
                ProducerConfig.COMPRESSION_TYPE_CONFIG, "zstd",
                ProducerConfig.LINGER_MS_CONFIG, 20,
                ProducerConfig.BATCH_SIZE_CONFIG, 262_144),
                new StringSerializer(), new ByteArraySerializer());
    }

    /** user_session references tenant and site: create them as the owner, like api-service would. */
    private static void createTenant(UUID tenant, UUID site) throws java.sql.SQLException {
        try (var c = java.sql.DriverManager.getConnection(env("POSTGRES_URL", "jdbc:postgresql://localhost:5432/insights"),
                env("POSTGRES_USER", "insights"), env("POSTGRES_PASSWORD", "insights_dev_pw"));
             var s = c.prepareStatement("INSERT INTO tenant (id, name) VALUES (?, 'throughput-smoke')");
             var t = c.prepareStatement("INSERT INTO site (id, tenant_id, name, allowed_origins) VALUES (?, ?, 'smoke', ARRAY['http://localhost:*'])")) {
            s.setObject(1, tenant);
            s.executeUpdate();
            t.setObject(1, site);
            t.setObject(2, tenant);
            t.executeUpdate();
        }
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static double secs(long nanos) {
        return nanos / 1e9;
    }
}
