package io.sessioninsights.events;

import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.query.GenericRecord;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Reads the replay chunk manifest ({@code replay_chunks}, {@code FINAL}), one tenant at a time. */
public class ManifestReader {

    private static final String SELECT = """
            SELECT tenant_id, site_id, session_id, chunk_seq, object_key, compressed_bytes, event_count,
                   first_ts, last_ts, has_full_snapshot, ingested_at
            FROM replay_chunks FINAL
            WHERE tenant_id = {tenantId:UUID} AND session_id = {sessionId:UUID}""";

    private final Client client;

    public ManifestReader(Client client) {
        this.client = client;
    }

    /** A session's chunks in {@code chunk_seq} order. */
    public List<ManifestRow> findChunks(UUID tenantId, UUID sessionId) {
        return client.queryAll(SELECT + " ORDER BY chunk_seq", Map.of("tenantId", tenantId, "sessionId", sessionId))
                .stream().map(ManifestReader::read).toList();
    }

    /** One chunk, if it belongs to this tenant's session. */
    public Optional<ManifestRow> findChunk(UUID tenantId, UUID sessionId, int chunkSeq) {
        return client.queryAll(SELECT + " AND chunk_seq = {seq:UInt32}",
                        Map.of("tenantId", tenantId, "sessionId", sessionId, "seq", chunkSeq))
                .stream().map(ManifestReader::read).findFirst();
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
