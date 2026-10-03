package io.sessioninsights.processor.store;

import io.sessioninsights.events.ManifestRow;
import tools.jackson.core.JsonGenerator;

import java.util.List;

/** {@link ReplayManifestStore} on ClickHouse {@code replay_chunks}. */
public class ClickHouseReplayManifestStore implements ReplayManifestStore {

    static final String TABLE = "replay_chunks";
    static final List<String> COLUMNS = List.of(
            "tenant_id", "site_id", "session_id", "chunk_seq", "object_key", "compressed_bytes", "event_count",
            "first_ts", "last_ts", "has_full_snapshot", "ingested_at");

    private final ClickHouseInserter inserter;

    public ClickHouseReplayManifestStore(ClickHouseInserter inserter) {
        this.inserter = inserter;
    }

    @Override
    public void insert(List<ManifestRow> rows, String deduplicationToken) {
        inserter.insert(TABLE, COLUMNS, rows, ClickHouseReplayManifestStore::write, deduplicationToken);
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
}
