package io.sessioninsights.common.wire;

/**
 * Structured event types. Values are stored verbatim in ClickHouse {@code events.event_type}
 * (platform-db {@code clickhouse/V1__events.sql}); keep both lists identical.
 */
public enum EventType {
    CLICK,
    DEAD_CLICK,
    RAGE_CLICK,
    ERROR_CLICK,
    NAVIGATION,
    INPUT,
    SCROLL,
    CONSOLE_ERROR,
    EXCEPTION,
    IDENTIFY,
    CUSTOM,
    NETWORK
}
