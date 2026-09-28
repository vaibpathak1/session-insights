package io.sessioninsights.processor.store;

import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.query.GenericRecord;
import tools.jackson.core.JsonGenerator;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** {@link ReplayManifestStore} on ClickHouse {@code replay_chunks}. */
public class ClickHouseReplayManifestStore implements ReplayManifestStore {

    static final String TABLE = "replay_chunks";
    static final List<String> COLUMNS = List.of(
            "tenant_id", "site_id", "session_id", "chunk_seq", "object_key", "compressed_bytes", "event_count",
            "first_ts", "last_ts", "has_full_snapshot", "ingested_at");

    private final ClickHouseInserter inserter;
    private final Client client;

    public ClickHouseReplayManifestStore(ClickHouseInserter inserter, Client client) {
        this.inserter = inserter;
        this.client = client;
    }

    @Override
    public void insert(List<ManifestRow> rows, String deduplicationToken) {
        inserter.insert(TABLE, COLUMNS, rows, ClickHouseReplayManifestStore::write, deduplicationToken);
    }

    @Override
    public List<ManifestRow> findChunks(UUID tenantId, UUID sessionId) {
        String sql = """
                SELECT tenant_id, site_id, session_id, chunk_seq, object_key, compressed_bytes, event_count,
                       first_ts, last_ts, has_full_snapshot, ingested_at
                FROM replay_chunks FINAL
                WHERE tenant_id = {tenantId:UUID} AND session_id = {sessionId:UUID}
                ORDER BY chunk_seq""";
        return client.queryAll(sql, Map.of("tenantId", tenantId, "sessionId", sessionId)).stream()
                .map(ClickHouseReplayManifestStore::read)
                .toList();
    }

    private static void write(JsonGenerator g, ManifestRow row) {
        g.writeStringProperty("tenant_id", row.tenantId().toString());
        g.writeStringProperty("site_id", row.siteId().toString());
        g.writeStringProperty("session_id", row.sessionId().toString());
        g.writeNumberProperty("chunk_seq", row.chunkSeq());
        g.writeStringProperty("object_key", row.objectKey());
        g.writeNumberProperty("compressed_bytes", row.compressedBytes());
        g.writeNumberProperty("event_count", row.eventCount());
        g.writeStringProperty("first_ts", row.firstTs().toString());
        g.writeStringProperty("last_ts", row.lastTs().toString());
        g.writeNumberProperty("has_full_snapshot", row.hasFullSnapshot() ? 1 : 0);
        g.writeStringProperty("ingested_at", row.ingestedAt().toString());
    }

    private static ManifestRow read(GenericRecord r) {
        return new ManifestRow(
                r.getUUID("tenant_id"), r.getUUID("site_id"), r.getUUID("session_id"),
                (int) r.getLong("chunk_seq"), r.getString("object_key"), r.getLong("compressed_bytes"),
                (int) r.getLong("event_count"),
                r.getZonedDateTime("first_ts").toInstant(),
                r.getZonedDateTime("last_ts").toInstant(),
                r.getLong("has_full_snapshot") == 1,
                r.getZonedDateTime("ingested_at").toInstant());
    }
}
