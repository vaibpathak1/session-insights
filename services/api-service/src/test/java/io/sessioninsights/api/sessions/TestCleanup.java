package io.sessioninsights.api.sessions;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.UUID;

final class TestCleanup {

    private TestCleanup() {
    }

    /** Removes a session a test added, so the shared fixtures stay as seeded. */
    static void deleteSession(PostgreSQLContainer postgres, UUID session) {
        new JdbcTemplate(new SingleConnectionDataSource(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword(), true)).update("DELETE FROM user_session WHERE id = ?", session);
    }
}
