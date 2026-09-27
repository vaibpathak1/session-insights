package io.sessioninsights.common.wire;

import java.time.Instant;
import java.util.UUID;

/**
 * Kafka value on {@code telemetry.events.v1}, one per event, key = {@code sessionId}.
 * {@code tenantId}, {@code siteId} and {@code receivedAt} are assigned by the collector.
 */
public record TelemetryEnvelope(
        int schemaVersion,
        UUID tenantId,
        UUID siteId,
        UUID sessionId,
        String anonymousId,
        String sdkVersion,
        Instant receivedAt,
        TelemetryEvent event) {

    @Override
    public String toString() {
        return "TelemetryEnvelope[tenantId=" + tenantId + ", sessionId=" + sessionId + ", event=" + event + "]";
    }
}
