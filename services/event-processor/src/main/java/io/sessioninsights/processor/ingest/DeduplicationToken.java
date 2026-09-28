package io.sessioninsights.processor.ingest;

import org.apache.kafka.clients.consumer.ConsumerRecord;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/**
 * ClickHouse {@code insert_deduplication_token} for the records of one insert: the topic and,
 * per partition, the runs of consecutive offsets, each with its first record's timestamp.
 * A retried insert of the same records yields the same token and is dropped at insert time.
 * A batch may span partitions and, after bad records are removed, have gaps, so every run
 * is included. The timestamps keep a recreated topic, whose offsets restart at 0, from
 * colliding with old tokens still in the deduplication window.
 */
final class DeduplicationToken {

    private DeduplicationToken() {
    }

    static String of(List<? extends ConsumerRecord<?, ?>> records) {
        List<? extends ConsumerRecord<?, ?>> sorted = records.stream()
                .sorted(Comparator.<ConsumerRecord<?, ?>>comparingInt(ConsumerRecord::partition)
                        .thenComparingLong(ConsumerRecord::offset))
                .toList();
        StringBuilder runs = new StringBuilder();
        ConsumerRecord<?, ?> runStart = null;
        ConsumerRecord<?, ?> previous = null;
        for (ConsumerRecord<?, ?> record : sorted) {
            boolean continues = previous != null && previous.partition() == record.partition()
                    && previous.offset() + 1 == record.offset();
            if (!continues) {
                appendRun(runs, runStart, previous);
                runStart = record;
            }
            previous = record;
        }
        appendRun(runs, runStart, previous);
        String topic = sorted.isEmpty() ? "" : sorted.getFirst().topic();
        return topic + ":" + sha256(runs.toString());
    }

    private static void appendRun(StringBuilder runs, ConsumerRecord<?, ?> first, ConsumerRecord<?, ?> last) {
        if (first != null) {
            runs.append(first.partition()).append(':').append(first.offset()).append('-').append(last.offset())
                    .append('@').append(first.timestamp()).append(';');
        }
    }

    private static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
