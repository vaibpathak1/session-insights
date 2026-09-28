package io.sessioninsights.processor.ingest;

import io.sessioninsights.common.wire.TelemetryEnvelope;
import io.sessioninsights.common.wire.TelemetryEvent;
import io.sessioninsights.processor.store.EventRow;

import java.time.Instant;

/**
 * Envelope → {@code events} row, column for column (V1). Text fields arrive already masked by
 * the SDK and redacted by the collector; they are stored as received.
 */
final class EventRows {

    private EventRows() {
    }

    static EventRow of(TelemetryEnvelope envelope) {
        TelemetryEvent event = envelope.event();
        return new EventRow(
                event.clientEventId(),
                envelope.tenantId(),
                envelope.siteId(),
                envelope.sessionId(),
                envelope.anonymousId(),
                null,   // end_user_id: resolved from IDENTIFY with session lifecycle (Phase 5)
                Instant.ofEpochMilli(event.ts()),
                envelope.receivedAt(),
                event.type().name(),
                event.eventName(),
                event.url(),
                event.path(),
                event.title(),
                event.targetSelector(),
                event.targetText(),
                event.errorMessage(),
                event.errorStack(),
                event.props());
    }
}
