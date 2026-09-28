package io.sessioninsights.db;

import io.sessioninsights.db.testing.TestContainers;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V5: the processor role writes sessions and visitors under RLS, never deletes, and reaches
 * across tenants only through claim_sessions_to_close() (Phase 5, task 5.1).
 */
@Testcontainers
class ProcessorRoleTest {

    @Container
    static final PostgreSQLContainer postgres = TestContainers.postgres();

    static Tenant a;
    static Tenant b;

    record Tenant(UUID id, UUID siteId) {
    }

    @BeforeAll
    static void migrateAndSeed() throws SQLException {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration/postgres")
                .placeholders(TestContainers.flywayPlaceholders())
                .load()
                .migrate();
        a = tenant("a");
        b = tenant("b");
    }

    @Test
    void writesSessionsAndVisitorsOfTheTenantItActsFor() throws SQLException {
        UUID session = UUID.randomUUID();
        try (Connection c = processor()) {
            c.setAutoCommit(false);
            actAs(c, a.id());
            UUID endUser = upsertEndUser(c, a, "anon-1");
            assertThat(upsertEndUser(c, a, "anon-1")).as("unique per site + anonymous id").isEqualTo(endUser);
            try (PreparedStatement s = c.prepareStatement("""
                    INSERT INTO user_session (id, tenant_id, site_id, end_user_id, anonymous_id, started_at, last_active_at)
                    VALUES (?, ?, ?, ?, 'anon-1', now(), now())
                    ON CONFLICT (id) DO UPDATE SET last_active_at = greatest(user_session.last_active_at, EXCLUDED.last_active_at)""")) {
                s.setObject(1, session);
                s.setObject(2, a.id());
                s.setObject(3, a.siteId());
                s.setObject(4, endUser);
                assertThat(s.executeUpdate()).isOne();
                assertThat(s.executeUpdate()).as("upsert").isOne();
            }
            c.commit();
        }
    }

    @Test
    void cannotDeleteOrTouchOtherTenantsOrOtherTables() throws SQLException {
        UUID sessionOfB = insertSessionAsOwner(b, "anon-b", "now() - interval '1 minute'", null);
        try (Connection c = processor()) {
            c.setAutoCommit(false);
            actAs(c, a.id());
            try (Statement s = c.createStatement()) {
                try (var rs = s.executeQuery("SELECT count(*) FROM user_session WHERE id = '" + sessionOfB + "'")) {
                    rs.next();
                    assertThat(rs.getLong(1)).as("tenant B's row is invisible").isZero();
                }
                assertThat(s.executeUpdate("UPDATE user_session SET page_count = 99 WHERE id = '" + sessionOfB + "'")).isZero();
            }
            c.rollback();
            assertDenied(c, "INSERT INTO user_session (id, tenant_id, site_id, anonymous_id, started_at, last_active_at)"
                    + " VALUES (gen_random_uuid(), '" + b.id() + "', '" + b.siteId() + "', 'x', now(), now())", "row-level security");
            assertDenied(c, "DELETE FROM user_session WHERE tenant_id = '" + a.id() + "'", "permission denied");
            assertDenied(c, "DELETE FROM end_user WHERE tenant_id = '" + a.id() + "'", "permission denied");
            assertDenied(c, "SELECT count(*) FROM site", "permission denied");
            assertDenied(c, "SELECT count(*) FROM session_insight", "permission denied");
            assertDenied(c, "SELECT * FROM resolve_site_key('x')", "permission denied");
        }
    }

    @Test
    void claimReturnsIdleOpenAndRecomputeSessionsOfAllTenantsAndSkipsLockedRows() throws SQLException {
        UUID idleA = insertSessionAsOwner(a, "anon-idle-a", "now() - interval '2 hours'", null);
        UUID idleB = insertSessionAsOwner(b, "anon-idle-b", "now() - interval '2 hours'", null);
        UUID fresh = insertSessionAsOwner(a, "anon-fresh", "now()", null);
        UUID closed = insertSessionAsOwner(a, "anon-closed", "now() - interval '3 hours'", "now() - interval '3 hours'");
        UUID recompute = insertSessionAsOwner(b, "anon-late", "now()", "now() - interval '1 hour'");
        exec("UPDATE user_session SET needs_recompute = true WHERE id = '" + recompute + "'");

        try (Connection first = processor(); Connection second = processor()) {
            first.setAutoCommit(false);
            second.setAutoCommit(false);
            List<String> claimedByFirst = claim(first, 1);
            List<String> claimedBySecond = claim(second, 100);
            Set<String> all = new HashSet<>(claimedByFirst);
            all.addAll(claimedBySecond);

            assertThat(claimedByFirst).hasSize(1);
            assertThat(claimedBySecond).doesNotContainAnyElementsOf(claimedByFirst);   // SKIP LOCKED
            assertThat(all).contains(idleA + ":CLOSE", idleB + ":CLOSE", recompute + ":RECOMPUTE")
                    .noneMatch(s -> s.startsWith(fresh.toString()) || s.startsWith(closed.toString()));
            first.rollback();
            second.rollback();
        }
    }

