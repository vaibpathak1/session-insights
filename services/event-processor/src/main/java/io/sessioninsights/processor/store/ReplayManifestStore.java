package io.sessioninsights.processor.store;

import java.util.List;
import java.util.UUID;

/** The replay chunk manifest, so the player never needs an object-storage LIST. */
public interface ReplayManifestStore {

    /** Same contract as {@link EventStore#insert}. */
    void insert(List<ManifestRow> rows, String deduplicationToken);

    /** A session's chunks for one tenant, deduplicated, in {@code chunk_seq} order. */
    List<ManifestRow> findChunks(UUID tenantId, UUID sessionId);
}
