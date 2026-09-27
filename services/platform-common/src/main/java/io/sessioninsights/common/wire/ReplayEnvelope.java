package io.sessioninsights.common.wire;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/**
 * Kafka value on {@code replay.chunks.v1}, one per chunk, key = {@code sessionId}.
 * Carries the client's {@code payload} or {@code events} unchanged.
 */
public record ReplayEnvelope(
        int schemaVersion,
        UUID tenantId,
        UUID siteId,
        UUID sessionId,
        int chunkSeq,
        Instant receivedAt,
        String payload,
        JsonNode events) {

    @Override
    public String toString() {
        return "ReplayEnvelope[tenantId=" + tenantId + ", sessionId=" + sessionId + ", chunkSeq=" + chunkSeq + "]";
    }
}
