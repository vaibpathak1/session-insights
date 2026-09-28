package io.sessioninsights.processor.sessions;

import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.query.GenericRecord;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Counters of closed sessions, computed from ClickHouse {@code events} with {@code FINAL}, never
 * incremented per delivery (task 5.4). Always scoped to one tenant.
 * <ul>
 *   <li>{@code pageCount}: NAVIGATION events, without {@code replaceState} and without
 *       fragment-only changes (a navigation counts when its URL without {@code #…} differs
 *       from the previous counted one, so {@code popstate} across a hash is not a page).</li>
 *   <li>{@code errorCount}: EXCEPTION + CONSOLE_ERROR events.</li>
 *   <li>{@code firstTs}/{@code lastTs}: client event times; duration = last − first.</li>
 * </ul>
 */
public class SessionStats {

    /** One session's counters. */
    public record Stats(Instant firstTs, Instant lastTs, int pageCount, int errorCount) {
        public long durationMs() {
            return Math.max(0, lastTs.toEpochMilli() - firstTs.toEpochMilli());
        }
    }

    private static final String QUERY = """
            SELECT session_id, min(ts) AS first_ts, max(ts) AS last_ts,
                   countIf(event_type IN ('EXCEPTION', 'CONSOLE_ERROR')) AS errors,
                   arrayMap(t -> t.3, arraySort(t -> (t.1, t.2), groupArrayIf((ts, event_id, cutFragment(url)),
                       event_type = 'NAVIGATION'
                       AND ifNull(props.trigger::Nullable(String), '') != 'replaceState'))) AS pages
            FROM events FINAL
            WHERE tenant_id = {tenantId:UUID} AND session_id IN {sessionIds:Array(UUID)}
            GROUP BY session_id""";

    private final Client client;

    public SessionStats(Client client) {
        this.client = client;
    }

    /** Counters for the sessions that have events; sessions without any are absent. */
    public Map<UUID, Stats> of(UUID tenantId, Collection<UUID> sessionIds) {
        if (sessionIds.isEmpty()) {
            return Map.of();
        }
        // client-v2 renders a List parameter with toString() (unquoted); an Array(UUID) literal
        // is passed as text instead. UUID.toString() has nothing to escape.
        String ids = sessionIds.stream().map(id -> "'" + id + "'").collect(Collectors.joining(",", "[", "]"));
        Map<UUID, Stats> stats = new HashMap<>();
        for (GenericRecord r : client.queryAll(QUERY, Map.of("tenantId", tenantId, "sessionIds", ids))) {
            stats.put(r.getUUID("session_id"), new Stats(
                    r.getZonedDateTime("first_ts").toInstant(), r.getZonedDateTime("last_ts").toInstant(),
                    pageCount(r.getList("pages")), (int) r.getLong("errors")));
        }
        return stats;
    }

    /** Pages = changes of the fragment-less URL along the ordered navigations. */
    static int pageCount(List<?> urls) {
        int pages = 0;
        Object previous = null;
        for (Object url : urls) {
            if (!url.equals(previous)) {
                pages++;
            }
            previous = url;
        }
        return pages;
    }
}
