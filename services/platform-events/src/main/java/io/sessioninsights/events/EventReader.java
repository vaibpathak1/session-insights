package io.sessioninsights.events;

import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.query.GenericRecord;
import io.sessioninsights.common.wire.WireJson;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reads a session's events from ClickHouse {@code events} (Phase 5, task 5.5). Every read is
 * scoped to one tenant ({@code tenant_id} is the first sorting-key column, ADR-0008) and
 * deduplicated with {@code FINAL}; order is {@code (ts, event_id)}.
 */
public class EventReader {

    private static final String COLUMNS = """
            event_id, tenant_id, site_id, session_id, anonymous_id, end_user_id, ts, ingested_at,
            event_type, event_name, url, path, page_title, target_selector, target_text,
            error_message, error_stack, toJSONString(props) AS props_json""";

    private final Client client;

    public EventReader(Client client) {
        this.client = client;
    }

    /** All of a session's events (small sessions, tests). */
    public List<EventRow> findEvents(UUID tenantId, UUID sessionId) {
        String sql = "SELECT " + COLUMNS + """
                 FROM events FINAL
                WHERE tenant_id = {tenantId:UUID} AND session_id = {sessionId:UUID}
                ORDER BY ts, event_id""";
        return client.queryAll(sql, Map.of("tenantId", tenantId, "sessionId", sessionId)).stream()
                .map(EventReader::read).toList();
    }

    /** Up to {@code limit} events after {@code after} (null: from the start), in order. */
    public List<EventRow> page(UUID tenantId, UUID sessionId, EventCursor after, int limit) {
        Map<String, Object> params = new HashMap<>(Map.of("tenantId", tenantId, "sessionId", sessionId,
                "limit", limit));
        String keyset = "";
        if (after != null) {
            keyset = " AND (ts, event_id) > ({afterTs:DateTime64(3)}, {afterId:UUID})";
            // epoch seconds with milliseconds, as plain text (a double would print as 1.79E9)
            params.put("afterTs", java.math.BigDecimal.valueOf(after.ts().toEpochMilli()).movePointLeft(3).toPlainString());
            params.put("afterId", after.eventId());
        }
        String sql = "SELECT " + COLUMNS + """
                 FROM events FINAL
                WHERE tenant_id = {tenantId:UUID} AND session_id = {sessionId:UUID}%s
                ORDER BY ts, event_id
                LIMIT {limit:UInt32}""".formatted(keyset);
        return client.queryAll(sql, params).stream().map(EventReader::read).toList();
    }

    private static EventRow read(GenericRecord r) {
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
                WireJson.mapper().readTree(r.getString("props_json")));
    }
}
