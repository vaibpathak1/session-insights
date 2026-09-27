package io.sessioninsights.common.wire;

import tools.jackson.databind.JsonNode;

import java.util.UUID;

/**
 * One structured event as sent by the SDK. {@code ts} is the client clock in epoch
 * milliseconds; {@code clientEventId} is the idempotency key downstream (ADR-0004).
 */
public record TelemetryEvent(
        UUID clientEventId,
        EventType type,
        Long ts,
        String url,
        String path,
        String title,
        String targetSelector,
        String targetText,
        String errorMessage,
        String errorStack,
        String eventName,
        JsonNode props) {

    @Override
    public String toString() {
        return "TelemetryEvent[clientEventId=" + clientEventId + ", type=" + type + "]";   // no content in logs
    }
}
