package io.sessioninsights.collector.ingest;

import io.sessioninsights.collector.config.CollectorProperties;
import io.sessioninsights.collector.config.CollectorProperties.FieldLimits;
import io.sessioninsights.common.privacy.Redactor;
import io.sessioninsights.common.wire.EventBatch;
import io.sessioninsights.common.wire.ReplayBatch;
import io.sessioninsights.common.wire.TelemetryEvent;
import io.sessioninsights.common.wire.WireJson;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Parses and validates batches (task 2.6). Structural problems reject the whole batch
 * ({@code 400}); an event whose client timestamp is outside the accepted window is dropped
 * and counted. Accepted events are redacted, then truncated (redacting first means a cut
 * can never leave a partial email or card number behind).
 */
@Component
public class BatchValidator {

    /** Accepted events, plus how many were dropped for an out-of-window timestamp. */
    public record ValidatedEvents(List<TelemetryEvent> accepted, int droppedOutOfWindow) {
    }

    private final CollectorProperties.Limits limits;
    private final FieldLimits fields;

    public BatchValidator(CollectorProperties properties) {
        this.limits = properties.limits();
        this.fields = limits.fields();
    }

    public EventBatch parseEvents(byte[] body) {
        return parse(body, EventBatch.class);
    }

    public ReplayBatch parseReplay(byte[] body) {
        return parse(body, ReplayBatch.class);
    }

    public ValidatedEvents validate(EventBatch batch, Instant now) {
        if (batch == null || batch.sessionId() == null || batch.events() == null || batch.events().isEmpty()) {
            throw new IngestException(Rejection.INVALID);
        }
        if (batch.events().size() > limits.maxEvents()) {
            throw new IngestException(Rejection.TOO_LARGE);
        }
        long earliest = now.minus(limits.maxEventAge()).toEpochMilli();
        long latest = now.plus(limits.maxClockSkew()).toEpochMilli();

        List<TelemetryEvent> accepted = new ArrayList<>(batch.events().size());
        int dropped = 0;
        for (TelemetryEvent event : batch.events()) {
            if (event == null || event.clientEventId() == null || event.type() == null || event.ts() == null
                    || (event.props() != null && !event.props().isObject())) {
                throw new IngestException(Rejection.INVALID);
            }
            if (event.ts() < earliest || event.ts() > latest) {
                dropped++;
                continue;
            }
            accepted.add(sanitize(event));
        }
        return new ValidatedEvents(accepted, dropped);
    }

    public void validate(ReplayBatch batch) {
        if (batch == null || batch.sessionId() == null || batch.chunkSeq() == null || batch.chunkSeq() < 0) {
            throw new IngestException(Rejection.INVALID);
        }
        boolean hasPayload = batch.payload() != null;
        boolean hasEvents = batch.events() != null && !batch.events().isNull();
        if (hasPayload == hasEvents) {
            throw new IngestException(Rejection.INVALID);   // exactly one of the two
        }
        if (hasEvents && !batch.events().isArray()) {
            throw new IngestException(Rejection.INVALID);
        }
        if (hasPayload) {
            try {
                Base64.getDecoder().decode(batch.payload());
            } catch (IllegalArgumentException notBase64) {
                throw new IngestException(Rejection.INVALID);
            }
        }
    }

    public String anonymousId(EventBatch batch) {
        return truncate(batch.anonymousId() == null ? "" : batch.anonymousId(), fields.anonymousId());
    }

    public String sdkVersion(EventBatch batch) {
        return truncate(batch.sdkVersion(), fields.sdkVersion());
    }

    private TelemetryEvent sanitize(TelemetryEvent e) {
        return new TelemetryEvent(
                e.clientEventId(),
                e.type(),
                e.ts(),
                truncate(Redactor.redactUrl(e.url()), fields.url()),
                truncate(e.path(), fields.path()),
                truncate(e.title(), fields.title()),
                truncate(e.targetSelector(), fields.targetSelector()),
                truncate(Redactor.redact(e.targetText()), fields.targetText()),
                truncate(Redactor.redact(e.errorMessage()), fields.errorMessage()),
                truncate(Redactor.redact(e.errorStack()), fields.errorStack()),
                truncate(e.eventName(), fields.eventName()),
                e.props());
    }

    private <T> T parse(byte[] body, Class<T> type) {
        try {
            return WireJson.mapper().readValue(body, type);
        } catch (JacksonException e) {
            throw new IngestException(Rejection.INVALID);   // never propagate the parser message
        }
    }

    /** Truncates to {@code max} chars without splitting a surrogate pair. */
    static String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        int end = Character.isHighSurrogate(value.charAt(max - 1)) ? max - 1 : max;
        return value.substring(0, end);
    }
}
