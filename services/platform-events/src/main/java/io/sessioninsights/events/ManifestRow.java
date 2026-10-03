package io.sessioninsights.events;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** One row of ClickHouse {@code replay_chunks} (V2): where a stored chunk lives and what it holds. */
public record ManifestRow(
        UUID tenantId,
        UUID siteId,
        UUID sessionId,
        int chunkSeq,
        String objectKey,
        long compressedBytes,
        int eventCount,
        Instant firstTs,
        Instant lastTs,
        boolean hasFullSnapshot,
        Instant ingestedAt) {

    public ManifestRow {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(siteId, "siteId");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(objectKey, "objectKey");
        Objects.requireNonNull(firstTs, "firstTs");
        Objects.requireNonNull(lastTs, "lastTs");
        Objects.requireNonNull(ingestedAt, "ingestedAt");
    }
}
