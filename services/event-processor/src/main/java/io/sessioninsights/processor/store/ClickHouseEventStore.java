package io.sessioninsights.processor.store;

import io.sessioninsights.events.EventRow;
import tools.jackson.core.JsonGenerator;

import java.util.List;

/** {@link EventStore} on ClickHouse {@code events} (inserts; reads are in platform-events). */
public class ClickHouseEventStore implements EventStore {

    static final String TABLE = "events";
    static final List<String> COLUMNS = List.of(
            "event_id", "tenant_id", "site_id", "session_id", "anonymous_id", "end_user_id", "ts", "ingested_at",
            "event_type", "event_name", "url", "path", "page_title", "target_selector", "target_text",
            "error_message", "error_stack", "props");

    private final ClickHouseInserter inserter;

    public ClickHouseEventStore(ClickHouseInserter inserter) {
        this.inserter = inserter;
    }

    @Override
    public void insert(List<EventRow> rows, String deduplicationToken) {
        inserter.insert(TABLE, COLUMNS, rows, ClickHouseEventStore::write, deduplicationToken);
    }

    private static void write(JsonGenerator g, EventRow row) {
        g.writeStringProperty("event_id", row.eventId().toString());
        g.writeStringProperty("tenant_id", row.tenantId().toString());
        g.writeStringProperty("site_id", row.siteId().toString());
        g.writeStringProperty("session_id", row.sessionId().toString());
        g.writeStringProperty("anonymous_id", nullToEmpty(row.anonymousId()));
        if (row.endUserId() == null) {
            g.writeNullProperty("end_user_id");
        } else {
            g.writeStringProperty("end_user_id", row.endUserId().toString());
        }
        g.writeStringProperty("ts", row.ts().toString());
        g.writeStringProperty("ingested_at", row.ingestedAt().toString());
        g.writeStringProperty("event_type", row.eventType());
        g.writeStringProperty("event_name", nullToEmpty(row.eventName()));
        g.writeStringProperty("url", nullToEmpty(row.url()));
        g.writeStringProperty("path", nullToEmpty(row.path()));
        g.writeStringProperty("page_title", nullToEmpty(row.pageTitle()));
        g.writeStringProperty("target_selector", nullToEmpty(row.targetSelector()));
        g.writeStringProperty("target_text", nullToEmpty(row.targetText()));
        g.writeStringProperty("error_message", nullToEmpty(row.errorMessage()));
        g.writeStringProperty("error_stack", nullToEmpty(row.errorStack()));
        g.writeName("props");
        if (row.props() == null || !row.props().isObject()) {
            g.writeStartObject();
            g.writeEndObject();
        } else {
            g.writeTree(row.props());
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