    @Test
    void onlyTheProcessorMayClaimAndTheFunctionIsHardened() throws SQLException {
        try (Connection app = DriverManager.getConnection(postgres.getJdbcUrl(), TestContainers.APP_USER, TestContainers.APP_PASSWORD)) {
            assertDenied(app, "SELECT * FROM claim_sessions_to_close(interval '1 minute', 10)", "permission denied");
        }
        try (Connection owner = owner(); Statement s = owner.createStatement();
             var rs = s.executeQuery("SELECT prosecdef, proconfig FROM pg_proc WHERE proname = 'claim_sessions_to_close'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getBoolean("prosecdef")).isTrue();
            assertThat((String[]) rs.getArray("proconfig").getArray()).containsExactly("search_path=pg_catalog, public");
        }
    }

    // ------------------------------------------------------------------ helpers

    private static List<String> claim(Connection c, int limit) throws SQLException {
        List<String> claimed = new ArrayList<>();
        try (Statement s = c.createStatement();
             var rs = s.executeQuery("SELECT * FROM claim_sessions_to_close(interval '30 minutes', " + limit + ")")) {
            while (rs.next()) {
                claimed.add(rs.getObject("session_id", UUID.class) + ":" + rs.getString("kind"));
            }
        }
        return claimed;
    }

    private static UUID upsertEndUser(Connection c, Tenant t, String anonymousId) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("""
                INSERT INTO end_user (tenant_id, site_id, anonymous_id) VALUES (?, ?, ?)
                ON CONFLICT (site_id, anonymous_id) DO UPDATE SET last_seen_at = greatest(end_user.last_seen_at, EXCLUDED.last_seen_at)
                RETURNING id""")) {
            s.setObject(1, t.id());
            s.setObject(2, t.siteId());
            s.setString(3, anonymousId);
            try (var rs = s.executeQuery()) {
                rs.next();
                return rs.getObject(1, UUID.class);
            }
        }
    }

    private static void actAs(Connection c, UUID tenant) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute("SELECT set_config('app.tenant_id', '" + tenant + "', true)");
        }
    }

    private static void assertDenied(Connection c, String sql, String message) {
        assertThatThrownBy(() -> {
            try (Statement s = c.createStatement()) {
                c.setAutoCommit(true);
                s.execute(sql);
            }
        }).as(sql).isInstanceOf(SQLException.class).hasMessageContaining(message);
    }

    private static Connection processor() throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), TestContainers.PROCESSOR_USER, TestContainers.PROCESSOR_PASSWORD);
    }

    private static Connection owner() throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private static void exec(String sql) throws SQLException {
        try (Connection c = owner(); Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    private static Tenant tenant(String name) throws SQLException {
        UUID tenant = UUID.randomUUID();
        UUID site = UUID.randomUUID();
        exec("INSERT INTO tenant (id, name) VALUES ('" + tenant + "', '" + name + "')");
        exec("INSERT INTO site (id, tenant_id, name, allowed_origins) VALUES ('" + site + "', '" + tenant
                + "', 'site', ARRAY['http://localhost:*'])");
        return new Tenant(tenant, site);
    }

    private static UUID insertSessionAsOwner(Tenant t, String anonymousId, String lastActive, String endedAt) throws SQLException {
        UUID id = UUID.randomUUID();
        exec("INSERT INTO user_session (id, tenant_id, site_id, anonymous_id, started_at, last_active_at, ended_at) VALUES ('"
                + id + "', '" + t.id() + "', '" + t.siteId() + "', '" + anonymousId + "', " + lastActive + ", " + lastActive
                + ", " + (endedAt == null ? "NULL" : endedAt) + ")");
        return id;
    }
}
