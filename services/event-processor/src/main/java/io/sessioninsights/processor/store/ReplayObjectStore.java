package io.sessioninsights.processor.store;

import java.util.UUID;

/**
 * Writes compressed replay chunk objects (ADR-0003, ADR-0008) at
 * {@code platform-events} {@code ReplayObjects.objectKey}. Reads: {@code ReplayObjectReader}.
 */
public interface ReplayObjectStore {

    /**
     * Writes (or overwrites, on redelivery) the chunk's object.
     *
     * @throws StoreUnavailableException on any failure
     */
    void put(UUID tenantId, UUID sessionId, int chunkSeq, byte[] zstdJson);
}
