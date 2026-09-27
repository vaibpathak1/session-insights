package io.sessioninsights.common;

/**
 * Single source of truth for Kafka topic names.
 * Must stay in sync with infra/kafka/create-topics.sh (ADR-0004).
 */
public final class Topics {

    public static final String TELEMETRY_EVENTS = "telemetry.events.v1";
    public static final String TELEMETRY_EVENTS_DLT = "telemetry.events.v1.dlt";
    public static final String REPLAY_CHUNKS = "replay.chunks.v1";
    public static final String REPLAY_CHUNKS_DLT = "replay.chunks.v1.dlt";
    public static final String SESSION_LIFECYCLE = "session.lifecycle.v1";
    public static final String ANALYSIS_REQUESTS = "analysis.requests.v1";
    public static final String ANALYSIS_REQUESTS_DLT = "analysis.requests.v1.dlt";

    private Topics() {
    }
}
