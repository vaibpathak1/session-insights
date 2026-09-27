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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V4: resolve_site_key() is the collector role's only access to PostgreSQL (ADR-0011). */
@Testcontainers
class CollectorRoleTest {

    @Container
    static final PostgreSQLContainer postgres = TestContainers.postgres();

    @BeforeAll
    static void migrate() {
        migrate(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    @Test
    void collectorResolvesOnlyLiveKeysOfLiveSitesAndTenants() throws SQLException {
        String live = seed(postgres.getJdbcUrl(), "live");
        String revoked = seed(postgres.getJdbcUrl(), "revoked");
        String deletedSite = seed(postgres.getJdbcUrl(), "deleted-site");
        String deletedTenant = seed(postgres.getJdbcUrl(), "deleted-tenant");
        exec(postgres.getJdbcUrl(), "UPDATE site_key SET revoked_at = now() WHERE key_hash = '" + revoked + "'");
        exec(postgres.getJdbcUrl(), "UPDATE site SET is_active = false, deleted_at = now()"
                + " WHERE id = (SELECT site_id FROM site_key WHERE key_hash = '" + deletedSite + "')");
        exec(postgres.getJdbcUrl(), "UPDATE tenant SET is_active = false, deleted_at = now()"
                + " WHERE id = (SELECT tenant_id FROM site_key WHERE key_hash = '" + deletedTenant + "')");

        try (Connection c = collector(postgres.getJdbcUrl()); Statement s = c.createStatement()) {
            try (var rs = s.executeQuery("SELECT * FROM resolve_site_key('" + live + "')")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getObject("tenant_id", UUID.class)).isNotNull();
                assertThat(rs.getObject("site_id", UUID.class)).isNotNull();
                assertThat((String[]) rs.getArray("allowed_origins").getArray()).containsExactly("http://localhost:*");
                assertThat(rs.getBigDecimal("sampling_rate")).isEqualByComparingTo("1");
                assertThat(rs.next()).isFalse();
            }
            for (String hash : new String[] {revoked, deletedSite, deletedTenant, "0".repeat(64)}) {
                try (var rs = s.executeQuery("SELECT * FROM resolve_site_key('" + hash + "')")) {
                    assertThat(rs.next()).as(hash).isFalse();
                }
            }
        }
    }

    @Test
    void collectorHasNoTablePrivileges() throws SQLException {
        try (Connection c = collector(postgres.getJdbcUrl()); Statement s = c.createStatement()) {
            for (String table : new String[] {"site_key", "tenant", "site", "user_session"}) {
                assertThatThrownBy(() -> s.executeQuery("SELECT 1 FROM " + table))
                        .as(table).isInstanceOf(SQLException.class).hasMessageContaining("permission denied");
            }
        }
    }

    @Test
    void appRoleCannotExecuteTheResolver() throws SQLException {
        try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), TestContainers.APP_USER, TestContainers.APP_PASSWORD);
             Statement s = c.createStatement()) {
            assertThatThrownBy(() -> s.executeQuery("SELECT * FROM resolve_site_key('x')"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("permission denied");
        }
    }

    /**
     * With a non-superuser schema owner, FORCE ROW LEVEL SECURITY would hide every row from
     * the SECURITY DEFINER function without V4's owner read policies.
     */
    @Test
    void resolverWorksWhenTheSchemaOwnerIsNotASuperuser() throws SQLException {
        exec(postgres.getJdbcUrl(), "CREATE ROLE plain_owner LOGIN CREATEROLE PASSWORD 'plain_owner_pw'");
        exec(postgres.getJdbcUrl(), "CREATE DATABASE plain_owned OWNER plain_owner");
        String url = postgres.getJdbcUrl().replace("/" + TestContainers.DATABASE, "/plain_owned");
        exec(url, "CREATE EXTENSION vector");   // needs a superuser; compose does this in init scripts
        migrate(url, "plain_owner", "plain_owner_pw");

        String key;
        try (Connection c = DriverManager.getConnection(url, "plain_owner", "plain_owner_pw");
             Statement s = c.createStatement()) {
            try (var rs = s.executeQuery("SELECT rolsuper, rolbypassrls FROM pg_roles WHERE rolname = current_user")) {
                rs.next();
                assertThat(rs.getBoolean(1)).isFalse();
                assertThat(rs.getBoolean(2)).isFalse();
            }
            key = insertKey(s, "plain");
        }
        try (Connection c = collector(url); Statement s = c.createStatement();
             var rs = s.executeQuery("SELECT tenant_id FROM resolve_site_key('" + key + "')")) {
            assertThat(rs.next()).isTrue();
        }
    }

    private static void migrate(String url, String user, String password) {
        Flyway.configure()
                .dataSource(url, user, password)
                .locations("classpath:db/migration/postgres")
                .placeholders(TestContainers.flywayPlaceholders())
                .load()
                .migrate();
    }

    /** Inserts tenant, site and key as the (superuser) owner; returns the key hash. */
    private static String seed(String url, String name) throws SQLException {
        try (Connection c = DriverManager.getConnection(url, postgres.getUsername(), postgres.getPassword());
             Statement s = c.createStatement()) {
            return insertKey(s, name);
        }
    }

    private static String insertKey(Statement s, String name) throws SQLException {
        UUID tenant = UUID.randomUUID();
        UUID site = UUID.randomUUID();
        String hash = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
        s.execute("SELECT set_config('app.tenant_id', '%s', false)".formatted(tenant));   // for a non-superuser owner
        s.execute("INSERT INTO tenant (id, name) VALUES ('%s', '%s')".formatted(tenant, name));
        s.execute("INSERT INTO site (id, tenant_id, name, allowed_origins) VALUES ('%s', '%s', 's', '{http://localhost:*}')"
                .formatted(site, tenant));
        s.execute("INSERT INTO site_key (tenant_id, site_id, key_prefix, key_hash) VALUES ('%s', '%s', 'sk_test_', '%s')"
                .formatted(tenant, site, hash));
        return hash;
    }

    private static Connection collector(String url) throws SQLException {
        return DriverManager.getConnection(url, TestContainers.COLLECTOR_USER, TestContainers.COLLECTOR_PASSWORD);
    }

    private static void exec(String url, String sql) throws SQLException {
        try (Connection c = DriverManager.getConnection(url, postgres.getUsername(), postgres.getPassword());
             Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }
}
