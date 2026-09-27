package io.sessioninsights.common.wire;

import java.util.List;
import java.util.UUID;

/**
 * Inbound {@code POST /v1/events} body. {@code siteKey} is optional here: the collector
 * prefers the {@code X-SI-Key} header or {@code ?k=} query parameter. Tenant and site ids
 * are never read from the client.
 */
public record EventBatch(
        String siteKey,
        UUID sessionId,
        String anonymousId,
        String sdkVersion,
        List<TelemetryEvent> events) {

    @Override
    public String toString() {
        return "EventBatch[sessionId=" + sessionId + ", events=" + (events == null ? 0 : events.size()) + "]";
    }
}
