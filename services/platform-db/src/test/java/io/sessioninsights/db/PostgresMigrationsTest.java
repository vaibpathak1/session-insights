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
    }

    static void assertCheckViolation(String sql) {
        assertThatThrownBy(() -> exec(sql))
                .isInstanceOf(SQLException.class)
                .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("23514"));
    }

    static void exec(String sql) throws SQLException {
        try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }
}
