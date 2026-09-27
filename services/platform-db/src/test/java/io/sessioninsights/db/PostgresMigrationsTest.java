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
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class PostgresMigrationsTest {

    @Container
    static final PostgreSQLContainer postgres = TestContainers.postgres();

    static Flyway flyway;
    static int firstRunExecuted;

    @BeforeAll
    static void migrate() {
        flyway = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration/postgres")
                .placeholders(Map.of("appUser", TestContainers.APP_USER, "appPassword", TestContainers.APP_PASSWORD))
                .load();
        firstRunExecuted = flyway.migrate().migrationsExecuted;
    }

    @Test
    void appliesOnEmptyDatabaseAndIsNoOpOnRerun() {
        assertThat(firstRunExecuted).isEqualTo(flyway.info().applied().length).isPositive();
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
    }

    @Test
    void checkConstraintsRejectInvalidEnumValues() throws SQLException {
        UUID tenant = UUID.randomUUID();
        UUID site = UUID.randomUUID();
        exec("INSERT INTO tenant (id, name) VALUES ('%s', 't')".formatted(tenant));
        exec("INSERT INTO site (id, tenant_id, name) VALUES ('%s', '%s', 's')".formatted(site, tenant));

        assertCheckViolation("INSERT INTO masking_rule (tenant_id, site_id, css_selector, action)"
                + " VALUES ('%s', '%s', '.x', 'HIDE')".formatted(tenant, site));
        assertCheckViolation("INSERT INTO app_user (tenant_id, email, display_name, role)"
                + " VALUES ('%s', 'a@b.c', 'A', 'ROOT')".formatted(tenant));
        assertCheckViolation("INSERT INTO model_provider_config (tenant_id, provider, chat_model)"
                + " VALUES ('%s', 'GEMINI', 'm')".formatted(tenant));
        assertCheckViolation("INSERT INTO site (tenant_id, name, sampling_rate)"
                + " VALUES ('%s', 's2', 1.5)".formatted(tenant));

        UUID session = UUID.randomUUID();
        assertCheckViolation(("INSERT INTO user_session (id, tenant_id, site_id, anonymous_id, started_at, last_active_at,"
                + " analysis_status) VALUES ('%s', '%s', '%s', 'a', now(), now(), 'DONE')").formatted(session, tenant, site));
        exec(("INSERT INTO user_session (id, tenant_id, site_id, anonymous_id, started_at, last_active_at)"
                + " VALUES ('%s', '%s', '%s', 'a', now(), now())").formatted(session, tenant, site));
        assertCheckViolation("INSERT INTO erasure_request (tenant_id, subject_type, subject_id)"
                + " VALUES ('%s', 'TENANT', '%s')".formatted(tenant, session));
        assertCheckViolation("INSERT INTO external_ticket_link (tenant_id, session_id, system, external_id)"
                + " VALUES ('%s', '%s', 'GITHUB', 'X-1')".formatted(tenant, session));
    }

    @Test
    void insightEmbeddingHasHnswCosineIndex() throws SQLException {
        try (Connection c = owner();
             var rs = c.createStatement().executeQuery(
                     "SELECT indexdef FROM pg_indexes WHERE indexname = 'session_insight_embedding_hnsw'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).contains("USING hnsw").contains("vector_cosine_ops");
        }
    }

    @Test
    void everyTenantScopedTableForcesRowLevelSecurity() throws SQLException {
        List<String> unprotected = new ArrayList<>();
        try (Connection c = owner(); var rs = c.createStatement().executeQuery("""
                SELECT c.relname, c.relrowsecurity, c.relforcerowsecurity
                FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'public' AND c.relkind = 'r'
                  AND (c.relname = 'tenant' OR EXISTS (SELECT 1 FROM pg_attribute a
                       WHERE a.attrelid = c.oid AND a.attname = 'tenant_id' AND NOT a.attisdropped))
                """)) {
            int tables = 0;
            while (rs.next()) {
                tables++;
                if (!rs.getBoolean(2) || !rs.getBoolean(3)) {
                    unprotected.add(rs.getString(1));
                }
            }
            assertThat(tables).isEqualTo(12);
        }
        assertThat(unprotected).isEmpty();
    }

    @Test
    void appRoleSeesOnlyItsTenantAndCannotRewriteAudit() throws SQLException {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        for (UUID t : List.of(tenantA, tenantB)) {
            UUID site = UUID.randomUUID();
            exec("INSERT INTO tenant (id, name) VALUES ('%s', 'rls')".formatted(t));
            exec("INSERT INTO site (id, tenant_id, name) VALUES ('%s', '%s', 's')".formatted(site, t));
            UUID session = UUID.randomUUID();
            exec(("INSERT INTO user_session (id, tenant_id, site_id, anonymous_id, started_at, last_active_at)"
                    + " VALUES ('%s', '%s', '%s', 'a', now(), now())").formatted(session, t, site));
            exec("INSERT INTO review_event (tenant_id, session_id, action) VALUES ('%s', '%s', 'NOTE')".formatted(t, session));
        }

        try (Connection app = DriverManager.getConnection(postgres.getJdbcUrl(), TestContainers.APP_USER, TestContainers.APP_PASSWORD);
             Statement s = app.createStatement()) {
            assertThat(count(s, "SELECT count(*) FROM user_session")).as("no tenant set").isZero();

            app.setAutoCommit(false);
            s.execute("SELECT set_config('app.tenant_id', '%s', true)".formatted(tenantA));
            assertThat(count(s, "SELECT count(*) FROM user_session")).isEqualTo(1);
            assertThat(count(s, "SELECT count(*) FROM user_session WHERE tenant_id = '%s'".formatted(tenantB))).isZero();
            assertThatThrownBy(() -> s.execute(("INSERT INTO tenant (id, name) VALUES ('%s', 'x')").formatted(UUID.randomUUID())))
                    .isInstanceOf(SQLException.class).hasMessageContaining("row-level security");
            app.rollback();

            s.execute("SELECT set_config('app.tenant_id', '%s', true)".formatted(tenantA));
            assertThatThrownBy(() -> s.execute("UPDATE review_event SET note = 'x'"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("permission denied");
            app.rollback();

            // after the transaction the setting reads as '' and must still fail closed, not error
            assertThat(count(s, "SELECT count(*) FROM user_session")).isZero();
            app.commit();
        }
    }

    @Test
    void appRoleDeletingASessionCascadesToInsightsAndAuditWithoutDeleteGrantOnAudit() throws SQLException {
        UUID tenant = UUID.randomUUID();
        UUID site = UUID.randomUUID();
        UUID session = UUID.randomUUID();
        exec("INSERT INTO tenant (id, name) VALUES ('%s', 'cascade')".formatted(tenant));
        exec("INSERT INTO site (id, tenant_id, name) VALUES ('%s', '%s', 's')".formatted(site, tenant));
        exec(("INSERT INTO user_session (id, tenant_id, site_id, anonymous_id, started_at, last_active_at)"
                + " VALUES ('%s', '%s', '%s', 'a', now(), now())").formatted(session, tenant, site));
        exec("INSERT INTO session_insight (id, tenant_id, session_id) VALUES (nextval('session_insight_seq'), '%s', '%s')"
                .formatted(tenant, session));
        exec("INSERT INTO review_event (tenant_id, session_id, action) VALUES ('%s', '%s', 'NOTE')".formatted(tenant, session));

        try (Connection app = DriverManager.getConnection(postgres.getJdbcUrl(), TestContainers.APP_USER, TestContainers.APP_PASSWORD);
             Statement s = app.createStatement()) {
            app.setAutoCommit(false);
            s.execute("SELECT set_config('app.tenant_id', '%s', true)".formatted(tenant));
            assertThatThrownBy(() -> s.execute("DELETE FROM review_event WHERE session_id = '%s'".formatted(session)))
                    .as("no direct DELETE on the audit log")
                    .isInstanceOf(SQLException.class).hasMessageContaining("permission denied");
            app.rollback();

            s.execute("SELECT set_config('app.tenant_id', '%s', true)".formatted(tenant));
            assertThat(s.executeUpdate("DELETE FROM user_session WHERE id = '%s'".formatted(session))).isEqualTo(1);
            app.commit();
        }

        try (Connection c = owner(); Statement s = c.createStatement()) {
            for (String table : List.of("user_session", "session_insight", "review_event")) {
                String column = table.equals("user_session") ? "id" : "session_id";
                assertThat(count(s, "SELECT count(*) FROM %s WHERE %s = '%s'".formatted(table, column, session)))
                        .as(table).isZero();
            }
        }
    }

    static long count(Statement s, String sql) throws SQLException {
        try (var rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    static Connection owner() throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    static void assertCheckViolation(String sql) {
        assertThatThrownBy(() -> exec(sql))
                .isInstanceOf(SQLException.class)
                .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("23514"));
    }

    static void exec(String sql) throws SQLException {
        try (Connection c = owner(); Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }
}
