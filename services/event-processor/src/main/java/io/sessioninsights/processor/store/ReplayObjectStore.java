package io.sessioninsights.processor.store;

import java.util.UUID;

/** Compressed replay chunk objects (ADR-0003, ADR-0008). */
public interface ReplayObjectStore {

    /** {@code tenants/{tenantId}/sessions/{sessionId}/{chunkSeq, 6 digits}.json.zst} */
    static String objectKey(UUID tenantId, UUID sessionId, int chunkSeq) {
        return "tenants/%s/sessions/%s/%06d.json.zst".formatted(tenantId, sessionId, chunkSeq);
    }

    /**
     * Writes (or overwrites, on redelivery) the object at {@link #objectKey}.
     *
     * @throws StoreUnavailableException on any failure
     */
    void put(UUID tenantId, UUID sessionId, int chunkSeq, byte[] zstdJson);

    /** The stored bytes, or null if there is no such object. */
    byte[] get(UUID tenantId, UUID sessionId, int chunkSeq);
}
