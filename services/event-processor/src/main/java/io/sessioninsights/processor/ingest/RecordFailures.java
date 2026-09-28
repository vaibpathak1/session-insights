package io.sessioninsights.processor.ingest;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.listener.BatchListenerFailedException;

import java.util.List;
import java.util.Map;

/**
 * Reports the first bad record of a batch whose good records are already written. The error
 * handler commits the offsets before it, dead-letters it and redelivers the rest; rewriting
 * the rest is harmless (deduplicated), and any further bad record is reported on that pass.
 */
final class RecordFailures {

    private RecordFailures() {
    }

    static void raise(List<ConsumerRecord<String, byte[]>> batch, Map<ConsumerRecord<String, byte[]>, DltReason> poison) {
        if (poison.isEmpty()) {
            return;
        }
        for (ConsumerRecord<String, byte[]> record : batch) {
            DltReason reason = poison.get(record);
            if (reason != null) {
                throw new BatchListenerFailedException("poison record: " + reason.code(),
                        new PoisonRecordException(reason), record);
            }
        }
    }
}
