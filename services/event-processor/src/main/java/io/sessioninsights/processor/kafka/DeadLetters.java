package io.sessioninsights.processor.kafka;

import io.sessioninsights.common.Topics;
import io.sessioninsights.common.wire.WireHeaders;
import io.sessioninsights.processor.ProcessorMetrics;
import io.sessioninsights.processor.ingest.DltReason;
import io.sessioninsights.processor.ingest.PoisonRecordException;
import io.sessioninsights.processor.store.Store;
import io.sessioninsights.processor.store.StoreUnavailableException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.support.KafkaUtils;
import org.springframework.kafka.support.SendResult;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Publishes poison records to {@code <topic>.dlt} with the original key, value bytes and
 * headers, plus Spring's {@code kafka_dlt-original-*} topic/partition/offset/timestamp headers,
 * {@link WireHeaders#DLT_REASON} and {@link WireHeaders#DLT_CONSUMER} (the consumer group).
 * Exception message and stack trace headers are omitted: the only exception detail is the
 * reason code, so no payload can leak into the DLT headers.
 * <p>
 * {@link #deadLetter} is what the listeners use (ADR-0014): every poison record of a batch in
 * one pass, one wait for all the acks. {@link #recoverer} is the error handler's single-record
 * path, kept for exceptions the listeners do not handle themselves.
 */
public final class DeadLetters {

    private static final Logger log = LoggerFactory.getLogger(DeadLetters.class);
    private static final Map<String, String> DLT_TOPICS = Map.of(
            Topics.TELEMETRY_EVENTS, Topics.TELEMETRY_EVENTS_DLT,
            Topics.REPLAY_CHUNKS, Topics.REPLAY_CHUNKS_DLT);

    private final BatchPublisher publisher;
    private final ProcessorMetrics metrics;
    private final Duration timeout;

    public DeadLetters(KafkaOperations<String, byte[]> template, ProcessorMetrics metrics, Duration timeout) {
        this.publisher = new BatchPublisher(template);
        this.metrics = metrics;
        this.timeout = timeout;
    }

    /**
     * Dead-letters all {@code poison} records and waits for every ack (ADR-0014). Returns
     * normally only when all are published; otherwise throws {@link StoreUnavailableException}
     * ({@link Store#KAFKA}), which the error handler treats as an outage: the batch is retried
     * and nothing is acknowledged. A retry may publish some records a second time.
     */
    public void deadLetter(Map<ConsumerRecord<String, byte[]>, DltReason> poison) {
        if (poison.isEmpty()) {
            return;
        }
        List<CompletableFuture<?>> acks = publisher.publishAll(poison);
        try {
            CompletableFuture.allOf(acks.toArray(CompletableFuture[]::new)).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StoreUnavailableException(Store.KAFKA, "dead-letter publish interrupted");
        } catch (Exception e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new StoreUnavailableException(Store.KAFKA, "dead-letter publish " + cause.getClass().getSimpleName());
        }
        Map<String, Integer> byReason = new TreeMap<>();
        String topic = poison.keySet().iterator().next().topic();
        poison.values().forEach(reason -> {
            metrics.deadLettered(topic, reason.code());
            byReason.merge(reason.code(), 1, Integer::sum);
        });
        // one line per batch, not per record: a poison run can be millions of records
        log.warn("Dead-lettered {} record(s) from {} {}", poison.size(), topic, byReason);
    }

    /** Single-record recoverer for {@link ConsumerErrorHandler}; waits for its ack. */
    public static ConsumerRecordRecoverer recoverer(KafkaOperations<String, byte[]> template, ProcessorMetrics metrics) {
        DeadLetterPublishingRecoverer publisher = new BatchPublisher(template);
        return (record, exception) -> {
            publisher.accept(record, exception);
            String reason = reason(exception);
            metrics.deadLettered(record.topic(), reason);
            log.warn("Dead-lettered {}-{}@{} reason={}", record.topic(), record.partition(), record.offset(), reason);
        };
    }

    static String dltTopic(String topic) {
        String dlt = DLT_TOPICS.get(topic);
        if (dlt == null) {
            throw new IllegalStateException("No dead-letter topic for " + topic);
        }
        return dlt;
    }

    static String reason(Exception exception) {
        DltReason reason = PoisonRecordException.reasonOf(exception);
        return reason == null ? "unknown" : reason.code();
    }

    /**
     * Spring's recoverer, with our headers. Inside {@link #publishAll} it collects each send's
     * future instead of waiting for it, so a batch of poison records costs one wait.
     */
    private static final class BatchPublisher extends DeadLetterPublishingRecoverer {

        private final ThreadLocal<List<CompletableFuture<?>>> collecting = new ThreadLocal<>();

        BatchPublisher(KafkaOperations<String, byte[]> template) {
            // -1: let the producer choose; each .dlt topic has one partition (create-topics.sh)
            super(template, (record, ex) -> new TopicPartition(dltTopic(record.topic()), -1));
            setExceptionHeadersCreator((headers, exception, isKey, names) -> {
                headers.add(new RecordHeader(names.getExceptionInfo().getExceptionFqcn(),
                        PoisonRecordException.class.getName().getBytes(StandardCharsets.UTF_8)));
                headers.add(new RecordHeader(WireHeaders.DLT_REASON, reason(exception).getBytes(StandardCharsets.UTF_8)));
                // several consumer groups read one topic (events writer, session tracker)
                String group = KafkaUtils.getConsumerGroupId();
                if (group != null) {
                    headers.add(new RecordHeader(WireHeaders.DLT_CONSUMER, group.getBytes(StandardCharsets.UTF_8)));
                }
            });
        }

        List<CompletableFuture<?>> publishAll(Map<ConsumerRecord<String, byte[]>, DltReason> poison) {
            List<CompletableFuture<?>> acks = new ArrayList<>(poison.size());
            collecting.set(acks);
            try {
                poison.forEach((record, reason) -> accept(record, null, new PoisonRecordException(reason)));
            } finally {
                collecting.remove();
            }
            return acks;
        }

        @Override
        protected void verifySendResult(KafkaOperations<Object, Object> template, ProducerRecord<Object, Object> out,
                                        CompletableFuture<SendResult<Object, Object>> sendResult, ConsumerRecord<?, ?> in) {
            List<CompletableFuture<?>> acks = collecting.get();
            if (acks == null) {
                super.verifySendResult(template, out, sendResult, in);
            } else {
                acks.add(sendResult != null ? sendResult
                        : CompletableFuture.failedFuture(new KafkaException("dead-letter send failed")));
            }
        }
    }
}
