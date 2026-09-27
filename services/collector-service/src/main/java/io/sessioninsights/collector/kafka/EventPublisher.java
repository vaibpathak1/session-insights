package io.sessioninsights.collector.kafka;

import io.micrometer.core.instrument.Timer;
import io.sessioninsights.collector.CollectorMetrics;
import io.sessioninsights.collector.config.CollectorProperties;
import io.sessioninsights.collector.ingest.IngestException;
import io.sessioninsights.collector.ingest.Rejection;
import io.sessioninsights.common.Topics;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Sends all records of one request and waits for every ack before returning (NFR:
 * acknowledged events are never lost). Blocking is fine on a virtual thread. Any failure
 * becomes {@code 503}; the client retries the whole batch and consumers dedup by
 * {@code clientEventId}, so a partially sent batch is harmless.
 */
@Component
public class EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(EventPublisher.class);
    /** Suggested client back-off when Kafka is unavailable. */
    static final long RETRY_AFTER_SECONDS = 5;

    private final KafkaTemplate<String, byte[]> template;
    private final Duration sendTimeout;
    private final CollectorMetrics metrics;

    public EventPublisher(KafkaTemplate<String, byte[]> template, CollectorProperties properties,
                          CollectorMetrics metrics) {
        this.template = template;
        this.sendTimeout = properties.kafkaSendTimeout();
        this.metrics = metrics;
    }

    /** Sends records for one topic and waits for all acks. */
    public void publish(String topic, List<ProducerRecord<String, byte[]>> records) {
        Timer.Sample sample = metrics.startKafkaSend();
        boolean success = false;
        try {
            CompletableFuture<?>[] acks = records.stream().map(template::send).toArray(CompletableFuture[]::new);
            CompletableFuture.allOf(acks).get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
            success = true;
        } catch (ExecutionException e) {
            if (hasCause(e, RecordTooLargeException.class)) {
                throw new IngestException(Rejection.TOO_LARGE);
            }
            throw unavailable(e);
        } catch (TimeoutException | RuntimeException e) {
            throw unavailable(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unavailable(e);
        } finally {
            metrics.stopKafkaSend(sample, topic, success);
        }
    }

    /**
     * Fetches topic metadata once at startup, on a platform thread: the Kafka client waits
     * for metadata with {@code Object.wait()} inside {@code synchronized}, which would pin a
     * virtual thread if the first request had to do it. Best effort; failures are logged.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void warmUpMetadata() {
        Thread.ofPlatform().daemon().name("kafka-metadata-warmup").start(() -> {
            for (String topic : List.of(Topics.TELEMETRY_EVENTS, Topics.REPLAY_CHUNKS)) {
                try {
                    template.partitionsFor(topic);
                } catch (RuntimeException e) {
                    log.warn("Kafka metadata warm-up failed for {} ({})", topic, e.getClass().getSimpleName());
                }
            }
        });
    }

    private static IngestException unavailable(Exception e) {
        log.warn("Kafka send failed: {}", rootCause(e).getClass().getSimpleName());
        return new IngestException(Rejection.UNAVAILABLE, RETRY_AFTER_SECONDS);
    }

    private static boolean hasCause(Throwable e, Class<? extends Throwable> type) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (type.isInstance(t)) {
                return true;
            }
        }
        return false;
    }

    private static Throwable rootCause(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t;
    }
}
