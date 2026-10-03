package io.sessioninsights.events;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One row of ClickHouse {@code events} (V1). Every row carries its {@code tenantId}
 * (ADR-0008). String columns are never null: absent values are {@code ""}, the column default.
 * {@code ingestedAt} is the collector's receive time, so a redelivered event maps to an
 * identical row.
 */
public record EventRow(
        UUID eventId,
        UUID tenantId,
        UUID siteId,
        UUID sessionId,
        String anonymousId,
        UUID endUserId,
        Instant ts,
        Instant ingestedAt,
        String eventType,
        String eventName,
        String url,
        String path,
        String pageTitle,
        String targetSelector,
        String targetText,
        String errorMessage,
        String errorStack,
        JsonNode props) {

    public EventRow {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(siteId, "siteId");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(ts, "ts");
        Objects.requireNonNull(ingestedAt, "ingestedAt");
        Objects.requireNonNull(eventType, "eventType");
    }

    @Override
    public String toString() {
        return "EventRow[tenantId=" + tenantId + ", sessionId=" + sessionId + ", eventId=" + eventId
                + ", type=" + eventType + "]";   // no content in logs
    }
}
