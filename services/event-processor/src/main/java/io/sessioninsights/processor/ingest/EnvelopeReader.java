package io.sessioninsights.processor.ingest;

import io.sessioninsights.common.wire.EventType;
import io.sessioninsights.common.wire.ReplayEnvelope;
import io.sessioninsights.common.wire.TelemetryEnvelope;
import io.sessioninsights.common.wire.WireHeaders;
import io.sessioninsights.common.wire.WireJson;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.exc.InvalidFormatException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turns raw Kafka values into validated envelopes, or a {@link PoisonRecordException} with
 * the reason. The listener sees raw bytes so a dead-lettered record keeps its original value
 * byte for byte, whether it failed to parse or to validate.
 */
public final class EnvelopeReader {

    private static final JsonMapper MAPPER = WireJson.mapper();

    private EnvelopeReader() {
    }

    public static TelemetryEnvelope telemetry(byte[] value) {
        TelemetryEnvelope envelope = parse(value, TelemetryEnvelope.class);
        requireCommon(envelope.schemaVersion(), envelope.tenantId() != null, envelope.siteId() != null,
                envelope.sessionId() != null);
        if (envelope.event() == null) {
            throw new PoisonRecordException(DltReason.MISSING_EVENT);
        }
        if (envelope.event().clientEventId() == null) {
            throw new PoisonRecordException(DltReason.MISSING_EVENT_ID);
        }
        if (envelope.event().type() == null) {
            throw new PoisonRecordException(DltReason.MISSING_TYPE);
        }
        if (envelope.event().ts() == null) {
            throw new PoisonRecordException(DltReason.MISSING_TS);
        }
        if (envelope.receivedAt() == null) {
            throw new PoisonRecordException(DltReason.MISSING_TS);
        }
        return envelope;
    }

    public static ReplayEnvelope replay(byte[] value) {
        ReplayEnvelope envelope = parse(value, ReplayEnvelope.class);
        requireCommon(envelope.schemaVersion(), envelope.tenantId() != null, envelope.siteId() != null,
                envelope.sessionId() != null);
        if (envelope.receivedAt() == null) {
            throw new PoisonRecordException(DltReason.MISSING_TS);
        }
        if (envelope.events() == null && envelope.payload() != null) {
            throw new PoisonRecordException(DltReason.UNSUPPORTED_PAYLOAD);
        }
        if (envelope.chunkSeq() < 0 || envelope.payload() != null || !validEvents(envelope.events())) {
            throw new PoisonRecordException(DltReason.INVALID_CHUNK);
        }
        return envelope;
    }

    /** A non-empty array of rrweb events, each an object with a numeric {@code timestamp}. */
    private static boolean validEvents(JsonNode events) {
        if (events == null || !events.isArray() || events.isEmpty()) {
            return false;
        }
        for (JsonNode event : events) {
            if (!event.isObject() || !event.path("timestamp").isNumber()) {
                return false;
            }
        }
        return true;
    }

    private static void requireCommon(int schemaVersion, boolean tenant, boolean site, boolean session) {
        if (schemaVersion != WireHeaders.CURRENT_SCHEMA_VERSION) {
            throw new PoisonRecordException(DltReason.UNSUPPORTED_SCHEMA_VERSION);
        }
        if (!tenant) {
            throw new PoisonRecordException(DltReason.MISSING_TENANT);
        }
        if (!site) {
            throw new PoisonRecordException(DltReason.MISSING_SITE);
        }
        if (!session) {
            throw new PoisonRecordException(DltReason.MISSING_SESSION);
        }
    }

    private static <T> T parse(byte[] value, Class<T> type) {
        if (value == null || value.length == 0) {
            throw new PoisonRecordException(DltReason.DESERIALIZATION);
        }
        try {
            T envelope = MAPPER.readValue(value, type);
            if (envelope == null) {
                throw new PoisonRecordException(DltReason.DESERIALIZATION);
            }
            return envelope;
        } catch (InvalidFormatException e) {
            throw new PoisonRecordException(
                    e.getTargetType() == EventType.class ? DltReason.BAD_TYPE : DltReason.DESERIALIZATION);
        } catch (JacksonException e) {
            throw new PoisonRecordException(DltReason.DESERIALIZATION);
        }
    }
}
