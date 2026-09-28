package io.sessioninsights.processor.ingest;

import io.sessioninsights.common.Topics;
import io.sessioninsights.processor.ProcessorMetrics;
import io.sessioninsights.processor.store.EventRow;
import io.sessioninsights.processor.store.EventStore;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code telemetry.events.v1} → ClickHouse {@code events} (task 4.3). One insert per poll;
 * the batch is acknowledged only after it returns (AckMode.BATCH). Bad records are written
 * around, then reported with {@link RecordFailures#raise}, so only they reach the DLT.
 */
@Component
public class EventsListener {

    public static final String ID = "events";

    private final EventStore store;
    private final ProcessorMetrics metrics;

    public EventsListener(EventStore store, ProcessorMetrics metrics) {
        this.store = store;
        this.metrics = metrics;
    }

    @KafkaListener(id = ID, topics = Topics.TELEMETRY_EVENTS, groupId = "${processor.kafka.events.group-id}",
            concurrency = "${processor.kafka.concurrency}", batch = "true",
            properties = {
                    "max.poll.records=${processor.kafka.events.max-poll-records}",
                    "fetch.min.bytes=${processor.kafka.events.fetch-min-bytes}",
                    "fetch.max.wait.ms=${processor.kafka.events.fetch-max-wait-ms}"})
    public void onBatch(List<ConsumerRecord<String, byte[]>> records) {
        metrics.batch(Topics.TELEMETRY_EVENTS, records.size());
        List<BisectingWriter.Item<EventRow>> rows = new ArrayList<>(records.size());
        Map<ConsumerRecord<String, byte[]>, DltReason> poison = new LinkedHashMap<>();
        for (ConsumerRecord<String, byte[]> record : records) {
            try {
                rows.add(new BisectingWriter.Item<>(record, EventRows.of(EnvelopeReader.telemetry(record.value()))));
            } catch (PoisonRecordException e) {
                poison.put(record, e.reason());
            }
        }
        for (BisectingWriter.Item<EventRow> rejected : BisectingWriter.write(rows, store::insert)) {
            poison.put(rejected.record(), DltReason.STORE_REJECTED);
        }
        RecordFailures.raise(records, poison);
    }
}
