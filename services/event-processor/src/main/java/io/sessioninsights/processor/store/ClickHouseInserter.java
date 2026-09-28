package io.sessioninsights.processor.store;

import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.DataStreamWriter;
import com.clickhouse.client.api.ServerException;
import com.clickhouse.client.api.insert.InsertResponse;
import com.clickhouse.client.api.insert.InsertSettings;
import com.clickhouse.data.ClickHouseFormat;
import io.sessioninsights.processor.ProcessorMetrics;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.json.JsonMapper;

import java.io.OutputStream;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;

/**
 * One {@code INSERT ... FORMAT JSONEachRow} per call, streamed straight from the rows.
 * <p>
 * JSONEachRow rather than RowBinary: {@code events.props} uses ClickHouse's {@code JSON} type,
 * whose RowBinary encoding is intricate and version-dependent, while JSONEachRow parses it
 * natively; rows are written by name, so column order cannot drift. The extra CPU is small
 * at the single-node target.
 * <p>
 * {@code insert_deduplication_token} makes a retried identical batch a no-op at insert time;
 * this needs {@code non_replicated_deduplication_window} (ClickHouse V2 migration).
 */
public final class ClickHouseInserter {

    private final Client client;
    private final JsonMapper mapper;
    private final Duration timeout;
    private final ProcessorMetrics metrics;

    public ClickHouseInserter(Client client, JsonMapper mapper, Duration timeout, ProcessorMetrics metrics) {
        this.client = client;
        this.mapper = mapper;
        this.timeout = timeout;
        this.metrics = metrics;
    }

    /**
     * Inserts all rows or none.
     *
     * @throws StoreRejectedException    ClickHouse is up and rejected the data
     * @throws StoreUnavailableException any other failure
     */
    public <T> void insert(String table, List<String> columns, List<T> rows,
                           BiConsumer<JsonGenerator, T> rowWriter, String deduplicationToken) {
        if (rows.isEmpty()) {
            return;
        }
        InsertSettings settings = new InsertSettings()
                .setDeduplicationToken(deduplicationToken)
                // rows carry ISO-8601 timestamps ("2026-09-28T10:00:00.123Z")
                .serverSetting("date_time_input_format", "best_effort");
        DataStreamWriter writer = out -> writeRows(out, rows, rowWriter);
        long start = System.nanoTime();
        try (InsertResponse ignored = client.insert(table, columns, writer, ClickHouseFormat.JSONEachRow, settings)
                .get(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            metrics.inserted(table, rows.size(), System.nanoTime() - start, true);
        } catch (ExecutionException e) {
            throw failed(table, e.getCause() == null ? e : e.getCause(), start);
        } catch (TimeoutException | RuntimeException e) {
            throw failed(table, e, start);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw failed(table, e, start);
        }
    }

    public boolean ping() {
        try {
            return client.ping(Math.min(timeout.toMillis(), 5_000));
        } catch (RuntimeException e) {
            return false;
        }
    }

    private RuntimeException failed(String table, Throwable failure, long start) {
        metrics.inserted(table, 0, System.nanoTime() - start, false);
        ServerException server = ClickHouseErrors.serverException(failure);
        // a data error from a server we can still reach is about the rows; anything else,
        // including a "data error" from a server that no longer answers, is an outage
        if (ClickHouseErrors.isDataError(server) && ping()) {
            return new StoreRejectedException(server.getCode());
        }
        return new StoreUnavailableException(Store.CLICKHOUSE, ClickHouseErrors.describe(failure));
    }

    private <T> void writeRows(OutputStream out, List<T> rows, BiConsumer<JsonGenerator, T> rowWriter) {
        // one generator for the whole stream; JSONEachRow wants one object per line
        try (JsonGenerator generator = mapper.createGenerator(new NonClosingOutputStream(out))) {
            for (T row : rows) {
                generator.writeStartObject();
                rowWriter.accept(generator, row);
                generator.writeEndObject();
                generator.writeRaw('\n');
            }
        }
    }

    /** The client owns the request stream; the generator must not close it. */
    private static final class NonClosingOutputStream extends java.io.FilterOutputStream {
        NonClosingOutputStream(OutputStream out) {
            super(out);
        }

        @Override
        public void write(byte[] b, int off, int len) throws java.io.IOException {
            out.write(b, off, len);
        }

        @Override
        public void close() throws java.io.IOException {
            flush();
        }
    }
}
