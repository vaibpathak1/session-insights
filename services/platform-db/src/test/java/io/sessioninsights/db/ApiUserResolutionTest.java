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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V6: resolve_app_user() is the API's only pre-tenant read (ADR-0013). */
@Testcontainers
class ApiUserResolutionTest {

    @Container
    static final PostgreSQLContainer postgres = TestContainers.postgres();

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration/postgres")
                .placeholders(TestContainers.flywayPlaceholders())
                .load()
                .migrate();
    }

    @Test
    void resolvesOnlyActiveUsersOfActiveTenantsCaseInsensitively() throws SQLException {
        UUID tenant = tenant(true);
        UUID alice = user(tenant, "Alice@Example.com", true);
        user(tenant, "gone@example.com", false);
        user(tenant(false), "inactive-tenant@example.com", true);

        assertThat(resolve("alice@example.COM")).containsExactly(alice + "/" + tenant + "/ADMIN");
        assertThat(resolve("gone@example.com")).isEmpty();
        assertThat(resolve("inactive-tenant@example.com")).isEmpty();
        assertThat(resolve("nobody@example.com")).isEmpty();
    }

    @Test
    void theSameEmailInTwoTenantsReturnsBothSoTheCallerCanRefuse() throws SQLException {
        user(tenant(true), "shared@example.com", true);
        user(tenant(true), "shared@example.com", true);
        assertThat(resolve("shared@example.com")).hasSize(2);
    }

    @Test
    void onlyTheAppRoleMayCallItAndItIsHardened() throws SQLException {
        for (String[] role : new String[][] {
                {TestContainers.COLLECTOR_USER, TestContainers.COLLECTOR_PASSWORD},
                {TestContainers.PROCESSOR_USER, TestContainers.PROCESSOR_PASSWORD}}) {
            try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), role[0], role[1]);
                 Statement s = c.createStatement()) {
                assertThatThrownBy(() -> s.executeQuery("SELECT * FROM resolve_app_user('x@example.com')"))
                        .hasMessageContaining("permission denied");
            }
        }
        try (Connection c = owner(); Statement s = c.createStatement();
             var rs = s.executeQuery("SELECT prosecdef, proconfig FROM pg_proc WHERE proname = 'resolve_app_user'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getBoolean("prosecdef")).isTrue();
            assertThat((String[]) rs.getArray("proconfig").getArray()).containsExactly("search_path=pg_catalog, public");
        }
    }

    private static List<String> resolve(String email) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), TestContainers.APP_USER, TestContainers.APP_PASSWORD);
             PreparedStatement s = c.prepareStatement("SELECT * FROM resolve_app_user(?)")) {
            s.setString(1, email);
            try (var rs = s.executeQuery()) {
                while (rs.next()) {
                    rows.add(rs.getObject("user_id") + "/" + rs.getObject("tenant_id") + "/" + rs.getString("role"));
                }
            }
        }
        return rows;
    }

    private static UUID tenant(boolean active) throws SQLException {
        UUID id = UUID.randomUUID();
        exec("INSERT INTO tenant (id, name, is_active, deleted_at) VALUES ('" + id + "', 't', " + active + ", "
                + (active ? "NULL" : "now()") + ")");
        return id;
    }

    private static UUID user(UUID tenant, String email, boolean active) throws SQLException {
        UUID id = UUID.randomUUID();
        exec("INSERT INTO app_user (id, tenant_id, email, display_name, role, is_active, deleted_at) VALUES ('" + id + "', '"
                + tenant + "', '" + email + "', 'User', 'ADMIN', " + active + ", " + (active ? "NULL" : "now()") + ")");
        return id;
    }

    private static Connection owner() throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private static void exec(String sql) throws SQLException {
        try (Connection c = owner(); Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }
}
