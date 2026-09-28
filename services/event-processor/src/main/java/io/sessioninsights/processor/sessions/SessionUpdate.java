package io.sessioninsights.processor.sessions;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * What one batch says about one session, aggregated from its events (task 5.3). Applying it
 * is idempotent: only minima, maxima and first-known values, never counters, so a
 * redelivered or reordered batch cannot change the result.
 *
 * @param firstTs      earliest client event time in the batch
 * @param lastTs       latest client event time in the batch
 * @param lastReceived latest server receive time: drives the idle close
 * @param entryAt      earliest NAVIGATION time in the batch, or null
 * @param entryUrl     URL of that NAVIGATION (already redacted by the collector), or null
 */
public record SessionUpdate(
        UUID tenantId,
        UUID siteId,
        UUID sessionId,
        String anonymousId,
        Instant firstTs,
        Instant lastTs,
        Instant lastReceived,
        Instant entryAt,
        String entryUrl,
        String platform,
        String browser) {

    public SessionUpdate {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(siteId, "siteId");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(firstTs, "firstTs");
        Objects.requireNonNull(lastTs, "lastTs");
        Objects.requireNonNull(lastReceived, "lastReceived");
    }

    @Override
    public String toString() {
        return "SessionUpdate[tenantId=" + tenantId + ", sessionId=" + sessionId + "]";   // no content in logs
    }
}
