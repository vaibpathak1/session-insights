package io.sessioninsights.common.wire;

import java.time.Instant;
import java.util.UUID;

/**
 * Kafka value on {@code telemetry.events.v1}, one per event, key = {@code sessionId}.
 * {@code tenantId}, {@code siteId} and {@code receivedAt} are assigned by the collector.
 * {@code userAgent} is the request's raw {@code User-Agent} (truncated; null if absent),
 * added in Phase 5 without a schema version change: consumers ignore unknown fields.
 */
public record TelemetryEnvelope(
        int schemaVersion,
        UUID tenantId,
        UUID siteId,
        UUID sessionId,
        String anonymousId,
        String sdkVersion,
        Instant receivedAt,
        TelemetryEvent event,
        String userAgent) {

    /** Without a user agent (non-browser senders, tests, records written before Phase 5). */
    public TelemetryEnvelope(int schemaVersion, UUID tenantId, UUID siteId, UUID sessionId, String anonymousId,
                             String sdkVersion, Instant receivedAt, TelemetryEvent event) {
        this(schemaVersion, tenantId, siteId, sessionId, anonymousId, sdkVersion, receivedAt, event, null);
    }

    @Override
    public String toString() {
        return "TelemetryEnvelope[tenantId=" + tenantId + ", sessionId=" + sessionId + ", event=" + event + "]";
    }
}
