package io.sessioninsights.common.wire;

import java.time.Instant;
import java.util.UUID;

/**
 * Kafka value on {@code session.lifecycle.v1} (compacted), key = {@code sessionId}: the
 * latest known state of a closed session (Phase 5, task 5.4). {@code CLOSED} when the closer
 * first closes it; {@code UPDATED} when late events changed it afterwards. Delivery is at
 * least once: a consumer may see the same state twice.
 */
public record SessionLifecycleEvent(
        int schemaVersion,
        Type type,
        UUID tenantId,
        UUID siteId,
        UUID sessionId,
        Instant startedAt,
        Instant endedAt,
        long durationMs,
        int pageCount,
        int errorCount,
        Instant publishedAt) {

    public enum Type {
        CLOSED,
        UPDATED
    }
}
