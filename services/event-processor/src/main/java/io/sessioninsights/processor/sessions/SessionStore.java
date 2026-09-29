package io.sessioninsights.processor.sessions;

import io.sessioninsights.processor.store.Store;
import io.sessioninsights.processor.store.StoreRejectedException;
import io.sessioninsights.processor.store.StoreUnavailableException;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Upserts {@code end_user} and {@code user_session} with plain JDBC as the processor role
 * (task 5.3; no JPA on this path). One transaction per call; rows are grouped by tenant and
 * {@code app.tenant_id} is set for each group, so row-level security applies as for the
 * application. Every statement only ever moves minima down, maxima up and fills unknown
 * values, and the {@code DO UPDATE ... WHERE} clauses skip the write when nothing changes:
 * a redelivered batch leaves rows, {@code version} and {@code updated_at} untouched.
 */
public class SessionStore {

    private static final String UPSERT_END_USER = """
            INSERT INTO end_user (tenant_id, site_id, anonymous_id, first_seen_at, last_seen_at)
            VALUES (:tenantId, :siteId, :anonymousId, :first, :last)
            ON CONFLICT (site_id, anonymous_id) DO UPDATE SET
                first_seen_at = least(end_user.first_seen_at, EXCLUDED.first_seen_at),
                last_seen_at  = greatest(end_user.last_seen_at, EXCLUDED.last_seen_at),
                updated_at    = now(),
                version       = end_user.version + 1
            WHERE EXCLUDED.first_seen_at < end_user.first_seen_at OR EXCLUDED.last_seen_at > end_user.last_seen_at
            RETURNING id""";

    private static final String FIND_END_USER =
            "SELECT id FROM end_user WHERE site_id = :siteId AND anonymous_id = :anonymousId";

    /** A new, earlier entry navigation replaces the known one. */
    private static final String EARLIER_ENTRY =
            "EXCLUDED.entry_at IS NOT NULL AND (user_session.entry_at IS NULL OR EXCLUDED.entry_at < user_session.entry_at)";

    private static final String UPSERT_SESSION = """
            INSERT INTO user_session (id, tenant_id, site_id, end_user_id, anonymous_id, started_at, last_active_at,
                                      entry_url, entry_at, platform, browser)
            VALUES (:id, :tenantId, :siteId, :endUserId, :anonymousId, :startedAt, :lastActive,
                    :entryUrl, :entryAt, :platform, :browser)
            ON CONFLICT (id) DO UPDATE SET
                started_at      = least(user_session.started_at, EXCLUDED.started_at),
                last_active_at  = greatest(user_session.last_active_at, EXCLUDED.last_active_at),
                entry_url       = CASE WHEN %1$s THEN EXCLUDED.entry_url ELSE user_session.entry_url END,
                entry_at        = CASE WHEN %1$s THEN EXCLUDED.entry_at ELSE user_session.entry_at END,
                platform        = coalesce(user_session.platform, EXCLUDED.platform),
                browser         = coalesce(user_session.browser, EXCLUDED.browser),
                end_user_id     = coalesce(user_session.end_user_id, EXCLUDED.end_user_id),
                -- a late event for a closed session: extend it and let the closer recompute (task 5.4)
                ended_at        = CASE WHEN user_session.ended_at IS NULL THEN NULL
                                       ELSE greatest(user_session.ended_at, :lastTs) END,
                needs_recompute = user_session.needs_recompute OR user_session.ended_at IS NOT NULL,
                updated_at      = now(),
                version         = user_session.version + 1
            WHERE EXCLUDED.started_at < user_session.started_at
               OR EXCLUDED.last_active_at > user_session.last_active_at
               OR (%1$s)
               OR (user_session.platform IS NULL AND EXCLUDED.platform IS NOT NULL)
               OR (user_session.browser IS NULL AND EXCLUDED.browser IS NOT NULL)
               OR (user_session.end_user_id IS NULL AND EXCLUDED.end_user_id IS NOT NULL)""".formatted(EARLIER_ENTRY);

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;

    public SessionStore(JdbcClient jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    /**
     * Applies all updates in one transaction, all or nothing.
     *
     * @throws StoreRejectedException    PostgreSQL is up and rejected the data
     * @throws StoreUnavailableException any other failure
     */
    public void apply(List<SessionUpdate> updates) {
        if (updates.isEmpty()) {
            return;
        }
        Map<UUID, List<SessionUpdate>> byTenant = updates.stream()
                .collect(Collectors.groupingBy(SessionUpdate::tenantId));
        try {
            tx.executeWithoutResult(status -> byTenant.forEach((tenantId, forTenant) -> {
                jdbc.sql("SELECT set_config('app.tenant_id', :tenant, true)").param("tenant", tenantId.toString())
                        .query().singleValue();
                forTenant.stream()
                        .sorted(Comparator.comparing(SessionUpdate::sessionId))   // stable lock order
                        .forEach(this::upsert);
            }));
        } catch (DataAccessException | TransactionException e) {
            throw classify(e);
        }
    }

    private void upsert(SessionUpdate u) {
        UUID endUserId = null;
        if (u.anonymousId() != null && !u.anonymousId().isBlank()) {
            Optional<UUID> upserted = jdbc.sql(UPSERT_END_USER)
                    .param("tenantId", u.tenantId()).param("siteId", u.siteId()).param("anonymousId", u.anonymousId())
                    .param("first", ts(u.firstTs())).param("last", ts(u.lastTs()))
                    .query(UUID.class).optional();
            endUserId = upserted.orElseGet(() -> jdbc.sql(FIND_END_USER)
                    .param("siteId", u.siteId()).param("anonymousId", u.anonymousId())
                    .query(UUID.class).single());
        }
        jdbc.sql(UPSERT_SESSION)
                .param("id", u.sessionId()).param("tenantId", u.tenantId()).param("siteId", u.siteId())
                .param("endUserId", endUserId).param("anonymousId", u.anonymousId() == null ? "" : u.anonymousId())
                .param("startedAt", ts(u.firstTs())).param("lastActive", ts(u.lastReceived()))
                .param("entryUrl", u.entryUrl()).param("entryAt", ts(u.entryAt()))
                .param("platform", u.platform()).param("browser", u.browser())
                .param("lastTs", ts(u.lastTs()))
                .update();
    }

    private RuntimeException classify(RuntimeException failure) {
        SQLException sql = PostgresErrors.sqlException(failure);
        if (sql != null && PostgresErrors.isDataError(sql) && reachable()) {
            return new StoreRejectedException(Store.POSTGRES, sql.getSQLState());
        }
        return new StoreUnavailableException(Store.POSTGRES, PostgresErrors.describe(failure));
    }

    private boolean reachable() {
        try {
            return jdbc.sql("SELECT 1").query(Integer.class).single() == 1;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
