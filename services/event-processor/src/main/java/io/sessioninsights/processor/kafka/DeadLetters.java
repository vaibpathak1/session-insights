package io.sessioninsights.processor.kafka;

import io.sessioninsights.common.Topics;
import io.sessioninsights.common.wire.WireHeaders;
import io.sessioninsights.processor.ProcessorMetrics;
import io.sessioninsights.processor.ingest.DltReason;
import io.sessioninsights.processor.ingest.PoisonRecordException;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Publishes poison records to {@code <topic>.dlt} with the original key, value bytes and
 * headers, plus Spring's {@code kafka_dlt-original-*} topic/partition/offset/timestamp headers
 * and {@link WireHeaders#DLT_REASON}. Exception message and stack trace headers are omitted:
 * the only exception detail is the reason code, so no payload can leak into the DLT headers.
 */
public final class DeadLetters {

    private static final Logger log = LoggerFactory.getLogger(DeadLetters.class);
    private static final Map<String, String> DLT_TOPICS = Map.of(
            Topics.TELEMETRY_EVENTS, Topics.TELEMETRY_EVENTS_DLT,
            Topics.REPLAY_CHUNKS, Topics.REPLAY_CHUNKS_DLT);

    private DeadLetters() {
    }

    public static ConsumerRecordRecoverer recoverer(KafkaOperations<String, byte[]> template, ProcessorMetrics metrics) {
        DeadLetterPublishingRecoverer publisher = new DeadLetterPublishingRecoverer(template,
                // -1: let the producer choose; each .dlt topic has one partition (create-topics.sh)
                (record, ex) -> new TopicPartition(dltTopic(record.topic()), -1));
        publisher.setExceptionHeadersCreator((headers, exception, isKey, names) -> {
            headers.add(new RecordHeader(names.getExceptionInfo().getExceptionFqcn(),
                    PoisonRecordException.class.getName().getBytes(StandardCharsets.UTF_8)));
            headers.add(new RecordHeader(WireHeaders.DLT_REASON,
                    reason(exception).getBytes(StandardCharsets.UTF_8)));
        });
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
}
