package io.sessioninsights.events;

import java.util.UUID;

/** Object keys of replay chunks (ADR-0003, ADR-0008). */
public final class ReplayObjects {

    private ReplayObjects() {
    }

    /** {@code tenants/{tenantId}/sessions/{sessionId}/{chunkSeq, 6 digits}.json.zst} */
    public static String objectKey(UUID tenantId, UUID sessionId, int chunkSeq) {
        return "tenants/%s/sessions/%s/%06d.json.zst".formatted(tenantId, sessionId, chunkSeq);
    }
}
