package io.sessioninsights.processor.ingest;

/**
 * Why a record was dead-lettered; the {@code si-dlt-reason} header value and the
 * {@code reason} tag of {@code si.processor.dlt}. Only record-level problems appear here:
 * a store outage never dead-letters anything (ADR-0011).
 */
public enum DltReason {
    /** The value is not a JSON envelope of the topic's type. */
    DESERIALIZATION,
    /** An event {@code type} that is not an {@code EventType}. */
    BAD_TYPE,
    UNSUPPORTED_SCHEMA_VERSION,
    MISSING_TENANT,
    MISSING_SITE,
    MISSING_SESSION,
    MISSING_EVENT,
    MISSING_EVENT_ID,
    MISSING_TYPE,
    MISSING_TS,
    /** A replay chunk with a negative sequence, no events, or events without timestamps. */
    INVALID_CHUNK,
    /** A replay chunk in the base64 {@code payload} form, which has no defined encoding yet. */
    UNSUPPORTED_PAYLOAD,
    /** Passed validation, but ClickHouse (reachable) rejected the row itself. */
    STORE_REJECTED;

    public String code() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
