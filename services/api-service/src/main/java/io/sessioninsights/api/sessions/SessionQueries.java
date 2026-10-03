package io.sessioninsights.api.sessions;

import io.sessioninsights.api.sessions.Dtos.SessionSummary;
import io.sessioninsights.api.sessions.Dtos.UserRef;
import io.sessioninsights.common.wire.WireJson;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Session reads in PostgreSQL. Must run inside a transaction started under the caller's tenant
 * ({@code TenantContext}): row-level security limits every query to that tenant, and the
 * explicit {@code tenant_id} predicate is defence in depth and uses the list index.
 */
@Component
class SessionQueries {

    /** List filters; null means "not filtered". */
    record Filter(Instant from, Instant to, String urlContains, Boolean hasError, Long minDurationMs,
                  Long maxDurationMs, String status) {
    }

    private static final String SELECT = """
            SELECT s.id, s.site_id, s.started_at, s.last_active_at, s.ended_at, s.duration_ms, s.page_count,
                   s.error_count, s.entry_url, s.platform, s.browser, s.friction_score, s.analysis_status,
                   s.anonymous_id, u.id AS end_user_id, u.external_user_id, u.traits
            FROM user_session s LEFT JOIN end_user u ON u.id = s.end_user_id
            """;

    private final JdbcClient jdbc;

    SessionQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Up to {@code limit + 1} rows after the cursor, newest first (the extra row means "more"). */
    List<Row> list(UUID tenantId, Filter f, SessionCursor after, int limit) {
        StringBuilder sql = new StringBuilder(SELECT).append("WHERE s.tenant_id = :tenant");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("tenant", tenantId);
        if (after != null) {
            sql.append(" AND (s.started_at, s.id) < (:cursorTs, :cursorId)");
            params.put("cursorTs", Timestamp.from(after.startedAt()));
            params.put("cursorId", after.id());
        }
        if (f.from() != null) {
            sql.append(" AND s.started_at >= :from");
            params.put("from", Timestamp.from(f.from()));
        }
        if (f.to() != null) {
            sql.append(" AND s.started_at < :to");
            params.put("to", Timestamp.from(f.to()));
        }
        if (f.urlContains() != null && !f.urlContains().isEmpty()) {
            sql.append(" AND s.entry_url ILIKE :url ESCAPE '\\'");   // escape character: one backslash
            params.put("url", "%" + escapeLike(f.urlContains()) + "%");
        }
        if (f.hasError() != null) {
            sql.append(f.hasError() ? " AND s.error_count > 0" : " AND s.error_count = 0");
        }
        if (f.minDurationMs() != null) {
            sql.append(" AND s.duration_ms >= :minDuration");
            params.put("minDuration", f.minDurationMs());
        }
        if (f.maxDurationMs() != null) {
            sql.append(" AND s.duration_ms <= :maxDuration");
            params.put("maxDuration", f.maxDurationMs());
        }
        if ("open".equals(f.status())) {
            sql.append(" AND s.ended_at IS NULL");
        } else if ("closed".equals(f.status())) {
            sql.append(" AND s.ended_at IS NOT NULL");
        }
        sql.append(" ORDER BY s.started_at DESC, s.id DESC LIMIT :limit");
        params.put("limit", limit + 1);
        return jdbc.sql(sql.toString()).params(params).query(ROW).list();
    }

    Optional<Row> find(UUID tenantId, UUID sessionId) {
        return jdbc.sql(SELECT + "WHERE s.tenant_id = :tenant AND s.id = :id")
                .param("tenant", tenantId).param("id", sessionId)
                .query(ROW).optional();
    }

    /** A session row with its visitor. */
    record Row(SessionSummary summary, UUID siteId, JsonNode traits) {
    }

    private static final RowMapper<Row> ROW = (rs, n) -> {
        Timestamp ended = rs.getTimestamp("ended_at");
        Object friction = rs.getObject("friction_score");
        UUID endUserId = rs.getObject("end_user_id", UUID.class);
        SessionSummary summary = new SessionSummary(
                rs.getObject("id", UUID.class),
                new UserRef(endUserId, rs.getString("external_user_id"), rs.getString("anonymous_id")),
                ended == null ? "open" : "closed",
                rs.getTimestamp("started_at").toInstant(),
                rs.getTimestamp("last_active_at").toInstant(),
                ended == null ? null : ended.toInstant(),
                rs.getLong("duration_ms"), rs.getInt("page_count"), rs.getInt("error_count"),
                rs.getString("entry_url"), rs.getString("platform"), rs.getString("browser"),
                friction == null ? null : ((Number) friction).intValue(),
                rs.getString("analysis_status"));
        String traits = rs.getString("traits");
        return new Row(summary, rs.getObject("site_id", UUID.class),
                WireJson.mapper().readTree(traits == null ? "{}" : traits));
    };

    static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
