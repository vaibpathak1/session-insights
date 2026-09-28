package io.sessioninsights.processor.ingest;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DeduplicationTokenTest {

    @Test
    void sameRecordsSameTokenInAnyOrder() {
        var records = List.of(rec(0, 10), rec(0, 11), rec(1, 5), rec(0, 12));
        assertThat(DeduplicationToken.of(records))
                .isEqualTo(DeduplicationToken.of(List.of(rec(1, 5), rec(0, 12), rec(0, 10), rec(0, 11))))
                .startsWith("telemetry.events.v1:");
    }

    @Test
    void differentOffsetsPartitionsGapsOrTimestampsGiveDifferentTokens() {
        String base = DeduplicationToken.of(List.of(rec(0, 10), rec(0, 11), rec(0, 12)));
        assertThat(DeduplicationToken.of(List.of(rec(0, 10), rec(0, 12)))).isNotEqualTo(base);   // gap
        assertThat(DeduplicationToken.of(List.of(rec(0, 11), rec(0, 12), rec(0, 13)))).isNotEqualTo(base);
        assertThat(DeduplicationToken.of(List.of(rec(1, 10), rec(1, 11), rec(1, 12)))).isNotEqualTo(base);
        // a recreated topic restarts offsets; record timestamps tell the batches apart
        assertThat(DeduplicationToken.of(List.of(rec(0, 10, 99L), rec(0, 11), rec(0, 12)))).isNotEqualTo(base);
    }

    private static ConsumerRecord<String, byte[]> rec(int partition, long offset) {
        return rec(partition, offset, 1_000L + offset);
    }

    private static ConsumerRecord<String, byte[]> rec(int partition, long offset, long timestamp) {
        return new ConsumerRecord<>("telemetry.events.v1", partition, offset, timestamp,
                org.apache.kafka.common.record.TimestampType.CREATE_TIME, 0, 0, "k", new byte[0],
                new org.apache.kafka.common.header.internals.RecordHeaders(), java.util.Optional.empty());
    }
}
