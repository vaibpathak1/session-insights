package io.sessioninsights.processor.ingest;

import io.sessioninsights.processor.store.StoreRejectedException;
import org.apache.kafka.clients.consumer.ConsumerRecord;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Writes a batch with one insert; if the store rejects the data, splits the batch in halves
 * until the rejected records are isolated, writing everything else (ADR-0011). Costs about
 * {@code 2·log2(n)} extra inserts per bad record, only when one is present. Every sub-insert
 * gets its own deduplication token, derived from its records, so retrying the whole batch
 * after an outage mid-bisection repeats the same tokens and writes nothing twice.
 * {@link io.sessioninsights.processor.store.StoreUnavailableException} propagates.
 */
final class BisectingWriter {

    private BisectingWriter() {
    }

    /** A row and the record it came from. */
    record Item<T>(ConsumerRecord<String, byte[]> record, T row) {
    }

    /** Writes the items and returns those the store rejected. */
    static <T> List<Item<T>> write(List<Item<T>> items, BiConsumer<List<T>, String> insert) {
        List<Item<T>> rejected = new ArrayList<>();
        write(items, insert, rejected);
        return rejected;
    }

    private static <T> void write(List<Item<T>> items, BiConsumer<List<T>, String> insert, List<Item<T>> rejected) {
        if (items.isEmpty()) {
            return;
        }
        try {
            insert.accept(items.stream().map(Item::row).toList(),
                    DeduplicationToken.of(items.stream().map(Item::record).toList()));
        } catch (StoreRejectedException e) {
            if (items.size() == 1) {
                rejected.add(items.getFirst());
                return;
            }
            int middle = items.size() / 2;
            write(items.subList(0, middle), insert, rejected);
            write(items.subList(middle, items.size()), insert, rejected);
        }
    }
}
