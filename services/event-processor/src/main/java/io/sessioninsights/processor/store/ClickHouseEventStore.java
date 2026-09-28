package io.sessioninsights.processor.store;

import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.query.GenericRecord;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** {@link EventStore} on ClickHouse {@code events}. Reads always filter on {@code tenant_id} first. */
public class ClickHouseEventStore implements EventStore {

    static final String TABLE = "events";
    static final List<String> COLUMNS = List.of(
            "event_id", "tenant_id", "site_id", "session_id", "anonymous_id", "end_user_id", "ts", "ingested_at",
            "event_type", "event_name", "url", "path", "page_title", "target_selector", "target_text",
            "error_message", "error_stack", "props");

    private final ClickHouseInserter inserter;
    private final Client client;
    private final JsonMapper mapper;

    public ClickHouseEventStore(ClickHouseInserter inserter, Client client, JsonMapper mapper) {
        this.inserter = inserter;
        this.client = client;
        this.mapper = mapper;
    }

    @Override
    public void insert(List<EventRow> rows, String deduplicationToken) {
        inserter.insert(TABLE, COLUMNS, rows, ClickHouseEventStore::write, deduplicationToken);
    }

    @Override
    public List<EventRow> findEvents(UUID tenantId, UUID sessionId) {
        String sql = """
                SELECT event_id, tenant_id, site_id, session_id, anonymous_id, end_user_id, ts, ingested_at,
                       event_type, event_name, url, path, page_title, target_selector, target_text,
                       error_message, error_stack, toJSONString(props) AS props_json
                FROM events FINAL
                WHERE tenant_id = {tenantId:UUID} AND session_id = {sessionId:UUID}
                ORDER BY ts, event_id""";
        return client.queryAll(sql, Map.of("tenantId", tenantId, "sessionId", sessionId)).stream()
                .map(this::read)
                .toList();
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

    private EventRow read(GenericRecord r) {
        Object endUser = r.getObject("end_user_id");
        return new EventRow(
                r.getUUID("event_id"), r.getUUID("tenant_id"), r.getUUID("site_id"), r.getUUID("session_id"),
                r.getString("anonymous_id"),
                endUser == null ? null : UUID.fromString(endUser.toString()),
                r.getZonedDateTime("ts").toInstant(),
                r.getZonedDateTime("ingested_at").toInstant(),
                r.getString("event_type"), r.getString("event_name"), r.getString("url"), r.getString("path"),
                r.getString("page_title"), r.getString("target_selector"), r.getString("target_text"),
                r.getString("error_message"), r.getString("error_stack"),
                mapper.readTree(r.getString("props_json")));
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
