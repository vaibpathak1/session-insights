package io.sessioninsights.processor.ingest;

import io.sessioninsights.processor.store.StoreRejectedException;
import io.sessioninsights.processor.store.StoreUnavailableException;
import io.sessioninsights.processor.store.Store;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BisectingWriterTest {

    /** Rejects (all-or-nothing, like ClickHouse) any insert containing a negative row. */
    private final List<Integer> written = new ArrayList<>();
    private final Set<String> tokens = new HashSet<>();
    private int inserts;

    private void insert(List<Integer> rows, String token) {
        inserts++;
        tokens.add(token);
        if (rows.stream().anyMatch(r -> r < 0)) {
            throw new StoreRejectedException(117);
        }
        written.addAll(rows);
    }

    @Test
    void cleanBatchIsOneInsert() {
        assertThat(BisectingWriter.write(items(0, 1, 2, 3), this::insert)).isEmpty();
        assertThat(written).containsExactly(0, 1, 2, 3);
        assertThat(inserts).isEqualTo(1);
    }

    @Test
    void isolatesRejectedRowsAndWritesEveryOtherRowExactlyOnce() {
        List<Integer> rows = new ArrayList<>(IntStream.range(0, 2000).boxed().toList());
        rows.set(777, -1);
        rows.set(1500, -2);
        var rejected = BisectingWriter.write(items(rows.stream().mapToInt(Integer::intValue).toArray()), this::insert);

        assertThat(rejected).extracting(BisectingWriter.Item::row).containsExactly(-1, -2);
        assertThat(written).hasSize(1998).doesNotHaveDuplicates().doesNotContain(-1, -2);
        assertThat(inserts).isLessThan(2 * 2 * 11 + 1);   // ~2·log2(n) per bad row
        assertThat(tokens).hasSize(inserts);               // every sub-insert has its own token
    }

    @Test
    void outagesPropagate() {
        assertThatThrownBy(() -> BisectingWriter.write(items(1, 2), (rows, token) -> {
            throw new StoreUnavailableException(Store.CLICKHOUSE, "test");
        })).isInstanceOf(StoreUnavailableException.class);
    }

    private static List<BisectingWriter.Item<Integer>> items(int... rows) {
        List<BisectingWriter.Item<Integer>> items = new ArrayList<>();
        for (int i = 0; i < rows.length; i++) {
            items.add(new BisectingWriter.Item<>(new ConsumerRecord<>("t", 0, i, "k", new byte[0]), rows[i]));
        }
        return items;
    }
}
